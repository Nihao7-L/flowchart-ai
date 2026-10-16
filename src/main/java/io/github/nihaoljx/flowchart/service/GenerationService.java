package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.agent.AgentLoopListener;
import io.github.nihaoljx.flowchart.agent.AgentLoopService;
import io.github.nihaoljx.flowchart.graph.validate.RequestIntent;
import io.github.nihaoljx.flowchart.llm.AgentResult;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolCall;
import io.github.nihaoljx.flowchart.session.InMemoryMemoryStore;
import io.github.nihaoljx.flowchart.session.MemoryStore;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成编排层（单一 agent 路径）
 *
 * 只有一条生成路径：agent loop（ReAct 循环 + 领域工具）。
 *
 * 本类只保留**编排职责**：会话状态读取（编辑基线 / 手绘旁路
 * take-over）、提示词取块、驱动一次 agent run、终态处置
 * （COMPLETED 落库下发 / PARTIAL 兜底）与跨轮记忆记录。
 * 判定逻辑全在 SceneGate，时间预算在 GenerationBudget，
 * SSE 出口在 GenerationEvents，种子工位在 SceneSeedBuilder，
 * 兜底决策在 GenerationFallback。
 */
@Service
public class GenerationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    GenerationService.class);

    /**
     * 静态层正文的唯一真相源（提示词正文 + 契约表都从这里派生）。
     * 仅为读 `promptVersion()` 而注入 —— 版本号不再写死在本类。
     */
    private final PromptService promptService;

    private final AgentLoopService agent;

    private final SessionStore sessionStore;

    /** 会话模型的序列化/反序列化（后端持 IR 唯一真相源，见 ADR-2） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** JSON 种子工位：手绘提升 / 版本盖章 / 空判定 / id 读取 / 占位合成 */
    private final SceneSeedBuilder seedBuilder;

    /** 失败兜底决策：话术 / 摘要 / 选材 / 下发收尾 */
    private final GenerationFallback fallback;

    /**
     * 跨轮记忆：按会话累积校验 issue 历史（只落"记录"，
     * 把历史注入提示词的消费策略归 m5）。
     * 进程内实现、随本 bean 存活，不改构造器签名（单测零感知）。
     */
    private final MemoryStore memoryStore = new InMemoryMemoryStore();

    /**
     * 单构造器：agent 是唯一路径，运行入口必需注入
     * （候选表解析失败时 AgentLoopService 自己 fail-fast，
     * 不会留下"能启动但生成必败"的半死状态）。
     *
     * 场景管线与闸门不在这里装配 —— 它们活在 agent 工具注册表
     * （AgentTools.defaultRegistry），判定逻辑只有一份。
     */
    public GenerationService(
            AgentLoopService agent,
            SessionStore sessionStore,
            PromptService promptService) {
        this.agent = agent;
        this.sessionStore = sessionStore;
        this.promptService = promptService;
        this.seedBuilder = new SceneSeedBuilder(objectMapper);
        this.fallback = new GenerationFallback(seedBuilder);
    }

    /**
     * 主入口：接收用户文本，跑一次 agent run，通过 SSE 推送事件
     *
     * @param sessionId 会话 ID：用来取/存"当前图模型"。为空则退化成
     *                  无状态生成（不读上下文、不落库）
     * @param userText 用户输入
     * @param emitter  SSE 发射器
     */
    public void generate(
            String sessionId,
            String userText,
            SseEmitter emitter) {
        long deadline = System.currentTimeMillis()
                + GenerationBudget.totalBudgetMs();
        try {
            generateViaAgent(
                    sessionId, userText,
                    emitter, deadline);
        } catch (RuntimeException e) {
            // 生成本身跑在独立线程里：异常一旦不接住就直接打穿线程，
            // 前端什么反馈都收不到（曾因 emitter 已被容器关闭而裸崩）
            LOG.error("生成线程异常终止", e);
            GenerationEvents.sendError(emitter,
                    "生成异常终止: " + e.getMessage());
        }
    }

    /**
     * 生成主流程：messages 单线积累 + 领域工具循环
     *
     * 会话状态（当前图 / 手绘旁路 / take-over）先读好；提示词走
     * promptService 的两块直取（system 稳定块 + user 块）；过程经
     * AgentLoopListener 翻译成 SSE 事件；终态 COMPLETED 落库下发、
     * PARTIAL 走兜底（最后一份可解析进度优先，不伪装成功）。
     */
    private void generateViaAgent(
            String sessionId,
            String userText,
            SseEmitter emitter,
            long deadline) {
        // 会话里已有模型 = 用户在"改自己那张图"；没有 = 新建。
        // 这个判断决定 prompt 里要不要塞入当前场景：
        // 不塞的后果是"删掉某个节点"被 LLM 理解成"画一张新图"，
        // 前端整体替换画布 → 之前画的全没了。
        String previousJson =
                currentModelJson(sessionId);
        boolean editMode = previousJson != null;
        // Bug A：用户只手绘了图形、还没有 AI 元素时，模型的
        // `elements` 是空的、手绘都在 `_userElements` 旁路里。
        // 按原样进编辑模式，LLM 看到的是一张空图，于是重画一张全新的
        // （用户看到右边自己的手绘、左边一张新的 AI 图）。把
        // 手绘提升进可编辑场景让 LLM 原地修改，保存时清掉旁路
        // （手绘已被吸收，不该再注入下一轮 prompt）。
        String effectiveSceneJson = previousJson;
        boolean takeOver = false;
        List<Map<String, Object>> userElements =
                currentUserElements(sessionId);
        if (previousJson != null) {
            String seeded = seedBuilder.buildSeedScene(previousJson);
            if (seeded != null) {
                takeOver = true;
                effectiveSceneJson = seeded;
                userElements = List.of();
            }
        }

        // 需求类别闸门（2026-10-16 ref_cat 事故）：
        // 绘画/构图类需求（写实物体、人物动物、风景、卡通形象、
        // 「用几何形状拼出一个形象」这类）在当前唯一提交路径下
        // **结构上不可能满足** —— 这类需求里"位置本身就是语义"，
        // 而 submit_spec 把坐标全交布局引擎。过去模型只能把它翻译成
        // 一张零件关系图（body→head→ears…），系统还报"校验通过"，
        // 用户看到一堆没有名字的椭圆。
        // 与其让模型冒充，不如如实说明能力边界：前置拦截 →
        // 下发带【AI 备注】的说明占位（顺带省掉一次 25~30 秒的调用）。
        // 编辑模式不动用户的图，只把说明当消息回给他。
        if (RequestIntent.classify(userText).isNonDiagram()) {
            LOG.info("需求判定为绘画/构图类，走能力边界说明（不调模型）");
            String note = "这是绘画类需求（画形象），当前只支持图表类主题："
                    + "本次不生成图表冒充，已给出说明与可图解的问法";
            String scene = previousJson != null
                    ? fallback.fallbackScene(previousJson,
                            effectiveSceneJson, takeOver, null, note)
                    : seedBuilder.limitationScene(userText);
            if (fallback.deliverFallback(emitter, note, scene)) {
                return;
            }
            GenerationEvents.sendError(emitter,
                    "能力边界说明场景合成失败");
            return;
        }

        String systemBlock;
        String userBlock;
        try {
            systemBlock = promptService.buildSystemBlock();
            userBlock = promptService.buildUserBlock(
                    userText, effectiveSceneJson);
            // m5 落地：消费跨轮 issue 历史（MemoryStore 只写不读是
            // 死路径）。把本会话最近踩过的校验问题压成一小段注入
            // user block，模型显式避开已知的坑，而不是每轮从零发现
            userBlock = withIssueHistory(userBlock, sessionId);
        } catch (IOException e) {
            // 读不到提示词资源是后端自己的故障，保持 error 语义，
            // 不下发假图
            GenerationEvents.sendError(emitter,
                    "加载 schema 失败: " + e.getMessage());
            return;
        }

        GenerationEvents.sendEvent(emitter, "thinking",
                "Agent 模式（候选 " + agent.candidateName()
                        + "），messages 单线积累 + 工具循环");
        // 步级可观测（2026-10-15 gen30 事故补课）：此前生成中途
        // 控制台零日志，一次远端挂死 27 分钟肉眼只看到"没产出"。
        // 每步开始打一行已用时，之后哪一步耗时异常一眼可见。
        final long runStart = System.currentTimeMillis();
        AgentLoopListener listener = new AgentLoopListener() {
            @Override
            public void onStep(int step, int maxSteps) {
                long elapsed = (System.currentTimeMillis()
                        - runStart) / 1000;
                LOG.info("Agent 第 {}/{} 步开始（已用时 {}s）",
                        step, maxSteps, elapsed);
                GenerationEvents.sendEvent(emitter, "thinking",
                        "Agent 第 " + step + "/" + maxSteps
                                + " 步思考中...");
            }

            @Override
            public void onToolCall(ToolCall call) {
                GenerationEvents.sendEvent(emitter, "tool_call",
                        call.name() + " "
                                + GenerationEvents.briefArgs(call));
            }

            @Override
            public void onToolResult(
                    ToolCall call, Observation obs) {
                GenerationEvents.sendEvent(emitter, "tool_result",
                        call.name() + " → "
                                + (obs.ok() ? "✓ " : "✗ ")
                                + obs.summary());
                // 跨轮记忆：校验未过的提交/自查当场记录（有界存储，
                // 消费归 m5）。非校验类工具的 issues 恒为空列表，
                // 不会误记。
                if (!obs.ok() && !obs.issues().isEmpty()) {
                    memoryStore.recordIssues(sessionId,
                            obs.issues());
                }
            }
        };

        AgentResult result;
        try {
            result = agent.run(systemBlock, userBlock,
                    userElements, previousJson,
                    deadline, listener);
        } catch (Exception e) {
            // LLM 是外部依赖：它超时/报错是在我们正常工作的过程中
            // 发生的，不是后端故障。此时给用户看一张「能画出来的图」
            // （编辑模式就是他原有的那张）远好过看一句 error ——
            // 空画布是这个产品能出的最高危事故。
            LOG.warn("Agent run 异常终止，转兜底", e);
            String note = "LLM 调用失败: " + e.getMessage()
                    + "；" + fallback.fallbackOutcome(editMode)
                    + "，可稍后重试";
            if (fallback.deliverFallback(emitter, note,
                    fallback.fallbackScene(previousJson,
                            effectiveSceneJson, takeOver,
                            null, note))) {
                return;
            }
            GenerationEvents.sendError(emitter, "兜底场景合成失败");
            return;
        }

        LOG.info("Agent run 终态 {} steps={} reason={} 用时={}s",
                result.status(), result.steps(),
                result.reason(),
                (System.currentTimeMillis() - runStart) / 1000);
        if (result.status() == AgentResult.Status.COMPLETED
                && result.sceneJson() != null) {
            String json = seedBuilder.stampSchemaVersion(
                    result.sceneJson());
            saveModel(sessionId, json, !takeOver);
            GenerationEvents.sendEvent(emitter, "result", json);
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                LOG.warn("SSE 通道已结束，跳过收尾: {}",
                        e.getMessage());
            }
            return;
        }

        // PARTIAL：最后一份可解析进度优先（fallbackScene 的
        // lastParseable 槽位），说明文案如实（几步、为什么停），
        // 不伪装成功
        String note = "Agent 循环未能通过校验（"
                + result.reason() + "，共 "
                + result.steps() + " 步）；"
                + fallback.fallbackOutcome(editMode)
                + "，请把图形拆小后重试";
        if (fallback.deliverFallback(emitter, note,
                fallback.fallbackScene(previousJson,
                        effectiveSceneJson, takeOver,
                        result.sceneJson(), note))) {
            return;
        }
        GenerationEvents.sendError(emitter,
                "Agent 循环未能产出可用场景: "
                        + result.reason());
    }

    /**
     * 读会话里"用户手绘元素"的轻量描述
     *
     * @param sessionId 会话 ID
     * @return 描述列表；没有时返回空列表（不返回 null，调用方不必判空）
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> currentUserElements(
            String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        Map<String, Object> model =
                sessionStore.getCurrentModel(sessionId);
        if (model == null) {
            return List.of();
        }
        Object raw = model.get(
                SessionStore.USER_ELEMENTS_KEY);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result =
                new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    /**
     * 把本会话的 issue 历史压成一段注入 user block（m5 消费侧）。
     *
     * <p>有界：最多 5 条、按 reason 去重、每条截断 —— 它是"避雷
     * 提示"不是"第二份需求"，绝不能涨到挤压主需求的体量。无历史
     * 时原样返回，一个字节都不加。
     */
    private String withIssueHistory(String userBlock, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return userBlock;
        }
        List<MemoryStore.IssueEntry> recent =
                memoryStore.recent(sessionId, 8);
        if (recent.isEmpty()) {
            return userBlock;
        }
        StringBuilder sb = new StringBuilder(userBlock);
        sb.append("\n\n【历史校验问题（本会话之前踩过，尽量避开）】\n");
        Set<String> seenReasons = new java.util.HashSet<>();
        int shown = 0;
        for (MemoryStore.IssueEntry entry : recent) {
            if (shown >= 5) {
                break;
            }
            String reason = entry.reason() == null
                    ? "" : entry.reason().trim();
            if (reason.isEmpty() || !seenReasons.add(reason)) {
                continue;
            }
            sb.append("- ").append(entry.field()).append(": ")
                    .append(truncate(reason, 80));
            if (entry.hint() != null && !entry.hint().isBlank()) {
                sb.append("（").append(
                        truncate(entry.hint().trim(), 60)).append("）");
            }
            sb.append('\n');
            shown++;
        }
        if (shown == 0) {
            return userBlock;
        }
        return sb.toString();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max
                ? text : text.substring(0, max) + "…";
    }

    /**
     * 读会话当前模型（JSON 文本）
     *
     * @param sessionId 会话 ID
     * @return 模型 JSON；没有模型 / sessionId 为空 / 序列化失败时返回 null
     *         （返回 null = 当作"新建图"，不能因为取不到上下文而失败）
     */
    private String currentModelJson(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Map<String, Object> model =
                sessionStore.getCurrentModel(sessionId);
        if (model == null) {
            return null;
        }
        try {
            return objectMapper
                    .writeValueAsString(model);
        } catch (JsonProcessingException e) {
            LOG.warn("会话模型序列化失败: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Persist the validated scene back to the session.
     *
     * Stores exactly the copy the frontend received (already binding-normalized),
     * so the next edit round sees the same scene the user is looking at.
     *
     * @param preserveUserElements when false (Bug A take-over), the stale
     *        `_userElements` bypass is dropped so the hand-drawings the
     *        LLM just absorbed are not re-injected into the next prompt.
     */
    private void saveModel(
            String sessionId, String json,
            boolean preserveUserElements) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> model =
                    objectMapper.readValue(
                            json,
                            new TypeReference<
                                    Map<String, Object>>() {
                            });
            // The user-drawing bypass is not part of the LLM output; on a
            // normal save we carry it forward so the next prompt still "sees"
            // what the user drew. On a take-over the hand-drawings have been
            // absorbed by the AI, so we drop the bypass instead of letting a
            // stale "old drawing + new diagram" mislead the next round.
            List<Map<String, Object>> userElements =
                    preserveUserElements
                            ? currentUserElements(sessionId)
                            : List.of();
            if (!userElements.isEmpty()) {
                model.put(SessionStore.USER_ELEMENTS_KEY,
                        userElements);
            }
            sessionStore.setCurrentModel(
                    sessionId, model);
        } catch (JsonProcessingException e) {
            LOG.warn("会话模型落库失败: {}",
                    e.getMessage());
        }
    }
}
