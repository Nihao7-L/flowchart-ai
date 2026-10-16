package io.github.nihaoljx.flowchart.agent;

import io.github.nihaoljx.flowchart.agent.tools.SubmitSpecTool;
import io.github.nihaoljx.flowchart.agent.tools.UpdateElementsTool;
import io.github.nihaoljx.flowchart.critique.CritiqueReport;
import io.github.nihaoljx.flowchart.critique.VisualFeedbackService;
import io.github.nihaoljx.flowchart.service.PromptService;

import java.util.LinkedHashMap;
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
 *     if 提交了 submit_spec / update_elements 且通过校验:
 *        → 跑视觉自纠批判（若启用且有问题）：回写结构化引导 → 再来一轮
 *        → 否则 COMPLETED
 *     if 提交了 submit_spec / update_elements 但未过: → 追加修正指令（点名化 issue），继续
 *     if 纯文本轮:                       → 回喂"请用工具或提交"，继续
 *                                        （下一轮 tool_choice=required 强制出工具）
 *     if 同工具同参数原样重发:            → 第2次软干预、第4次硬早退
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

    /**
     * 单次 LLM 调用的超时硬帽（秒）。
     *
     * 2026-10-15 gen30 事故的治本点：此前单轮超时 = 剩余总预算
     * （TOTAL_BUDGET_MS=1_800_000 → 首轮调用 1800s）。候选一旦在
     * 远端挂住（连接活着但迟迟不回），这一发就**安静烧满 30 分钟**
     * —— 不抛异常、不触发故障转移、不打日志，肉眼只看到"卡死"。
     * 实测正常首步收敛全部落在 5~114s：帽子先设 300s（regress_arch2
     * 第 4 步实测仍被挂死候选烧满 5 分钟），2026-10-15 收到 180s
     * —— 给正常收敛留 60% 裕量的同时，把挂死代价从 5 分钟压到
     * 3 分钟。帽子一超抛 HttpTimeoutException，超时刻意不算瞬时
     * 错误（FailoverAgentClient.isTransient）→ 立即换下一家候选，
     * 并留下一行 WARN。
     *
     * 2026-10-16 用户拍板收回到 100s：这一版的 SLA 是「单图 ≤100 秒，
     * 超 100 秒即判失败」。单次调用既然不许超过 100s，整图（含可能的
     * 修正轮）就只可能更快 —— 把帽子和 SLA 对齐，才不会出现"每张图
     * 合法地跑 300 秒"这种与 SLA 互相打架的口径。
     *
     * 前提是模型不再手算几何：语义路径（submit_spec，只交节点与连线）
     * 实测第 1 步 5~30 秒收敛，100s 是 3 倍以上的裕量；反过来说，
     * 一份需要 >100s 的调用必然是又回到了"自己算坐标画样式"的老路，
     * 那时候早掐断、换候选，比等它画完更接近用户要的结果。
     *
     * ⚠️ 2026-10-16 深夜修正（两轮实测迭代）：
     * ① 复杂图（8 参与者时序图）让模型自选 submit_scene，合法地慢，
     *    100s 总帽误杀正在吐字的 GLM（后端已生成成功却因 SSE 140s 先关
     *    而送达失败）—— 故接入流式 + 空闲判死（见 OpenAiToolClient），
     *    本帽退居"单次调用的绝对天花板"而非主判据。
     * ② 但第一版把本帽抬到 950s 踩坑：GLM（tier1 主力）画该时序图单跳
     *    流式狂奔 366s 且输出非法（8 轮校验全打回 → 占位符）。空闲判死
     *    救不了它（它在持续吐字、不是卡死，即"流着垃圾"盲区）。
     *    而实测 GLM 一旦被切、deepseek（tier2）84s 就产出合法 41 节点图。
     * ③ 故定 180s：足够合法候选跑完（deepseek ~84s，2 倍余量），又能在
     *    狂奔候选（GLM ~366s）烧穿预算前切断、逼故障转移到下家。真正
     *    的根治是候选排序（见 AgentLoopService / application.yml）——
     *    本帽只是防"单次调用无限跑"的理智线。
     */
    public static final int CALL_TIMEOUT_CAP_SECONDS = 180;

    /** 纯文本轮的回喂提示（模型只说话不干活时给一条明确的出路） */
    private static final String NO_TOOL_NUDGE =
            "你没有调用任何工具。画图必须通过工具完成："
                    + "所有结构化图表（流程/架构/时序/组织/思维导图等）"
                    + "都用 submit_spec 语义提交（只给节点与连线，"
                    + "坐标由布局引擎算）；需要看当前场景可 read_scene。";

    /**
     * 振荡干预提示（2026-10-16 补）：同工具同参数原样重发第二次时回喂。
     *
     * <p>实测病态模式：同一个坏调用（如早期 siliconflow 流式空工具名）
     * 原样重复 3~4 次，8 步烧完只落占位符。市场共识做法是重复检测 +
     * 硬干预；这里是软干预版（第 2 次提示、第 4 次早退），既给模型
     * 留一轮自救，又不让整场预算被空转吃光。
     */
    private static final String OSCILLATION_NUDGE =
            "你在原样重复同一个调用。重复同样的输入不会换来新结果："
                    + "要么按上一条工具反馈真正修改参数，要么承认这一步"
                    + "做不了、换一种做法（拆小图 / 换工具），"
                    + "不要原样重发。";

    /** 同一调用原样重复到这么多次 ⇒ 提前收手（PARTIAL），不烧完步数 */
    private static final int OSCILLATION_HARD_STOP = 4;

    private final AgentLlmClient client;
    private final ToolRegistry registry;
    private final PromptService promptService;
    private VisualFeedbackService visualFeedback;

    /**
     * 完整构造（含视觉自纠闭环）。
     *
     * @param visualFeedback 可为 null：null 时等同于关闭视觉反馈，
     *                      管线行为与旧版完全一致（见 D2 视觉自纠闭环）。
     */
    public AgentLoop(AgentLlmClient client, ToolRegistry registry,
                     PromptService promptService,
                     VisualFeedbackService visualFeedback) {
        this.client = client;
        this.registry = registry;
        this.promptService = promptService;
        this.visualFeedback = visualFeedback;
    }

    /** 向后兼容：不接视觉反馈（默认关闭） */
    public AgentLoop(AgentLlmClient client, ToolRegistry registry,
                     PromptService promptService) {
        this(client, registry, promptService, null);
    }

    /** 注入视觉自纠服务（供 Spring 装配在构造后设置；仅允许从 null 设置一次） */
    public void setVisualFeedback(VisualFeedbackService visualFeedback) {
        if (this.visualFeedback == null) {
            this.visualFeedback = visualFeedback;
        }
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
     *                   由 GenerationService 统一兜底，与本类无关
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
     * 全参入口：previousJson 非空 = 编辑模式，
     * 场景闸门对首次提交跑改图保真检查（提醒一次，不是门禁）。
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
        // 振荡检测账本：调用摘要（工具名 + 参数 JSON）→ 原样重复次数
        Map<String, Integer> callDigests = new LinkedHashMap<>();
        // 纯文本空转过一次后，下一轮物理上强制调工具（tool_choice=required）
        boolean forceTool = false;
        while (steps < MAX_STEPS) {
            if (System.currentTimeMillis() >= deadlineMs) {
                return partial(ctx, steps, "总预算耗尽");
            }
            steps++;
            safeStep(listener, steps);
            AgentTurn turn = client.respond(
                    messages.snapshot(), registry.schemas(),
                    callTimeoutSeconds(deadlineMs),
                    forceTool ? "required" : "auto");
            messages.appendAssistant(turn);

            if (turn.toolCalls().isEmpty()) {
                messages.appendUser(NO_TOOL_NUDGE);
                forceTool = true;
                continue;
            }
            forceTool = false;
            Observation submitted = null;
            for (ToolCall call : turn.toolCalls()) {
                safeToolCall(listener, call);
                Observation obs = registry.dispatch(call, ctx);
                messages.appendToolResult(call, obs);
                safeToolResult(listener, call, obs);
                // 振荡检测（2026-10-16）：同工具同参数原样重发。
                // 第 2 次软干预（给一轮自救），第 4 次硬早退（PARTIAL）。
                String digest = call.name() + "|"
                        + (call.arguments() == null
                        ? "{}" : call.arguments().toString());
                int seen = callDigests.merge(digest, 1, Integer::sum);
                if (seen == 2) {
                    messages.appendUser(OSCILLATION_NUDGE);
                } else if (seen >= OSCILLATION_HARD_STOP) {
                    return partial(ctx, steps,
                            "振荡：同一调用原样重复 " + seen + " 次，提前收手");
                }
            // 两类提交工具的成功都是终态：submit_spec（语义路径，
            // validScene 已由布局家族产出）与 update_elements（增量编辑）
            // 同权 —— 不再继续空转剩余步数。submit_scene 已在 v10.12.0
            // 冻结，不再注册（让 LLM 算坐标是反模式，见战略报告）
            if (UpdateElementsTool.NAME.equals(call.name())
                    || SubmitSpecTool.NAME.equals(call.name())) {
                submitted = obs;
            }
            }
            if (submitted != null && submitted.ok()) {
                // D2 视觉自纠闭环：给 LLM「装眼睛」。校验通过不代表渲染好看，
                // 让几何/视觉批判器看一眼布局后的图，有问题就回写**结构性**
                // 引导（间距/加宽/走向），再来一轮修正；反馈必须是结构性的，
                // 不做像素修补（D6）。visualFeedback 为 null 或开关关闭时等于
                // 旧行为。steps < MAX_STEPS 保证还有至少一轮可用来修正。
                CritiqueReport report = visualFeedback == null ? null
                        : visualFeedback.critique(ctx.validScene(), userRequest);
                if (report != null && report.needsFix() && steps < MAX_STEPS) {
                    messages.appendUser(report.guidance());
                    continue;
                }
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

    /**
     * 单轮调用的实际超时 = 剩余预算与 {@link #CALL_TIMEOUT_CAP_SECONDS}
     * 取小 —— 预算只决定"整场最多跑多久"，不该被一次远端挂死的花
     * 整场吃掉。
     */
    private static int callTimeoutSeconds(long deadlineMs) {
        return Math.min(CALL_TIMEOUT_CAP_SECONDS,
                remainingSeconds(deadlineMs));
    }
}
