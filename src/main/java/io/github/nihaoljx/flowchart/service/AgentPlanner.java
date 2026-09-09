package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.client.stream.ProgressContext;
import io.github.nihaoljx.flowchart.client.stream.ProgressEvent;
import io.github.nihaoljx.flowchart.model.GraphJson;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 任务41：Agent 规划与自主修正（ReAct 模式）
 *
 * 核心理念：LLM 不只是"出图"，而是"做事"——接收任务后自己拆步骤、执行、验证、修正。
 *
 * ReAct 循环（Thought → Action → Observation）：
 *   1. Thought：LLM 分析需求，决定需要哪些步骤（检索文档？生成图？校验？修正？）
 *   2. Action：执行每个步骤（调用 RAG / 生成 / 校验）
 *   3. Observation：观察结果，判断是否需要继续
 *   4. 循环直到：任务完成 / 超过最大步数 / 用户中断
 *
 * 与 Task 53 Loop Engineering 的区别：
 *   - Loop 只在"生成→校验→修正"这个单一环节循环
 *   - Agent 是更通用的"自主规划多步骤"，可能包含"先检索再生成""分多次生成再合并"等
 *
 * 验收：输入"画一个电商系统架构"，Agent 自动拆成多步（检索→生成→校验→修正），而非一次性瞎编。
 */
@Service
public class AgentPlanner {

    private final LlmProvider llmProvider;
    private final RagService ragService;
    private final ToolExecutor toolExecutor;
    private final ParserService parserService;
    private final DiagramService diagramService;
    private final GraphJsonService graphJsonService;
    private final GraphValidator graphValidator;
    private final PromptService promptService;

    /** Agent 最大执行步数（防无限循环） */
    private static final int MAX_STEPS = 5;

    /** ObjectMapper 用于解析 Agent 的计划 JSON */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public AgentPlanner(LlmProvider llmProvider, RagService ragService, ToolExecutor toolExecutor,
                        ParserService parserService, DiagramService diagramService,
                        GraphJsonService graphJsonService, GraphValidator graphValidator,
                        PromptService promptService) {
        this.llmProvider = llmProvider;
        this.ragService = ragService;
        this.toolExecutor = toolExecutor;
        this.parserService = parserService;
        this.diagramService = diagramService;
        this.graphJsonService = graphJsonService;
        this.graphValidator = graphValidator;
        this.promptService = promptService;
    }

    /**
     * Agent 执行入口：接收用户需求，自主规划并执行多步任务。
     *
     * @param userText  用户输入
     * @param type      图表类型
     * @param useRag    是否启用知识库
     * @param useTool   是否启用工具
     * @return 最终的图数据 + 渲染结果
     */
    public AgentResult execute(String userText, String type, boolean useRag, boolean useTool) {
        ProgressContext.publish(ProgressEvent.info("🧠 Agent 正在分析需求并制定执行计划…"));

        // 第一步：让 LLM 制定计划
        List<String> plan = planSteps(userText, type, useRag, useTool);
        ProgressContext.publish(ProgressEvent.info("📋 Agent 计划：共 " + plan.size() + " 个步骤"));
        for (int i = 0; i < plan.size(); i++) {
            ProgressContext.publish(ProgressEvent.info("  步骤 " + (i + 1) + ": " + plan.get(i)));
        }

        // 第二步：逐步执行
        String context = "";
        GraphJson finalGraph = null;
        String plantUml = null;

        for (int step = 0; step < Math.min(plan.size(), MAX_STEPS); step++) {
            String action = plan.get(step);
            ProgressContext.publish(ProgressEvent.info("▶️ 执行步骤 " + (step + 1) + ": " + action));

            if (action.contains("检索") || action.contains("搜索") || action.contains("知识库")) {
                // 检索步骤
                if (useRag && ragService.isEnabled()) {
                    ProgressContext.publish(ProgressEvent.info("📚 正在检索知识库…"));
                    RagService.RagResult ragResult = ragService.retrieveWithMeta(userText);
                    context += ragResult.context();
                    ProgressContext.publish(ProgressEvent.info("✅ 检索完成，命中 " + ragResult.hits().size() + " 条"));
                } else {
                    ProgressContext.publish(ProgressEvent.info("⏭️ 跳过检索（未启用知识库）"));
                }

            } else if (action.contains("工具") || action.contains("外部信息")) {
                // 工具调用步骤
                if (useTool) {
                    ProgressContext.publish(ProgressEvent.info("🔧 正在让 AI 决定调用工具…"));
                    String extra = toolExecutor.collectContext(userText);
                    if (extra != null && !extra.isBlank()) {
                        context += extra;
                        ProgressContext.publish(ProgressEvent.info("✅ 工具结果已并入素材"));
                    }
                } else {
                    ProgressContext.publish(ProgressEvent.info("⏭️ 跳过工具调用（未启用）"));
                }

            } else if (action.contains("生成") || action.contains("画图")) {
                // 生成步骤
                ProgressContext.publish(ProgressEvent.info("🤖 正在生成图表…"));
                try {
                    String prompt = promptService.buildPrompt(userText, type, context);
                    String schema = promptService.loadSchema(type);
                    String llmText = llmProvider.chatStructuredStream(prompt, schema);

                    Map<String, Object> result = new HashMap<>();
                    plantUml = parseAndRender(type, llmText, result);
                    finalGraph = result.get("graphJson") instanceof GraphJson g ? g : null;

                    ProgressContext.publish(ProgressEvent.info("✅ 图表已生成"));
                } catch (Exception e) {
                    ProgressContext.publish(ProgressEvent.info("⚠️ 生成失败：" + e.getMessage()));
                }

            } else if (action.contains("校验") || action.contains("验证")) {
                // 校验步骤
                if (finalGraph != null) {
                    ProgressContext.publish(ProgressEvent.info("🔍 正在校验图表…"));
                    var issues = graphValidator.validate(finalGraph, type);
                    if (issues.isEmpty()) {
                        ProgressContext.publish(ProgressEvent.info("✅ 校验通过"));
                    } else {
                        ProgressContext.publish(ProgressEvent.info("⚠️ 校验发现 " + issues.size() + " 个问题"));
                        // 尝试修正
                        ProgressContext.publish(ProgressEvent.info("🔄 正在自动修正…"));
                        try {
                            String correctionPrompt = promptService.buildCorrectionPrompt(userText, "", issues);
                            String schema = promptService.loadSchema(type);
                            String llmText = llmProvider.chatStructuredStream(correctionPrompt, schema);
                            Map<String, Object> result = new HashMap<>();
                            plantUml = parseAndRender(type, llmText, result);
                            finalGraph = result.get("graphJson") instanceof GraphJson g ? g : null;
                            ProgressContext.publish(ProgressEvent.info("✅ 修正完成"));
                        } catch (Exception e) {
                            ProgressContext.publish(ProgressEvent.info("⚠️ 修正失败：" + e.getMessage()));
                        }
                    }
                }

            } else {
                // 未知步骤，跳过
                ProgressContext.publish(ProgressEvent.info("⏭️ 跳过未知步骤: " + action));
            }
        }

        // 返回最终结果
        if (finalGraph != null && plantUml != null) {
            try {
                String svg = diagramService.renderToSvg(plantUml);
                String mermaid = graphJsonService.toMermaid(finalGraph);
                return new AgentResult(finalGraph, svg, mermaid, "Agent 执行完成（" + plan.size() + " 步）");
            } catch (Exception e) {
                return new AgentResult(finalGraph, "", "", "Agent 执行完成，但渲染失败：" + e.getMessage());
            }
        }
        return new AgentResult(null, "", "", "Agent 执行完成，但未生成有效的图");
    }

    /**
     * 让 LLM 制定执行计划。
     * 计划是一个步骤列表（如 ["检索知识库", "生成图表", "校验"]）。
     */
    @SuppressWarnings("unchecked")
    private List<String> planSteps(String userText, String type, boolean useRag, boolean useTool) {
        String planPrompt = """
            你是一个 AI Agent，需要完成以下任务：根据用户描述生成一张%s图表。

            用户描述：%s
            是否启用知识库：%s
            是否启用工具：%s

            请制定一个执行计划（JSON 数组格式），每个元素是一个步骤描述。
            步骤可能包含：检索、工具调用、生成、校验、修正 等。
            只返回 JSON 数组，不要其他文字。

            示例：["检索知识库获取相关文档", "生成图表", "校验图表结构", "修正校验发现的问题"]
            """.formatted(type, userText, useRag ? "是" : "否", useTool ? "是" : "否");

        try {
            String raw = llmProvider.chat(planPrompt);
            // 提取 JSON 数组
            int start = raw.indexOf('[');
            int end = raw.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String json = raw.substring(start, end + 1);
                List<String> steps = MAPPER.readValue(json, List.class);
                if (!steps.isEmpty()) return steps;
            }
        } catch (Exception e) {
            ProgressContext.publish(ProgressEvent.info("⚠️ 计划生成失败，使用默认计划"));
        }

        // 默认计划
        List<String> defaultPlan = new ArrayList<>();
        if (useRag) defaultPlan.add("检索知识库获取相关文档");
        if (useTool) defaultPlan.add("调用外部工具获取信息");
        defaultPlan.add("生成图表");
        defaultPlan.add("校验图表结构");
        return defaultPlan;
    }

    /** 解析 LLM 输出并渲染（复用 DiagramController 的逻辑） */
    private String parseAndRender(String type, String llmText, Map<String, Object> result) throws Exception {
        return switch (type) {
            case "mindmap" -> {
                var m = parserService.parseMindmap(llmText);
                result.put("graphJson", graphJsonService.fromMindmap(m));
                yield diagramService.buildMindMap(m);
            }
            case "architecture" -> {
                var a = parserService.parseArchitecture(llmText);
                result.put("graphJson", a);
                yield diagramService.buildArchitecture(a);
            }
            default -> {
                var g = parserService.parse(llmText);
                result.put("graphJson", g);
                yield diagramService.buildPlantUml(g);
            }
        };
    }

    /** Agent 执行结果 */
    public record AgentResult(GraphJson graphJson, String svg, String mermaid, String summary) {}
}
