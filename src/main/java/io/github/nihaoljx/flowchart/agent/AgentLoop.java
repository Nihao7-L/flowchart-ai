package io.github.nihaoljx.flowchart.agent;

import io.github.nihaoljx.flowchart.agent.tools.SubmitSceneTool;
import io.github.nihaoljx.flowchart.agent.tools.UpdateElementsTool;
import io.github.nihaoljx.flowchart.service.PromptService;

import java.util.List;
import java.util.Map;
import io.github.nihaoljx.flowchart.llm.AgentTurn;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.llm.AgentLlmClient;
import io.github.nihaoljx.flowchart.llm.AgentMessages;
import io.github.nihaoljx.flowchart.llm.ToolCall;
import io.github.nihaoljx.flowchart.llm.AgentResult;

/**
 * agent 主循环（，learn-claude-code s01 的 while + tool_use）
 *
 * messages = [system(稳定块), user(需求)]
 * while steps < MAX_STEPS 且预算未耗尽:
 *     resp = client.respond(messages, tools)
 *     一批 tool_calls 全部执行 → observation 逐条追加回 messages
 *     if 提交了 submit_scene 或 update_elements 且通过校验: → COMPLETED
 *     if 提交了 submit_scene 但未过:     → 追加修正指令（点名化 issue），继续
 *     if 纯文本轮:                       → 回喂"请用工具或提交"，继续
 * 超限/超时 → PARTIAL（最后一份读得通的进度，不伪装成功）
 *
 * 双闸门：步数上限 {@value #MAX_STEPS} + 总预算 `deadlineMs`
 * （沿用 8d661b9 的 totalBudgetMs 口径，由调用方传入）。单轮超时由循环
 * 按剩余预算折算后传给 AgentLlmClient。无进展检测/checkpoint
 * 属 ，本类刻意不做。
 */
public final class AgentLoop {

    /** 步数上限：4 工具的小管线，8 步足够"读契约→提交→修正1~2轮" */
    public static final int MAX_STEPS = 8;

    /** 纯文本轮的回喂提示（模型只说话不干活时给一条明确的出路） */
    private static final String NO_TOOL_NUDGE =
            "你没有调用任何工具。画图必须通过工具完成："
                    + "先 submit_scene 提交场景 JSON；"
                    + "需要字段口径可先 read_contract，需要看当前场景可 read_scene。";

    private final AgentLlmClient client;
    private final ToolRegistry registry;
    private final PromptService promptService;

    public AgentLoop(AgentLlmClient client, ToolRegistry registry,
                     PromptService promptService) {
        this.client = client;
        this.registry = registry;
        this.promptService = promptService;
    }

    /**
     * 跑一次 agent run。
     *
     * @param systemBlock  system 稳定块（整轮字节级不变 —— KV-cache 纪律）
     * @param userRequest  用户需求原文
     * @param userElements 用户手绘旁路元素（去重器用；可空表）
     * @param deadlineMs   总预算截止时刻（epoch ms）
     * @return 终态（COMPLETED / PARTIAL）
     * @throws Exception LLM 调用的基础设施异常（网络等）—— 交调用方处置，
     *                   与 legacy 管线"异常打穿到 generate() 统一兜底"同口径
     */
    public AgentResult run(String systemBlock, String userRequest,
                           List<Map<String, Object>> userElements,
                           long deadlineMs) throws Exception {
        return run(systemBlock, userRequest, userElements,
                deadlineMs, null);
    }

    public AgentResult run(String systemBlock, String userRequest,
                           List<Map<String, Object>> userElements,
                           long deadlineMs,
                           AgentLoopListener listener) throws Exception {
        return run(systemBlock, userRequest, userElements,
                null, deadlineMs, listener);
    }

    /**
     * 全参入口（步骤 3）：previousJson 非空 = 编辑模式，
     * 场景闸门对首次提交跑改图保真检查（对齐 legacy 第 1 轮口径）。
     */
    public AgentResult run(String systemBlock, String userRequest,
                           List<Map<String, Object>> userElements,
                           String previousJson,
                           long deadlineMs,
                           AgentLoopListener listener) throws Exception {
        AgentMessages messages = AgentMessages.seed(systemBlock, userRequest);
        ToolContext ctx = new ToolContext(
                userRequest, userElements, previousJson);
        int steps = 0;
        while (steps < MAX_STEPS) {
            if (System.currentTimeMillis() >= deadlineMs) {
                return partial(ctx, steps, "总预算耗尽");
            }
            steps++;
            safeStep(listener, steps);
            AgentTurn turn = client.respond(
                    messages.snapshot(), registry.schemas(),
                    remainingSeconds(deadlineMs));
            messages.appendAssistant(turn);

            if (turn.toolCalls().isEmpty()) {
                messages.appendUser(NO_TOOL_NUDGE);
                continue;
            }
            Observation submitted = null;
            for (ToolCall call : turn.toolCalls()) {
                safeToolCall(listener, call);
                Observation obs = registry.dispatch(call, ctx);
                messages.appendToolResult(call, obs);
                safeToolResult(listener, call, obs);
                if (SubmitSceneTool.NAME.equals(call.name())
                        || UpdateElementsTool.NAME.equals(call.name())) {
                    submitted = obs;
                }
            }
            if (submitted != null && submitted.ok()) {
                return new AgentResult(AgentResult.Status.COMPLETED,
                        ctx.validScene(), steps, "校验通过");
            }
            if (submitted != null) {
                messages.appendUser(
                        promptService.buildFixBlock(submitted.issues()));
            }
        }
        return partial(ctx, steps, "步数上限 " + MAX_STEPS + " 轮未通过校验");
    }

    private static void safeStep(AgentLoopListener listener, int step) {
        if (listener == null) {
            return;
        }
        try {
            listener.onStep(step, MAX_STEPS);
        } catch (RuntimeException e) {
            // 观测失败不能打断生成：记一条就继续
        }
    }

    private static void safeToolCall(AgentLoopListener listener,
                                     ToolCall call) {
        if (listener == null) {
            return;
        }
        try {
            listener.onToolCall(call);
        } catch (RuntimeException e) {
            // 同上：观测失败不打断生成
        }
    }

    private static void safeToolResult(AgentLoopListener listener,
                                       ToolCall call, Observation obs) {
        if (listener == null) {
            return;
        }
        try {
            listener.onToolResult(call, obs);
        } catch (RuntimeException e) {
            // 同上：观测失败不打断生成
        }
    }

    /** PARTIAL 终态：交出最后一份读得通的进度（可能为 null），如实说明原因 */
    private static AgentResult partial(ToolContext ctx, int steps, String reason) {
        return new AgentResult(AgentResult.Status.PARTIAL,
                ctx.processedScene(), steps, reason);
    }

    /** 按剩余预算折算本轮超时（秒），下限 1 秒防 0/负值打穿 HTTP 客户端 */
    private static int remainingSeconds(long deadlineMs) {
        long remain = deadlineMs - System.currentTimeMillis();
        return (int) Math.max(1, remain / 1000);
    }
}
