package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.client.Tool;
import io.github.nihaoljx.flowchart.client.stream.ProgressContext;
import io.github.nihaoljx.flowchart.client.stream.ProgressEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * 任务39 核心：Tool Calling 执行器（大厂能力版）
 *
 * 流程：调 LLM 拿 tool_calls → 后端真执行工具 → 把结果作为"素材"返回。
 * 设计要点：
 *  - LLM 只当"调度员"决定调哪个工具 + 传什么参数，真正干活在后端 execute()。
 *  - 工具结果不直接让 LLM 生成最终图（出图必须走 chatStructured 保证 JSON 契约），
 *    而是作为 context 素材并入出图 prompt，与 RAG 注入同理。
 *  - 任何异常都降级为 null（无素材），让主流程退化为普通生成，不阻断出图。
 */
@Service
public class ToolExecutor {

    private final LlmProvider llmProvider;
    private final ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolExecutor(LlmProvider llmProvider, ToolRegistry registry) {
        this.llmProvider = llmProvider;
        this.registry = registry;
    }

    private static final String SYSTEM_PROMPT =
        "你是 FlowAI 的助手。用户要画图时，若需要参考本地文件、联网资料或知识库，先调用对应工具获取素材，"
        + "再把素材综合进最终回答。只有在确需外部信息时才调用工具，不要编造工具未返回的内容。";

    /** 多轮回灌：返回补充后的上下文素材（可能 null = LLM 没调工具） */
    public String collectContext(String userText) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", userText));

        StringBuilder collected = new StringBuilder();
        try {
            // 第一轮：让 LLM 决定要不要调工具（返回 tool_calls 或纯 content）
            String raw = llmProvider.chatWithTools(messages, registry.toolsJson());
            List<ToolCall> calls = parseToolCalls(raw);

            if (calls.isEmpty()) {
                return null; // LLM 觉得不需要工具 → 无素材，走普通生成
            }

            // 后端真去执行每个工具，把结果拼成素材
            for (ToolCall c : calls) {
                Tool tool = registry.get(c.name);
                if (tool == null) {
                    System.out.println("⚠️ 模型调用了未注册工具: " + c.name + "，跳过");
                    continue;
                }
                // 进度事件：非流式（/api/generate）调用时 ProgressContext 未挂出水口，此处为 no-op，安全
                // 带上 input，前端可区分同工具的多次调用（如两次 web_search 查询不同关键词）
                String input = String.valueOf(c.args.values().stream().findFirst().orElse(""));
                Map<String, Object> callExtra = new LinkedHashMap<>();
                callExtra.put("input", input);
                ProgressContext.publish(ProgressEvent.of("tool_call",
                        "🔧 执行任务：" + friendlyName(c.name), c.name, callExtra));
                try {
                    String result = tool.execute(c.args);   // ← 这里才真正"长手脚"
                    if (result == null || result.isBlank()) {
                        // 工具按需不可用（如沙箱未配）→ 视为无素材，跳过该工具，不影响其它工具
                        ProgressContext.publish(ProgressEvent.info("⚠️ 工具「" + friendlyName(c.name) + "」无可用结果，已跳过"));
                        continue;
                    }
                    // FixD：工具结果截断到 2000 字，防止超长输出（如 base64 图片、大段日志）污染出图 prompt
                    String flat = result.replaceAll("\\s+", " ").trim();
                    if (flat.length() > 2000) flat = flat.substring(0, 2000) + "…(超长已截断)";
                    collected.append("\n").append(flat);
                    publishToolResult(c, tool, result);
                } catch (Exception ex) {
                    // 关键修复（Fix1）：单个工具失败不再拖累整个素材收集，跳过它继续
                    System.err.println("⚠️ 工具「" + c.name + "」执行失败，跳过: " + ex.getMessage());
                    ProgressContext.publish(ProgressEvent.info("⚠️ 工具「" + friendlyName(c.name) + "」执行失败，已跳过"));
                }
                // （完整大厂做法：把 assistant(tool_calls) + tool(result) 回灌 messages 再调一次 LLM；
                //  本项目出图强约束 JSON，故工具结果并入 context 走 chatStructured，等价且更稳）
            }
        } catch (Exception e) {
            System.err.println("Tool 路由/执行失败，降级无素材: " + e.getMessage());
            return null;
        }
        return collected.toString().isBlank() ? null : collected.toString();
    }

    /**
     * 解析 provider 返回的 tool_calls。
     * OpenAI/Kimi 的真实结构里 name 与 arguments 都嵌套在 function 字段下：
     * {"tool_calls":[{ "id":..., "type":"function", "function":{ "name":..., "arguments": "{\"...\"}" } }]}
     */
    @SuppressWarnings("unchecked")
    private List<ToolCall> parseToolCalls(String raw) {
        List<ToolCall> out = new ArrayList<>();
        try {
            Map<String, Object> root = mapper.readValue(raw, Map.class);
            Object callsObj = root.get("tool_calls");
            if (callsObj == null) return out; // 普通 content，无工具调用
            List<Map<String, Object>> calls = (List<Map<String, Object>>) callsObj;
            for (Map<String, Object> c : calls) {
                String id = c.get("id") == null ? "" : String.valueOf(c.get("id"));
                Map<String, Object> fn = (Map<String, Object>) c.get("function");
                if (fn == null) continue;
                String name = String.valueOf(fn.get("name"));
                Map<String, Object> args = new LinkedHashMap<>();
                Object a = fn.get("arguments");
                if (a instanceof String s && !s.isBlank()) {
                    args = (Map<String, Object>) mapper.readValue(s, Map.class);
                } else if (a instanceof Map m) {
                    args = (Map<String, Object>) m;
                }
                out.add(new ToolCall(id, name, args));
            }
        } catch (Exception ignored) {
            // raw 是纯文本 → 返回空，上层降级
        }
        return out;
    }

    private record ToolCall(String id, String name, Map<String, Object> args) {}

    /** 任务39 UI 改版：把工具 key 翻译成给用户看的中文标签（思考流里不出现 raw web_search 等英文） */
    private String friendlyName(String key) {
        return switch (key) {
            case "web_search" -> "搜索网页";
            case "read_file" -> "读取本地文件";
            case "code_execute" -> "执行代码";
            case "retrieve_document" -> "检索知识库";
            default -> key;
        };
    }

    /**
     * 任务39 UI 改版：工具执行完推一条 tool_result 事件。
     * - web_search 有结构化结果 → items 进前端"搜索结果面板"（模仿 Kimi 右侧面板）
     * - 其它工具 → 结果前 80 字当预览进思考流
     * data 会被 DiagramController 拍平进 SSE 顶层（putAll），前端直接取 data.items / data.input。
     */
    private void publishToolResult(ToolCall c, Tool tool, String result) {
        try {
            Map<String, Object> extra = new LinkedHashMap<>();
            String input = String.valueOf(c.args.values().stream().findFirst().orElse(""));
            extra.put("input", input);

            String message;
            List<Map<String, Object>> structured = tool.structuredResults(c.args, result);
            if (!structured.isEmpty()) {
                extra.put("items", structured);
                message = "🔍 搜索「" + input + "」，命中 " + structured.size() + " 条网页";
            } else {
                String flat = result.replaceAll("\\s+", " ").trim();
                String preview = flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
                message = "✅ " + c.name + " 完成：" + preview;
            }
            ProgressContext.publish(ProgressEvent.of("tool_result", message, c.name, extra));
        } catch (Exception ignored) {
            // 进度推送失败不影响主流程
        }
    }
}
