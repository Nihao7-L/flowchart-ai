package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.LlmEffort;
import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.client.Message;
import io.github.nihaoljx.flowchart.context.AssembleInput;
import io.github.nihaoljx.flowchart.context.ContextAssembler;
import io.github.nihaoljx.flowchart.graph.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.GraphValidator;
import io.github.nihaoljx.flowchart.graph.SceneBinder;
import io.github.nihaoljx.flowchart.graph.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.SceneDrift;
import io.github.nihaoljx.flowchart.graph.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.SceneSanitizer;
import io.github.nihaoljx.flowchart.graph.ValidationIssue;
import io.github.nihaoljx.flowchart.model.ChatEvent;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成→校验→修正循环（v2-10）
 *
 * <p>流程：prompt → LLM → 校验 → 不通过则携带 issues
 * 重调 → 最多 3 轮。每一步都通过 SSE 推事件给前端。
 */
@Service
public class GenerationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    GenerationService.class);

    /**
     * 最大轮次 = 1 次首生成 + 最多 2 次修正轮（共 3 次 LLM 调用）
     *
     * <p>只有**校验报 issue** 才会进入下一轮，且下一轮用带 issues 的修正
     * prompt。校验一次通过就立刻下发，不再空跑（2026-09-17 修正）。
     */
    private static final int MAX_ROUNDS = 3;

    /**
     * 单次生成的总时间预算（毫秒）
     *
     * SSE 通道上限由 {@code DiagramController} 取"本值 + 收尾余量"推导，
     * 两者因此恒等对齐。旧版把 120 秒硬编码在控制器里、与本预算无关，
     * 而实测单轮 LLM 调用就要约 100 秒——第 2 轮必然撞墙，现象是
     * 流被容器掐断、前端颗粒无收（2026-09-16 定位）。
     *
     * <p>2026-10-07 由 420 秒上调到 900 秒：多候选故障转移后，"一家不行
     * 换下一家"要按候选数花时间，420 秒连一轮都走不完（实测 15 模块大图
     * 在第一轮就把 300 秒预算耗光、连第 2 轮都开不起来）。现在真正的
     * 防挂死手段是**每候选的空闲判死**（见 OpenAiCompatibleProvider），
     * 总预算只当兜底，所以可以放宽。
     *
     * <p>2026-10-11 再由 900 秒上调到 1800 秒：付费主力 glm-5.3-flash 实测画
     * 复杂图需要约 380 秒，而 0.6 的候选份额把首候选窗口压到 252 秒 ⇒ 每轮都在
     * 它还没写完时掐断（实测 2_order_flow：849 秒三轮全灭、只剩占位符）。
     * 现在给到 30 分钟，让主力在一次生成里能拿到**两次 850 秒级**的机会。
     * 注意这是**上限**不是固定等待——正常请求 30~400 秒即返回。
     */
    public static final long TOTAL_BUDGET_MS = 1_800_000L;

    /**
     * 总预算的运行时读数（2026-10-11）。
     *
     * <p>为什么需要它：{@code TOTAL_BUDGET_MS} 是 {@code static final} 字面量，
     * 别的类若写 {@code GenerationService.TOTAL_BUDGET_MS + 20_000L} 就是一个
     * **编译期常量表达式**，javac 会把它**内联**成字面量写进调用方的 class 文件，
     * 运行时不再回读本类。于是"只改本文件、调用方源码没动"时，增量编译不会
     * 重编译调用方 ⇒ 两边的值**静默失配**（曾经的真实事故形态：SSE 通道先于
     * 生成结束被掐断，前端只收到一条腰斩的流）。
     *
     * <p>方法调用不是编译期常量 ⇒ 不会被内联 ⇒ 调用方永远读到当前值。
     * 需要与本值恒等对齐的地方一律调本方法，不要直接引用常量。
     */
    public static long totalBudgetMs() {
        return TOTAL_BUDGET_MS;
    }

    /** 留给"发结果 + 关闭流"的收尾余量（毫秒），不参与 LLM 调用 */
    private static final long TAIL_RESERVE_MS = 30_000L;

    /** IR 格式版本号：写入每个下发的场景，便于将来向前兼容（ADR-2） */
    private static final String IR_SCHEMA_VERSION = "2.0";

    /**
     * 静态层正文的唯一真相源（提示词正文 + 契约表都从这里派生）。
     * 仅为读 {@code promptVersion()} 而注入 —— 版本号不再写死在本类。
     */
    private final PromptService promptService;

    /**
     * 提示词版本号（v2-43 / Q3-A）—— 每轮组装时由 {@link #promptService} 现读。
     *
     * <p><b>v2-43 收尾变更：原为硬编码常量，此处改为实时读取。</b>
     * 硬编码期间（09-28 至 10-10）md 连改十余版（v8.0.1 → v10.2.0），
     * 常量始终停在 v8.0.1 不动 —— 不报错、不告警，只是安静地记错。
     * 而它的唯一用途就是给 M14（评估回归）按版本对照出图质量，
     * 记错等于给回归门禁喂假基线。现在改为从 md 治理头派生，
     * "改了 md 忘了改 Java"这条断链结构上消失。
     *
     * <p>本值只做**溯源标注**，不参与任何逻辑判断 —— 送进模型的正文里
     * 不含版本号（元数据进正文会破坏 KV-cache 前缀）。
     */
    private String promptVersion() {
        return promptService.promptVersion();
    }

    /**
     * 单轮 LLM 调用的上限（秒）
     *
     * <p>这个值必须由**实测**决定，不能拍脑袋。2026-09-16 的实测数据：
     * <ul>
     *   <li>三节点小流程（约 5 个元素）→ 25 秒</li>
     *   <li>25 元素的系统架构图 → 140 秒</li>
     *   <li>5 层微服务电商架构图 → **超过 150 秒仍未返回**</li>
     * </ul>
     * 根本原因是推理模型要先产出数千 reasoning token，速度约 40 token/秒，
     * 输出规模一大，时间就线性上涨。旧值 150 秒正是"复杂图必失败"的原因
     * （用户实测：第 1 轮跑到 150 秒被掐断，画布全空）。
     *
     * <p>2026-10-07 由 300 秒上调到 420 秒，且**判死主依据已换成流式空闲
     * 判据**：该值现在只是"兜底总时限"，不会在模型还在稳定吐字时把它掐掉。
     *
     * <p>2026-10-11 由 420 秒上调到 900 秒：要真正让"模型没停就不中断"生效，
     * 单轮必须容得下一次完整的慢生成。配合 MAX_BUDGET_SHARE=0.95，
     * 首候选窗口 = min(900, 900×0.95=855) = 855 秒，远大于实测所需的 380 秒。
     */
    private static final int MAX_ROUND_SECONDS = 900;

    /** 低于这个秒数就不再开新一轮：开也跑不完，不如早点如实报错 */
    private static final int MIN_ROUND_SECONDS = 10;

    /**
     * 兜底合成场景里那个占位框的 id（2026-09-26）
     *
     * <p>固定值而非随机：随机 id 会让"每次降级都产生一份新模型"，
     * 排查时没法把日志里的场景对上。用户可以自由删掉它。
     */
    private static final String FALLBACK_ELEMENT_ID =
            "chartflow-fallback-note";

    /**
     * 失败兜底时，是否优先下发"本轮已产出的可解析场景"（2026-10-07）
     *
     * <p>仅在**新建模式**（没有原图可还）生效：宁可给一张画得不完美的图，
     * 也别退回占位框 —— 这就是"任务跑到一半断掉不丢进度"。
     * 编辑模式一律还原图：半成品覆盖用户原图比占位更糟。
     */
    private static final boolean DELIVER_LAST_PARSEABLE_ON_FAILURE = true;

    /**
     * 兜底说明里 issue 摘要的最大字符数（2026-09-26）
     *
     * <p>issues 一多，全量拼出来能有两三千字 —— 那条说明会随 SSE 一起
     * 推给前端进消息列表，撑爆它比少写点原因糟得多。只截摘要，
     * 结论（"已把原来的图还给你，请把图形拆小后重试"）始终完整。
     */
    private static final int SUMMARY_MAX_LEN = 200;

    /**
     * 前端可见的校验明细上限（2026-10-07）
     *
     * <p>比 {@link #SUMMARY_MAX_LEN} 宽一些：这条是发给"人"看的诊断信息
     * （前端消息列表 + 排查日志），不是兜底话术的一部分。上限存在的
     * 理由不变 —— 一轮 30+ 个 issue 全量拼出来两三千字，会把通道撑爆。
     */
    private static final int DIGEST_MAX_LEN = 320;

    /** 摘要里单个原因最多显示几个字（超了截断，否则一条长原因就吃满额度） */
    private static final int DIGEST_REASON_CHARS = 18;

    /** 摘要里最多列几类原因 */
    private static final int DIGEST_TOP_REASONS = 3;

    /**
     * 兜底说明里"图现在是什么状态"那半句（2026-09-26）
     *
     * <p>四种兜底场景（超时 / LLM 调用失败 / 返回空白 / 三轮修不动）
     * 统一用它收尾，措辞刻意**中性**：早先写的是「已把原来的图还给你」，
     * 在编辑模式下会被读成"我刚画的图被你退回来了"，把一次"没改动成功"
     * 误读成"出了错"。实际发生的是**图原样没动**，所以这么说。
     *
     * <p>新建模式下没有"原图"可还，只能给一条占位说明，两种要分开说，
     * 否则用户会对着一张占位框找"我原来的图呢"。
     *
     * @param editMode 本次是"改这张图"还是"画一张新图"
     * @return 描述图当前状态的一句话
     */
    private String fallbackOutcome(boolean editMode) {
        return editMode
                ? "本次未生成新内容，图保持原样"
                : "本次未生成新内容，给你一条占位说明";
    }

    private final LlmProvider llmProvider;
    private final GraphValidator graphValidator;
    private final SceneBinder sceneBinder;
    private final SceneDeduper sceneDeduper;
    /**
     * 宽容归一化（2026-10-07）：跑在校验前，把"差一点但意图明确"的字段
     * 做零损失修复（目前只收 label），免得为一个空 label 白烧一轮 LLM。
     */
    private final SceneSanitizer sceneSanitizer;
    /** 编辑模式的几何保真校验（v2-37）：防"保留 id 但整图重排坐标" */
    private final SceneDrift sceneDrift;
    /** 排版（v2-39 / v2-40 P1）：先分模块再落几何；小图与编辑模式不介入 */
    private final ScenePlanner scenePlanner;
    private final SessionStore sessionStore;
    /**
     * 上下文组装（v2-41）：把静态层（角色 / 契约 / 规则）与动态层
     * （当前场景 / 用户需求 / 校验 issues）拼成 system + user 两条消息。
     */
    private final ContextAssembler contextAssembler;

    /**
     * 几何体检（v10.0.0）：只测量、不改坐标。把"连线交叉 / 穿身 /
     * 标签被压"量出来变成 issue，借既有修正轮让 LLM 自己重摆。
     */
    private final GeometryAudit geometryAudit;

    /** 会话模型的序列化/反序列化（后端持 IR 唯一真相源，见 ADR-2） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 解析闸门专用的**严格**解析器（2026-10-10）
     *
     * <p>为什么要跟 {@link #objectMapper} 分开：默认的 ObjectMapper 走
     * {@code FAIL_ON_TRAILING_TOKENS=false} —— 读到一个合法值就收工，
     * 后面剩什么一概不管。而模型最常见的收尾习惯就是在 JSON 后面补一句
     * "希望对你有帮助"。于是出现口径分裂：**后端判它合法、前端判它非法**
     * （JS 的 JSON.parse 是严格口径，Python 的 json.loads 同理）。
     *
     * <p>后果正好落在最高危的那一类：前端解析一失败就渲染不出场景，
     * 用户看到的是**空白画布**，而日志这边一切"正常"。
     *
     * <p>所以闸门必须对齐**下游**的解析口径，而不是顺着 Jackson 的宽容。
     * 这里开 FAIL_ON_TRAILING_TOKENS，把"多一个字符"也算成读不通。
     */
    private static final ObjectMapper STRICT_JSON =
            new ObjectMapper().enable(
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public GenerationService(
            LlmProvider llmProvider,
            GraphValidator graphValidator,
            SceneBinder sceneBinder,
            SceneDeduper sceneDeduper,
            SceneSanitizer sceneSanitizer,
            SceneDrift sceneDrift,
            ScenePlanner scenePlanner,
            SessionStore sessionStore,
            ContextAssembler contextAssembler,
            GeometryAudit geometryAudit,
            PromptService promptService) {
        this.llmProvider = llmProvider;
        this.graphValidator = graphValidator;
        this.sceneBinder = sceneBinder;
        this.sceneDeduper = sceneDeduper;
        this.sceneSanitizer = sceneSanitizer;
        this.sceneDrift = sceneDrift;
        this.scenePlanner = scenePlanner;
        this.sessionStore = sessionStore;
        this.contextAssembler = contextAssembler;
        this.geometryAudit = geometryAudit;
        this.promptService = promptService;
    }

    /**
     * 主入口：接收用户文本，跑循环，通过 SSE 推送事件
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
                + TOTAL_BUDGET_MS;
        try {
            // 任务开始就估一次规模：路由靠它决定"第 1 轮要不要直接上强模型"
            //（大活首轮用便宜模型等于白烧一轮，2026-10-07 实测）
            int scale = RequestScale.estimate(userText);
            LlmEffort.setScale(scale);
            if (scale >= RequestScale.LARGE) {
                LOG.info("需求规模判定为大活（scale={}），"
                        + "本轮起直接放开强模型", scale);
                sendEvent(emitter, "thinking",
                        "识别为大图任务，直接启用强模型");
            }
            generateInternal(
                    sessionId, userText,
                    emitter, deadline);
        } catch (RuntimeException e) {
            // 生成本身跑在独立线程里：异常一旦不接住就直接打穿线程，
            // 前端什么反馈都收不到（曾因 emitter 已被容器关闭而裸崩）
            LOG.error("生成线程异常终止", e);
            sendError(emitter,
                    "生成异常终止: " + e.getMessage());
        } finally {
            // ThreadLocal 必须清：线程池复用时会串味（上次的规模档
            // 会跟到下一次请求上）
            LlmEffort.clear();
        }
    }

    /**
     * 生成主流程
     *
     * @param userText 用户输入
     * @param emitter  SSE 发射器
     * @param deadline 本次生成的总截止时刻
     *                 （毫秒时间戳）
     */
    private void generateInternal(
            String sessionId,
            String userText,
            SseEmitter emitter,
            long deadline) {

        // 会话里已有模型 = 用户在"改自己那张图"；没有 = 新建。
        // 这个判断决定 prompt 里要不要塞入当前场景（v2-33）：
        // 不塞的后果是"删掉某个节点"被 LLM 理解成"画一张新图"，
        // 前端整体替换画布 → 之前画的全没了。
        String previousJson =
                currentModelJson(sessionId);
        boolean editMode = previousJson != null;
        // Bug A (2026-09-17): when the user has only hand-drawn shapes and no
        // AI-generated elements yet, the model's `elements` is empty while the
        // hand-drawings live in the `_userElements` bypass. Entering editMode as-is
        // shows the LLM an empty diagram, so it draws a brand-new one (user saw a
        // hand-drawing on the right and a fresh AI drawing on the left). Promote the
        // hand-drawings into the editable scene so the LLM modifies them in place,
        // and clear the stale bypass on save (the hand-drawings are now absorbed).
        String effectiveSceneJson = previousJson;
        boolean takeOver = false;
        List<Map<String, Object>> userElements =
                currentUserElements(sessionId);
        if (previousJson != null) {
            String seeded = buildSeedScene(previousJson);
            if (seeded != null) {
                takeOver = true;
                effectiveSceneJson = seeded;
                userElements = List.of();
            }
        }
        // 轮次语义（2026-09-17 修正）：通过即发、失败才继续。
        //
        // 旧实现是"每轮通过都先存档、继续跑满 3 轮、最后才发"，而第 2/3 轮
        // 用的 prompt 与第 1 轮**完全相同**（通过时不会构建修正 prompt），
        // 等于靠采样随机性重摇一次。两个后果都是用户实打实等出来的：
        // (1) 简单图也要跑满 3 次 LLM 调用 —— 等待时间直接 ×3；
        // (2) 第 2/3 轮一旦撞上单轮上限，那份已经合格的存档会被彻底丢掉
        //     （用户实测的"LLM 调用超时：整次调用超过 296 秒"就走这条路）。
        //
        // 现在：校验 0 issue 立即落库并下发；只有校验不通过，才带着 issues
        // 进入下一轮修正。于是"存档"不再需要 —— 合格结果产生的当下就发出去了。
        List<Message> messages;
        try {
            messages = contextAssembler.assemble(
                    AssembleInput.of(
                            sessionId, userText,
                            effectiveSceneJson,
                            editMode, 1, null,
                            promptVersion()));
        } catch (IOException e) {
            sendError(emitter,
                    "加载 schema 失败: "
                            + e.getMessage());
            return;
        }

        // 记录"本轮已产出的可解析场景"：后面轮次若断掉，兜底时优先用它
        String lastParseable = null;

        for (int round = 1;
                round <= MAX_ROUNDS; round++) {
            int roundTimeout = planRound(deadline);
            if (roundTimeout < MIN_ROUND_SECONDS) {
                long usedSec = (TOTAL_BUDGET_MS
                        - (deadline
                                - System.currentTimeMillis()))
                        / 1000;
                // 预算不足以再开一轮 —— 但**超时是时间花完了，后端自己
                // 没毛病**，它跟"组装修正失败 / 加载 schema 失败"不是一类：
                // 后者是后端真的坏了，下发假图反而是把事故盖住。前者下发
                // 纯 error 的后果是用户整句输入毫无回响（2026-09-16 实测
                // "整次调用超过 296 秒"那次就是）。
                //
                // 2026-09-26 起：改成下发**合法场景**（编辑模式优先把原图
                // 还回去，新建模式给一条占位说明），用户至少看得到东西。
                String note = "生成超时：已耗时 " + usedSec
                        + " 秒，剩余时间不足以完成第 " + round
                        + " 轮；" + fallbackOutcome(editMode)
                        + "，请把图形拆小后重试";
                if (deliverFallback(emitter, note,
                            fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                sendError(emitter, "兜底场景合成失败");
                return;
            }

            LOG.info("生成轮次 {}/{}，本轮上限 {} 秒",
                    round, MAX_ROUNDS, roundTimeout);
            sendEvent(emitter, "thinking",
                    "第 " + round + " 轮生成中...");

            // 告诉路由"当前第几轮"：round 1 只用便宜/不限量候选，
            // round >= 2（首轮没过校验 = 难任务）才放开限量强模型
            LlmEffort.setRound(round);

            String raw;
            try {
                raw = llmProvider.chat(
                        messages, roundTimeout);
            } catch (Exception e) {
                // 2026-10-07：先看"还有没有下一轮"再决定兜底。
                // 以前这里无条件兜底 —— 于是"首轮候选全挂"等价于"整次失败"，
                // 而 minRound=2 的强模型本来就只允许第 2 轮起用，等于永远轮
                // 不到它（实测 15 模块大图就栽在这：round 1 四个候选全超时，
                // 一次都没试到 DeepSeek）。轮次本身就是"换一批更贵的候选"
                // 的开关，所以只要预算还够就必须往下走一轮。
                long leftMs = deadline - System.currentTimeMillis()
                        - TAIL_RESERVE_MS;
                if (round < MAX_ROUNDS
                        && leftMs > MIN_ROUND_SECONDS * 1000L) {
                    LOG.warn("第 {} 轮 LLM 调用失败（{}），"
                            + "剩余预算 {}s 仍够，转入第 {} 轮重试",
                            round, e.getMessage(), leftMs / 1000,
                            round + 1);
                    sendEvent(emitter, "validation",
                            "第 " + round + " 轮模型均不可用，"
                                    + "转下一轮重试");
                    continue;
                }
                // 2026-09-26：LLM 是**外部依赖**，它超时/报错是在我们正常
                // 工作的过程中发生的，不是后端故障。此时给用户看一张
                // 「能画出来的图」（编辑模式就是他原有的那张）远好过看一句
                // error —— 空画布是这个产品能出的最高危事故。
                String note = "LLM 调用失败: " + e.getMessage()
                        + "；" + fallbackOutcome(editMode)
                        + "，可稍后重试";
                if (deliverFallback(emitter, note,
                            fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                sendError(emitter, "兜底场景合成失败");
                return;
            }
            if (raw == null || raw.isBlank()) {
                // 同上：返回空白同样是"模型没干活"，不是后端坏了
                String note = "LLM 返回内容为空；"
                        + fallbackOutcome(editMode)
                        + "，可稍后重试";
                if (deliverFallback(emitter, note,
                            fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                sendError(emitter, "兜底场景合成失败");
                return;
            }

            // 2026-09-26：这三步都会**解析整份 JSON**，模型一旦返回
            // 被截断 / 混进 Frontmatter 的非法 JSON，就会在这里抛。
            // 之前没人接，异常直接冒到 generate() 变成一句 error，
            // 用户得到的是「报错」而不是「图」——违反 v5.0.0 输出策略。
            // 现在与「LLM 返回内容为空」同路径兜底。
            String json;
            try {
                // 吸附归一化：把"两端都落在图形上的两点箭头"升级成绑定式，
                // 让连线在用户拖动图形时能跟着走（v2-33）
                json = sceneBinder.bind(
                        extractJson(raw));
                // 去重：丢掉与"用户手绘连线"撞端点的线，以及 LLM 自己
                // 画的重复线（v2-34）。必须排在 bind 之后 —— 吸附后的线
                // 是绑定式，比 id 对比比坐标可靠
                json = sceneDeduper.dedup(
                        json, userElements);
                // 宽容归一化（2026-10-07）：零损失修掉"差一点"的字段
                // （空 label / 直写字符串的 label），免得为一个空 label
                // 白烧一轮 LLM。必须排在 validate 之前 —— 校验器判的是
                // 它拿到的 JSON，而这里改的就是最终要下发的那一份。
                json = sceneSanitizer.sanitize(json);
            } catch (RuntimeException parseFailure) {
                // 模型干活了，但交付的东西我们读不懂。跟"它没干活"一样
                // 属于外部依赖的锅，不是后端故障 —— 给它一张能看的图。
                LOG.warn("第 {} 轮返回内容无法解析，转兜底场景: {}",
                        round, parseFailure.toString());
                String note = "模型返回的内容无法解析；"
                        + fallbackOutcome(editMode)
                        + "，请把需求说得更具体后重试";
                if (deliverFallback(emitter, note,
                            fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                sendError(emitter, "兜底场景合成失败");
                return;
            }
            // 排版（v2-39 / v2-41 原生坐标模式）：
            // v2-41 起不再调后端布局引擎做「尺寸抹平 + 竖排重排」——
            // 实测后端重排会把图收成一根窄条，远不如 LLM 原生布局好看，
            // 所以「摆哪、多大」直接采用 LLM 给的种子坐标。
            // 这里只保留 ScenePlanner 的「画框认领」（填 frame.children），
            // 使画框在渲染端拖动 / 删除时带上成员；不改动任何几何。
            // bind / dedup 仍排在前面（吸附要先把两点箭头升级成绑定式，
            // 去重要先丢掉与用户手绘撞端点的线）。
            json = scenePlanner.claimFramesOnly(json);

            // 解析闸门（2026-10-10）：上面这四环对坏 JSON 都是"原样透传"
            // （宽容是它们的设计纪律 —— 能修就修，不把关），所以"链路跑完"
            // **不代表读得通**。原先这里直接 `lastParseable = json`，注释写着
            // "已能解析出合法结构"，可依据只有"链路跑完了" —— 这个变量名
            // 一直在说谎。坏内容被记成"可解析场景"以后，某一轮超时走兜底时
            // 会优先把它下发出去，前端解析失败 = 空白画布。
            // 现在先真读一次：读不通就当 issue 交给修正轮（对模型来说
            // "整份重发一个合法 JSON"几乎必成），并且不记进 lastParseable。
            ValidationIssue malformed = parseGate(json);
            if (malformed == null) {
                // 已能解析出合法结构：记下来，兜底时优先下发它（不丢进度）
                lastParseable = json;
            }

            sendEvent(emitter, "validation",
                    "第 " + round + " 轮校验中...");
            List<ValidationIssue> issues = new ArrayList<>();
            if (malformed != null) {
                // 读不通就无所谓"结构"了：结构校验 / 几何体检 / 改图保真
                // 对着一段读不出来的文本都说不出更有用的话，全跑一遍只会
                // 让修正 prompt 里堆满互相矛盾的提示。只报这一条，让模型
                // 把注意力全放在"重发一份合法 JSON"上。
                issues.add(malformed);
            } else {
                issues.addAll(
                        graphValidator
                                .validate(json));
                // 几何体检（v10.0.0）：结构合法不等于画得能看 —— 连线交叉 /
                // 穿身 / 标签被压只有量出来才知道。体检器只报告、绝不改坐标
                // （怎么挪由 LLM 在修正轮里自己决定），所以这里只是追加 issue。
                issues.addAll(geometryAudit.audit(json));
                if (editMode && !takeOver && round == 1) {
                    // 只在第一轮提醒"别把用户的图换成新图"：它是一条提醒、
                    // 不是门禁 —— 第二轮 LLM 仍坚持重画就放行，
                    // 用户本来就可能明确要求"重新画一张"
                    ValidationIssue rewrite =
                            detectRewrite(previousJson, json);
                    if (rewrite != null) {
                        issues.add(rewrite);
                    }
                    // v2-37：id 全换看 detectRewrite，**保留 id 但整图重排**
                    // 归这里。两者合起来才覆盖 PromptService 那句
                    // "没有要求改动的元素必须原样保留 id / 几何"。
                    ValidationIssue drift =
                            sceneDrift.check(previousJson, json);
                    if (drift != null) {
                        issues.add(drift);
                    }
                }
            }

            // 空场景拦截（2026-09-26）：模型交出 {"elements":[]} 时，
            // 即使 GraphValidator 一笔都没挑（它只看得到"没报"），
            // 这份结果也绝不能落库下发 —— 那正是"画布全白、用户以为
            // 成功生成了"的同款事故。拦下来 = 进修正轮，并把"必须给
            // 出可见元素"这条明确写进修正 prompt。
            //
            // 注意判的是**结果**，不是 issue 数量：校验器自己也可能会
            // 漏报空数组，所以这里独立判一次，而不是依赖上面那条 issue。
            boolean emptyScene = isEmptyScene(json);
            if (emptyScene && issues.isEmpty()) {
                issues.add(new ValidationIssue(
                        "elements",
                        "场景为空：一个可见元素都没有"
                                + "（不要返回 {\"elements\":[]}）",
                        "至少输出一个可见元素"));
            }

            if (issues.isEmpty()) {
                // 校验通过即发（2026-09-17）：不再用同一个 prompt
                // 空跑后两轮。想在图质量上继续投入只能靠"修正轮"，
                // 而修正轮的前提是**有 issues** —— 没 issue 就没有
                // 可修的东西，再摇一次只是重掷骰子。
                LOG.info("第 {} 轮校验通过，直接落库并下发",
                        round);
                json = stampSchemaVersion(json);
                saveModel(sessionId, json, !takeOver);
                sendEvent(emitter, "result", json);
                emitter.complete();
                return;
            }

            LOG.warn("第 {} 轮校验失败，共 {} 个 issue",
                    round, issues.size());
            // 明细必须跟着一起发（2026-10-07）：只写"共 N 个问题"时，
            // 前端和排查日志都只知道数量、不知道是什么 —— 大图第一次
            // 出来 35 个 issue，我们连主因是什么都得靠猜。
            sendEvent(emitter, "validation",
                    "第 " + round + " 轮校验失败: "
                            + issueDigest(issues));

            if (round == MAX_ROUNDS) {
                // 三轮都修不动 = 模型给不出可用结果，分两种：
                // (a) 场景**为空**（鱼骨图那组实测），(b) 图非空但那几条
                // 校验三轮都改不掉（f05 那批 frame/text + group、f30 那批
                // 箭头绑 frame）。旧实现两种都下发纯 error，用户整句输入
                // 毫无回响 —— 与 f12 白卷是同一类事故，只是触发条件换成
                // "图非空"（2026-09-26）。两种一律走兜底下发，区别只在
                // 给回的是原图还是占位框，逻辑只有这一套。
                String summary = issueDigest(issues);
                // 摘要必须截断：一轮 issue 一多就是两三千字，整条 SSE
                // 消息会把通道和前端消息列表一起撑爆
                if (summary.length() > SUMMARY_MAX_LEN) {
                    summary = summary.substring(
                            0, SUMMARY_MAX_LEN)
                            + "…";
                }
                String note = "已重试 " + MAX_ROUNDS
                        + " 轮仍未通过校验（" + summary
                        + "）：" + fallbackOutcome(editMode)
                        + "，请把图形拆小后重试";
                if (deliverFallback(emitter, note,
                            fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                // 兜底自身失败（objectMapper 都挂了）才退回 error
                sendError(emitter,
                        "已重试 " + MAX_ROUNDS
                                + " 轮仍不通过: "
                                + summary);
                return;
            }

            try {
                // 传 round + 1：AssembleInput.round 的语义是"这组消息**将要用于**
                // 的轮次"，而这里正在为下一轮备料；组装器以 round > 1 判定是否
                // 追加修正块，传 round 会让首轮失败后的修正消息漏掉 issues。
                messages = contextAssembler.assemble(
                        AssembleInput.of(
                                sessionId, userText,
                                previousJson,
                                editMode, round + 1, issues,
                                promptVersion()));
            } catch (IOException e) {
                sendError(emitter,
                        "组装修正消息失败: "
                                + e.getMessage());
                return;
            }
        }
    }

    /**
     * 计算本轮可用的 LLM 调用上限（秒）
     *
     * 取"剩余预算 − 收尾余量"与"单轮上限"的较小值，
     * 保证每轮跑完都还留着发结果与关流的时间，
     * 不会把整个 SSE 通道顶穿。
     *
     * @param deadline 总截止时刻（毫秒时间戳）
     * @return 本轮硬上限，单位秒
     */
    private int planRound(long deadline) {
        long remaining = deadline
                - System.currentTimeMillis()
                - TAIL_RESERVE_MS;
        return (int) Math.min(
                MAX_ROUND_SECONDS, remaining / 1000);
    }

    /**
     * 把一轮校验问题压成一行"能看懂主因"的摘要（2026-10-07）。
     *
     * <p>为什么归类而不是原样罗列：大图一轮 30+ 个 issue 里，真正的原因
     * 往往只有两三类 —— 比如"箭头既给 start/end 又给 points"这一条就能
     * 同时命中十几个元素。原样罗列时前 200 字全被同一个原因的不同元素
     * 占满，看的人反而看不出主因是什么。
     *
     * <p>输出形如：
     * {@code 共 35 个问题，前三类: 箭头路径… x17 / group 只属… x9；例: elements[12] …}
     *
     * @param issues 本轮校验问题
     * @return 单行摘要（长度受 {@link #DIGEST_MAX_LEN} 约束）
     */
    String issueDigest(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "0 个问题";
        }
        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (ValidationIssue issue : issues) {
            byReason.merge(
                    shorten(issue.reason(), DIGEST_REASON_CHARS),
                    1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> top = byReason.entrySet()
                .stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(DIGEST_TOP_REASONS)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(issues.size())
                .append(" 个问题，前三类: ");
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(top.get(i).getKey())
                    .append(" x").append(top.get(i).getValue());
        }
        ValidationIssue first = issues.get(0);
        sb.append("；例: ").append(first.field())
                .append(' ').append(first.reason());
        return sb.length() > DIGEST_MAX_LEN
                ? sb.substring(0, DIGEST_MAX_LEN) + "…"
                : sb.toString();
    }

    /** 只留前 n 个字符；null 当空串 */
    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 从 LLM 原始响应中提取 JSON
     * （LLM 可能用 ```json ... ``` 围起来）
     */
    String extractJson(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline =
                    trimmed.indexOf('\n');
            int lastFence =
                    trimmed.lastIndexOf("```");
            if (firstNewline > 0
                    && lastFence > firstNewline) {
                trimmed = trimmed.substring(
                        firstNewline + 1,
                        lastFence);
            }
        }
        return trimmed.strip();
    }

    /**
     * 解析闸门：确认**即将下发的那一份**真的能被标准解析器读出来（2026-10-10）
     *
     * <p><b>为什么"链路走完了"不等于"读得通"：</b>bind / dedup / sanitize /
     * claimFramesOnly 这四环的设计纪律都是**宽容**的 —— 遇到坏 JSON 一律
     * 原样透传（见 SceneBinder / SceneSanitizer 的 catch 分支），因为它们的
     * 职责是"能修就修"，不是"把关"。所以一段谁都不认识的文本可以安然穿过
     * 整条链，一路走到下发。
     *
     * <p>原代码在这里直接 `lastParseable = json`，注释写的是"已能解析出合法
     * 结构" —— 这个变量名一直在说谎。它被赋值的唯一依据是"链路跑完了"。
     * 于是坏内容被记成"可解析场景"，等到某一轮超时 / LLM 掉线走兜底时，
     * 兜底优先下发的就是它（见 {@link #DELIVER_LAST_PARSEABLE_ON_FAILURE}），
     * 前端解析失败 = 空白画布。
     *
     * <p><b>闸门怎么用：</b>失败不直接丢弃，而是转成一条 issue 交给修正轮 ——
     * 这类问题的修法对模型来说极其简单（"重新输出一遍合法 JSON"），给一轮
     * 几乎必成；同时**不再记进 lastParseable**，把兜底那条通路也堵上。
     *
     * <p>只做"读得通"这一个判断，不重复 GraphValidator 的结构校验：
     * 结构对不对由校验器说，这里只回答"下游解析器能不能读"。
     *
     * @param json 链路处理完、准备交给校验器的场景 JSON
     * @return 读不通时返回一条 issue；读得通返回 null
     */
    ValidationIssue parseGate(String json) {
        if (json == null || json.isBlank()) {
            LOG.warn("解析闸门拦下空内容");
            return new ValidationIssue(
                    "$",
                    "交付内容为空",
                    "必须输出一个包含 elements 的 JSON 对象");
        }
        try {
            JsonNode root = STRICT_JSON.readTree(json);
            if (root == null || !root.isObject()) {
                LOG.warn("解析闸门拦下非对象顶层: {}",
                        root == null ? "null"
                                : root.getNodeType());
                return new ValidationIssue(
                        "$",
                        "顶层不是 JSON 对象"
                                + "（实际是 "
                                + (root == null ? "空" : root.getNodeType())
                                + "）",
                        "必须输出一个对象：{\"elements\":[…]}");
            }
            return null;
        } catch (Exception e) {
            // 最常见的就是"JSON 后面还跟着一句话"：Jackson 默认容忍尾随
            // 内容，前端不容忍。这里如实报出来，让模型知道要整份重发。
            LOG.warn("解析闸门拦下不可解析的交付内容: {}",
                    e.getMessage());
            return new ValidationIssue(
                    "$",
                    "交付内容不是合法 JSON: " + e.getMessage(),
                    "只输出一个 JSON 对象：前后不要任何多余字符、"
                            + "不要写注释、不要用 ``` 包裹、"
                            + "不要加「以上」之类的收尾话");
        }
    }

    private void sendEvent(
            SseEmitter emitter,
            String type,
            String data) {
        try {
            emitter.send(
                    ChatEvent.of(type, data));
        } catch (IOException e) {
            LOG.warn("SSE 发送失败: {}",
                    e.getMessage());
        } catch (IllegalStateException e) {
            // 通道已结束时（连接超时被容器掐断、或客户端已断开）
            // emitter.send 抛的是 IllegalStateException 而非 IOException。
            // 这不是"发送失败"而是"已经没人收了"——继续往外抛会让
            // 整个生成线程裸崩，连收尾日志都留不下来（2026-09-16 实测）。
            LOG.warn("SSE 通道已结束，跳过本次推送: {}",
                    e.getMessage());
        }
    }

    private void sendError(
            SseEmitter emitter,
            String message) {
        sendEvent(emitter, "error", message);
        try {
            // 这里必须用 complete()，不能用 completeWithError()。
            //
            // 错误内容已经作为 error 事件推给客户端了，通道层再"报错"只会让
            // 容器去走 ERROR dispatch —— 而响应头此时已经是
            // Content-Type: text/event-stream，容器找不到能把错误体写成 SSE
            // 的消息转换器，实测抛：
            //   HttpMessageNotWritableException: No converter for
            //   [class java.util.LinkedHashMap] with preset Content-Type
            //   'text/event-stream'
            // 后果是响应既不正常收尾也不断开：前端 reader.read() 永远等不到
            // done，isLoading 永远为 true —— 输入框被禁用，整个界面卡死，
            // 只能刷新页面（2026-09-16 实测卡死 280 秒以上）。
            emitter.complete();
        } catch (IllegalStateException e) {
            LOG.warn("SSE 通道已结束，无法收尾: {}",
                    e.getMessage());
        }
    }

    /**
     * 「改图」保真检查：识别"把用户的图换成了另一张新图"。
     *
     * <p><b>为什么需要它：</b>在补上会话语义之前，后端是**无状态生成** ——
     * prompt 里只有用户这一句，LLM 看不到用户眼下这张图。
     * 用户说"删掉某个节点"，LLM 只能凭空造，于是前端收到 1 个元素的场景、
     * 整体替换画布，**之前画的全没了**（2026-09-16 实测：23 → 2 个元素）。
     *
     * <p>把当前场景喂进 prompt（见 {@link PromptService}）能治本，但 LLM
     * 仍有可能忽略上下文、用一批全新 id 重画。那种结果"看起来成功了"，
     * 实则把用户的图换掉了，必须能被拦下来。
     *
     * <p><b>判据刻意保守：只在"旧元素一个都没留下"时报警。</b>
     * 用户合法地删掉一半元素不该被拦（那会让检查变成阻碍），
     * 而"全部 id 都换了"几乎只可能是"忽略了当前场景"。
     *
     * @param previousJson 会话里修改前的模型（JSON 文本）
     * @param json         本轮 LLM 输出的场景
     * @return 需要 LLM 修正的问题；判定通过返回 null
     */
    private ValidationIssue detectRewrite(
            String previousJson, String json) {
        try {
            Set<String> before = idsOf(
                    objectMapper.readTree(previousJson));
            // 单元素场景不做判断：只画了一个框时说"换个颜色"，
            // id 变化属于正常，拦它只会制造无谓的修正轮
            if (before.size() < 2) {
                return null;
            }

            JsonNode after = objectMapper
                    .readTree(json)
                    .get("elements");
            if (after == null
                    || !after.isArray()
                    || after.isEmpty()) {
                return new ValidationIssue(
                        "elements",
                        "修改后的场景一个元素都没有"
                                + "（原场景有 "
                                + before.size() + " 个）",
                        "必须在原场景基础上修改："
                                + "只去掉用户要求删除的元素，"
                                + "其余元素原样保留");
            }

            Set<String> afterIds = idsOf(
                    objectMapper.readTree(json));
            Set<String> kept = new HashSet<>(afterIds);
            kept.retainAll(before);
            // 2026-09-17 tightened: old version only checked "any id
            // overlaps". LLM regenerates a new diagram reusing a few
            // ids and passes -> user sees two diagrams side by side.
            // Now require >= 30% id overlap for old diagrams >= 3 elements.
            if (before.size() >= 3) {
                double overlapRatio = (double) kept.size()
                        / before.size();
                if (overlapRatio < 0.3) {
                    return new ValidationIssue(
                            "elements[].id",
                            "original " + before.size()
                                    + " elements, only "
                                    + kept.size() + " ids kept"
                                    + " (overlap "
                                    + String.format("%.0f",
                                            overlapRatio * 100)
                                    + "%) - looks like a new diagram",
                            "keep unmodified elements id/geometry/style,"
                            + " only change what user asked;"
                            + " only discard all when user explicitly"
                            + " asks to redraw");
                }
                return null;
            }
            // old diagram < 3 elements: id change may be normal
            // id 全换 ≠ 一定出错：用户可能就是要"另画一张"（见 PromptService
            // 的意图判断）。再要求新场景规模**明显缩水**才报警 ——
            // 实测那次事故是 23 个元素被换成 2 个（8%），而正常换主题时
            // 新旧图规模相当。少了这一条，"另画一张"的正常路径会白跑一轮
            // 修正（一轮就是 30~100 秒）。
            if (afterIds.size() * 2
                    >= before.size()) {
                return null;
            }
            return new ValidationIssue(
                    "elements[].id",
                    "原场景 " + before.size()
                            + " 个元素的 id 全部被换掉了，"
                            + "看起来是重新画了一张图",
                    "保留未涉及元素的 id / 几何 / 样式，"
                            + "只改用户要求改的部分；"
                            + "只有用户明确要求重画时才可全部丢弃");
        } catch (Exception e) {
            // JSON 坏掉时 GraphValidator 会给出更准确的 issue，
            // 这里不重复报警，更不能因自己解析失败而阻断生成
            LOG.warn("改图保真检查跳过（解析失败）: {}",
                    e.getMessage());
        }
        return null;
    }

    /**
     * 读会话里"用户手绘元素"的轻量描述（v2-34）
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

    /** 取一份场景里所有元素的 id */
    /**
     * 安全地读一份场景 JSON 里的 id 全集
     *
     * <p>布局靠它区分"哪些图形是本次新增的"（v2-36 决策 3）。
     * 解析失败一律返回空集 —— 空集等于"全量重排"，这比抛异常好：
     * 布局是锦上添花，不能因为读不到旧模型就阻断整次生成。
     *
     * @param json 场景 JSON 文本；可为 null
     * @return id 集合；任何异常都返回空集
     */
    private Set<String> idsOfJson(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return idsOf(objectMapper.readTree(json));
        } catch (JsonProcessingException e) {
            LOG.warn("读取既有 id 失败，布局按新建处理: {}",
                    e.getMessage());
            return Set.of();
        }
    }

    private Set<String> idsOf(JsonNode root) {
        Set<String> result = new HashSet<>();
        JsonNode elements = root.get("elements");
        if (elements == null
                || !elements.isArray()) {
            return result;
        }
        for (JsonNode elem : elements) {
            JsonNode id = elem.get("id");
            if (id != null && id.isTextual()) {
                result.add(id.asText());
            }
        }
        return result;
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
     * <p>Stores exactly the copy the frontend received (already binding-normalized),
     * so the next edit round sees the same scene the user is looking at.
     *
     * @param preserveUserElements when false (Bug A take-over), the stale
     *        {@code _userElements} bypass is dropped so the hand-drawings the
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

    /**
     * Bug A companion (v2-35): turn a "hand-drawn only, no AI elements" session
     * into an editable scene.
     *
     * <p>Returns a seed scene (hand-drawings promoted into {@code elements},
     * {@code _userElements} bypass removed) ONLY when the model's
     * {@code elements} is empty and {@code _userElements} is non-empty.
     * Otherwise returns null (normal edit / new-chart path).
     *
     * <p>Only contract types are promoted (rectangle / ellipse / diamond /
     * arrow / line / text / frame); freedraw strokes cannot participate in
     * auto-layout and are skipped.
     */
    private String buildSeedScene(String modelJson) {
        try {
            JsonNode root = objectMapper.readTree(modelJson);
            JsonNode elements = root.get("elements");
            boolean elementsEmpty = elements == null
                    || !elements.isArray()
                    || elements.isEmpty();
            JsonNode userEls = root.get(
                    SessionStore.USER_ELEMENTS_KEY);
            if (!elementsEmpty
                    || userEls == null
                    || !userEls.isArray()
                    || userEls.isEmpty()) {
                return null;
            }

            List<Object> seed = new ArrayList<>();
            for (JsonNode ue : userEls) {
                String type = ue.path("type").asText(null);
                if (type == null) continue;
                if (!Set.of("rectangle", "ellipse", "diamond",
                        "arrow", "line", "text", "frame")
                        .contains(type)) {
                    continue;
                }
                ObjectNode el =
                        objectMapper.createObjectNode();
                el.put("id", ue.path("id").asText(
                        "ue" + seed.size()));
                el.put("type", type);
                putIfFinite(el, ue, "x");
                putIfFinite(el, ue, "y");
                if (!type.equals("arrow")
                        && !type.equals("line")
                        && !type.equals("text")) {
                    putIfFinite(el, ue, "width");
                    putIfFinite(el, ue, "height");
                }
                if (ue.has("text")
                        && !ue.get("text").asText().isEmpty()) {
                    if (type.equals("text")) {
                        el.put("text",
                                ue.get("text").asText());
                    } else {
                        el.putObject("label")
                                .put("text",
                                        ue.get("text").asText());
                    }
                }
                if (type.equals("arrow")
                        || type.equals("line")) {
                    String s = ue.path("startId").asText(null);
                    String e = ue.path("endId").asText(null);
                    if (s != null && e != null) {
                        el.putObject("start")
                                .put("id", s);
                        el.putObject("end")
                                .put("id", e);
                    } else if (ue.has("points")) {
                        el.set("points", ue.get("points"));
                    }
                }
                seed.add(el);
            }
            if (seed.isEmpty()) return null;

            ObjectNode out =
                    objectMapper.createObjectNode();
            out.set("elements",
                    objectMapper.valueToTree(seed));
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            LOG.warn("take-over seed scene build failed, "
                    + "fall back to normal edit: {}",
                    e.getMessage());
            return null;
        }
    }

    /** Write a numeric field only when it is finite (never NaN/Infinity). */
    private void putIfFinite(
            ObjectNode el, JsonNode src, String key) {
        JsonNode v = src.get(key);
        if (v != null && v.isNumber()) {
            el.put(key, v.doubleValue());
        }
    }

    /**
     * 给场景盖 IR 版本号（幂等：已有则不覆盖）。
     *
     * <p>后端是 IR 唯一真相源（ADR-2），每个下发的场景都带版本号，
     * 将来 schema 演进时旧实例可据此识别、决定能否直接兼容。
     * 解析失败一律原样返回 —— 版本号只是保险，绝不能因它阻断生成。
     */
    private String stampSchemaVersion(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isObject() && !root.has("schemaVersion")) {
                ((ObjectNode) root).put(
                        "schemaVersion", IR_SCHEMA_VERSION);
                return root.toString();
            }
            return json;
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 判一份场景是不是"空场景"（一个元素都没有）
     *
     * <p>用于兜住 GraphValidator 漏报空数组的情形（2026-09-26 实测：
     * 鱼骨图那组返回 elements 为空数组，校验器一笔没挑就被落库下发，
     * 前端收到 0 元素当"成功生成"）。
     *
     * <p><b>解析失败一律不算空</b> —— 坏 JSON 由 GraphValidator 去报更
     * 准确的 issue；这里闷声判成"空场景"反而会把坏 JSON 一路放行到落库。
     *
     * @param json 场景 JSON 文本；可为 null
     * @return 元素数组缺失 / 为空时返回 true
     */
    private boolean isEmptyScene(String json) {
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode elements = root.get("elements");
            return elements == null
                    || !elements.isArray()
                    || elements.isEmpty();
        } catch (Exception e) {
            LOG.warn("空场景判定跳过（解析失败）: {}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * 构造兜底场景：**至少一个能画出来的元素**（2026-09-26）
     *
     * <p>三条收集路径（超时 / LLM 调用失败 / 返回空白）与"三轮都交空
     * 场景"都走这里。原则是**宁可给一张不完美的图，也不能给空画布** ——
     * 空白画布是这个产品能出的最高危事故，用户既看不到东西也无从判断
     * 发生了什么。
     *
     * <p>与之相对，**真实后端故障**（加载 schema 失败、组装修正消息失败）
     * 不走这里：那种情况下后端自己出了问题，给一张假图只是把事故盖住，
     * 必须保持 error 语义。
     *
     * <p><b>两级降级，顺序不可换：</b>
     * <ol>
     *   <li>编辑模式下先把**原图**还回去（用户眼下那张就是合法场景，
     *       只是这次没改成功）；只有"手绘提升"分支的种子场景比原模型
     *       更贴近用户所见，那种情况下它优先。</li>
     *   <li>原图不存在 / 它自己也是空的（会话本来就没元素），才合成
     *       一条占位文字。</li>
     * </ol>
     *
     * <p>注意候选必须**非空**才收：原模型里 {@code elements} 为空、
     * 内容还堆在 {@code _userElements} 旁路，正是要修的那个毛病 ——
     * 收了等于换个姿势把空画布发出去。
     *
     * @param previousJson       会话里修改前的模型；可为 null
     * @param effectiveSceneJson 真正喂给模型的当前场景；可为 null
     * @param takeOver           是否走了"用户手绘提升"分支
     * @param hint               新建模式下合成占位文字的内容
     * @return 至少一个元素的合法场景 JSON；合成彻底失败时返回 null
     *         （调用方据此退回 error 语义）
     */
    private String fallbackScene(
            String previousJson,
            String effectiveSceneJson,
            boolean takeOver, String bestSoFar, String hint) {
        // 新建模式：本轮已产出过可解析场景 → 优先下发它（不丢进度）
        if (DELIVER_LAST_PARSEABLE_ON_FAILURE
                && !takeOver
                && previousJson == null
                && bestSoFar != null
                && !bestSoFar.isBlank()
                && !isEmptyScene(bestSoFar)) {
            return stampSchemaVersion(bestSoFar);
        }
        String candidate = takeOver
                ? effectiveSceneJson : previousJson;
        if (candidate != null
                && !candidate.isBlank()
                && !isEmptyScene(candidate)) {
            // 盖版本号：下发到前端的每张图都带 IR 版本（ADR-2）。
            // 解析不了就原样返回，版本号是保险，不能因此阻断。
            return stampSchemaVersion(candidate);
        }
        return syntheticScene(hint);
    }

    /**
     * 合成一个只含单个占位框的合法场景
     *
     * <p>刻意用**带 label 的矩形**而不是裸 <code>text</code> 元素：契约里
     * text 元素不允许带 width / height（{@code additionalProperties: false}），
     * 而裸 text 走渲染层会被补成 0×0。带 label 的矩形两头都满足 ——
     * 尺寸是我们给的有限值，框里有字，用户能一眼看清"当前没有图"，
     * 也能直接框选删掉它。
     *
     * <p>文字里写清楚"没画出来、为什么、下一步怎么办"，比假装给用户
     * 一张错图更诚实。
     *
     * @param text 占位框里的说明文字
     * @return 场景 JSON；objectMapper 异常时返回 null
     */
    private String syntheticScene(String text) {
        try {
            ObjectNode el = objectMapper.createObjectNode();
            el.put("id", FALLBACK_ELEMENT_ID);
            el.put("type", "rectangle");
            el.put("x", 120);
            el.put("y", 120);
            el.put("width", 360);
            el.put("height", 60);
            el.set("label",
                    objectMapper.createObjectNode()
                            .put("text", text));
            ArrayNode elements =
                    objectMapper.createArrayNode();
            elements.add(el);
            ObjectNode root = objectMapper.createObjectNode();
            root.set("elements", elements);
            root.put("schemaVersion", IR_SCHEMA_VERSION);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            // 兜底的兜底：走到这里说明 objectMapper 都出问题了，
            // 交回 null 让调用方退回 error 语义
            LOG.warn("兜底场景合成失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 下发兜底场景并正常收尾通道
     *
     * <p>原因走 <code>validation</code> 通道（前端当 agent 消息显示，
     * 与"第 N 轮校验失败"同一惯用法），场景本身走 <code>result</code>
     * —— 前端把它当终态开关。
     *
     * <p>这里**不落库**：兜底内容可能是用户原有的图（会话里本来就有），
     * 也可能是占位文字（落库会让下一次编辑以一条垃圾文字为起点）。
     * 两种都不写，用户重试时起点与今天看到的完全一致。
     *
     * @param emitter  SSE 发射器
     * @param note     为什么降级；也会写进占位文字里
     * @param fallback 兜底场景 JSON；为 null 表示兜底本身失败
     * @return true 表示已下发；false 表示调用方应退回 error 语义
     */
    private boolean deliverFallback(
            SseEmitter emitter, String note,
            String fallback) {
        if (fallback == null) {
            return false;
        }
        sendEvent(emitter, "validation", note);
        sendEvent(emitter, "result", fallback);
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            LOG.warn("SSE 通道已结束，跳过收尾: {}",
                    e.getMessage());
        }
        return true;
    }
}
