package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.agent.AgentLoopListener;
import io.github.nihaoljx.flowchart.agent.AgentLoopService;
import io.github.nihaoljx.flowchart.llm.AgentResult;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolCall;
import io.github.nihaoljx.flowchart.llm.LlmProvider;
import io.github.nihaoljx.flowchart.llm.Message;
import io.github.nihaoljx.flowchart.session.AssembleInput;
import io.github.nihaoljx.flowchart.session.ContextAssembler;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.SceneDrift;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;
import io.github.nihaoljx.flowchart.session.InMemoryMemoryStore;
import io.github.nihaoljx.flowchart.session.MemoryStore;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生成→校验→修正循环（legacy 管线编排层）
 *
 * 流程：prompt → LLM → 校验 → 不通过则携带 issues
 * 重调 → 达标即停。每一步都通过 SSE 推事件给前端。
 *
 * 本类只保留**编排职责**：选路（agent / legacy）、轮次循环、
 * 落库与兜底纪律的调用。时间预算在 GenerationBudget，
 * SSE 事件出口在 GenerationEvents，JSON 种子工位在
 * SceneSeedBuilder，失败兜底决策在 GenerationFallback。
 */
@Service
public class GenerationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    GenerationService.class);

    /**
     * 生成循环的兜底轮数：只防死循环，不是"跑满才停"。
     * 真正退出靠 predicate —— `issues.isEmpty()`（结构合法 + 几何达标
     * + 非空）一旦达成立即 return，与轮次无关。总时间预算是第二道墙。
     */
    private static final int BACKSTOP_MAX_ROUNDS = 5;

    /**
     * 静态层正文的唯一真相源（提示词正文 + 契约表都从这里派生）。
     * 仅为读 `promptVersion()` 而注入 —— 版本号不再写死在本类。
     */
    private final PromptService promptService;

    /**
     * 提示词版本号 —— 每轮组装时由 promptService 现读。
     *
     * 只做**溯源标注**，不参与任何逻辑判断 —— 送进模型的正文里
     * 不含版本号（元数据进正文会破坏 KV-cache 前缀）。
     */
    private String promptVersion() {
        return promptService.promptVersion();
    }

    private final LlmProvider llmProvider;

    /**
     * Agent Loop 运行入口 —— **可选**依赖。
     *
     * 仅当 `chartflow.agent-loop.enabled=true` 时容器里才有
     * AgentLoopService 这个 bean；`getIfAvailable()` 拿不到
     * 就走 legacy 管线 —— "flag 默认关、老管线保底"的落点。测试里直接
     * 传 null 等价于 flag 关。
     */
    private final ObjectProvider<AgentLoopService> agentLoops;
    private final SceneDrift sceneDrift;
    /** 场景闸门：管线四环 + 全部判定的单一真相源（与 agent 工具共用） */
    private final SceneGate sceneGate;
    private final SessionStore sessionStore;
    /**
     * 上下文组装：把静态层（角色 / 契约 / 规则）与动态层
     * （当前场景 / 用户需求 / 校验 issues）拼成 system + user 两条消息。
     */
    private final ContextAssembler contextAssembler;

    /** 会话模型的序列化/反序列化（后端持 IR 唯一真相源，见 ADR-2） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** JSON 种子工位：手绘提升 / 版本盖章 / 空判定 / id 读取 / 占位合成 */
    private final SceneSeedBuilder seedBuilder;

    /** 失败兜底决策：话术 / 摘要 / 选材 / 下发收尾 */
    private final GenerationFallback fallback;

    /**
     * 跨轮记忆：按会话累积校验 issue 历史（本轮只落"记录"，
     * 把历史注入修正提示词的消费策略归 m5）。
     * 进程内实现、随本 bean 存活，不改构造器签名（单测零感知）。
     */
    private final MemoryStore memoryStore = new InMemoryMemoryStore();

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
        this(llmProvider, null, graphValidator,
                sceneBinder, sceneDeduper, sceneSanitizer,
                sceneDrift, scenePlanner, sessionStore,
                contextAssembler, geometryAudit,
                promptService);
    }

    /**
     * 完整构造器（Spring 用这个）：agent 依赖为可选注入。
     *
     * 两个构造器并存时 Spring 要求显式指定用哪个 —— @Autowired 标这里。
     * 11 参构造器只是给单测的便捷入口（agentLoops=null = flag 关）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public GenerationService(
            LlmProvider llmProvider,
            ObjectProvider<AgentLoopService> agentLoops,
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
        this.agentLoops = agentLoops;
        this.sceneDrift = sceneDrift;
        this.sessionStore = sessionStore;
        this.contextAssembler = contextAssembler;
        this.promptService = promptService;
        this.sceneGate = new SceneGate(
                sceneBinder, sceneDeduper, sceneSanitizer,
                scenePlanner, graphValidator, geometryAudit,
                sceneDrift);
        this.seedBuilder = new SceneSeedBuilder(objectMapper);
        this.fallback = new GenerationFallback(seedBuilder);
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
                + GenerationBudget.TOTAL_BUDGET_MS;
        // flag 开（且 bean 装配成功）才走 agent loop，
        // 否则 legacy 原样服务 —— 两条路径在 generate() 一层分流，
        // 各自完整拥有自己的兜底语义，不互相嵌套。
        AgentLoopService agent = agentLoops == null
                ? null : agentLoops.getIfAvailable();
        try {
            if (agent != null) {
                generateViaAgent(
                        agent, sessionId, userText,
                        emitter, deadline);
            } else {
                generateInternal(
                        sessionId, userText,
                        emitter, deadline);
            }
        } catch (RuntimeException e) {
            // 生成本身跑在独立线程里：异常一旦不接住就直接打穿线程，
            // 前端什么反馈都收不到（曾因 emitter 已被容器关闭而裸崩）
            LOG.error("生成线程异常终止", e);
            GenerationEvents.sendError(emitter,
                    "生成异常终止: " + e.getMessage());
        } finally {
            // 无状态收尾（原 LlmEffort ThreadLocal 清理已随「强模型升档」设计移除）
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
        // 轮次语义（修正）：通过即发、失败才继续。
        //
        // 旧实现是"每轮通过都先存档、继续跑满 3 轮、最后才发"，而第 2/3 轮
        // 用的 prompt 与第 1 轮**完全相同**（通过时不会构建修正 prompt），
        // 等于靠采样随机性重摇一次。两个后果都是用户实打实等出来的：
        // (1) 简单图也要跑满 3 次 LLM 调用 —— 等待时间直接 ×3；
        // (2) 第 2/3 轮一旦撞上单轮上限，那份已经合格的存档会被彻底丢掉
        // （实测"LLM 调用超时：整次调用超过 296 秒"就走这条路）。
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
            GenerationEvents.sendError(emitter,
                    "加载 schema 失败: "
                            + e.getMessage());
            return;
        }

        // 记录"本轮已产出的可解析场景"：后面轮次若断掉，兜底时优先用它
        String lastParseable = null;

        for (int round = 1;
                round <= BACKSTOP_MAX_ROUNDS; round++) {
            int roundTimeout =
                    GenerationBudget.planRound(deadline);
            if (roundTimeout
                    < GenerationBudget.MIN_ROUND_SECONDS) {
                long usedSec = (GenerationBudget.TOTAL_BUDGET_MS
                        - (deadline
                                - System.currentTimeMillis()))
                        / 1000;
                // 预算不足以再开一轮 —— 但**超时是时间花完了，后端自己
                // 没毛病**，它跟"组装修正失败 / 加载 schema 失败"不是一类：
                // 后者是后端真的坏了，下发假图反而是把事故盖住。前者下发
                // 纯 error 的后果是用户整句输入毫无回响（实测
                // "整次调用超过 296 秒"那次就是）。
                //
                // 改成下发**合法场景**（编辑模式优先把原图
                // 还回去，新建模式给一条占位说明），用户至少看得到东西。
                String note = "生成超时：已耗时 " + usedSec
                        + " 秒，剩余时间不足以完成第 " + round
                        + " 轮；" + fallback.fallbackOutcome(editMode)
                        + "，请把图形拆小后重试";
                if (fallback.deliverFallback(emitter, note,
                            fallback.fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                GenerationEvents.sendError(emitter,
                        "兜底场景合成失败");
                return;
            }

            LOG.info("生成轮次 {}/{}，本轮上限 {} 秒",
                    round, BACKSTOP_MAX_ROUNDS, roundTimeout);
            GenerationEvents.sendEvent(emitter, "thinking",
                    "第 " + round + " 轮生成中...");

            // 纯故障转移模式：候选按 tier 升序、谁不可用切谁，不按轮次升档

            String raw;
            try {
                raw = llmProvider.chat(
                        messages, roundTimeout);
            } catch (Exception e) {
                // 先看"还有没有下一轮"再决定兜底。
                // 以前这里无条件兜底 —— 于是"首轮候选全挂"等价于"整次失败"，
                // 强模型若只允许第 2 轮起用，等于永远轮不到它（实测
                // 15 模块大图就栽在这：round 1 四个候选全超时，一次都没试到
                // 备选）。轮次本身就是"换一批更贵的候选"的开关，
                // 所以只要预算还够就必须往下走一轮。
                long leftMs = deadline - System.currentTimeMillis()
                        - GenerationBudget.TAIL_RESERVE_MS;
                if (round < BACKSTOP_MAX_ROUNDS
                        && leftMs > GenerationBudget.MIN_ROUND_SECONDS
                                * 1000L) {
                    LOG.warn("第 {} 轮 LLM 调用失败（{}），"
                            + "剩余预算 {}s 仍够，转入第 {} 轮重试",
                            round, e.getMessage(), leftMs / 1000,
                            round + 1);
                    GenerationEvents.sendEvent(emitter, "validation",
                            "第 " + round + " 轮模型均不可用，"
                                    + "转下一轮重试");
                    continue;
                }
                // LLM 是**外部依赖**，它超时/报错是在我们正常
                // 工作的过程中发生的，不是后端故障。此时给用户看一张
                // 「能画出来的图」（编辑模式就是他原有的那张）远好过看一句
                // error —— 空画布是这个产品能出的最高危事故。
                String note = "LLM 调用失败: " + e.getMessage()
                        + "；" + fallback.fallbackOutcome(editMode)
                        + "，可稍后重试";
                if (fallback.deliverFallback(emitter, note,
                            fallback.fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                GenerationEvents.sendError(emitter,
                        "兜底场景合成失败");
                return;
            }
            if (raw == null || raw.isBlank()) {
                // 同上：返回空白同样是"模型没干活"，不是后端坏了
                String note = "LLM 返回内容为空；"
                        + fallback.fallbackOutcome(editMode)
                        + "，可稍后重试";
                if (fallback.deliverFallback(emitter, note,
                            fallback.fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                GenerationEvents.sendError(emitter,
                        "兜底场景合成失败");
                return;
            }

            // 这三步都会**解析整份 JSON**，模型一旦返回
            // 被截断 / 混进 Frontmatter 的非法 JSON，就会在这里抛。
            // 之前没人接，异常直接冒到 generate() 变成一句 error，
            // 用户得到的是「报错」而不是「图」——违反输出策略。
            // 现在与「LLM 返回内容为空」同路径兜底。
            String json;
            try {
                // 管线四环（吸附 → 去重 → 宽容归一化 → 画框认领）收口在
                // SceneGate —— 判定与改写逻辑只有一份，与 agent 工具共用。
                // 吸附把"两端都落在图形上的两点箭头"升级成绑定式；去重丢掉
                // 与手绘撞端点的线（吸附后是绑定式，比 id 对比可靠）；归一化
                // 零损失修掉"差一点"的字段免得白烧一轮；认领只填
                // frame.children、不改动任何几何（「摆哪、多大」采用 LLM
                // 原生坐标，实测后端重排会把图收成窄条）。
                json = sceneGate.runPipeline(
                        extractJson(raw), userElements);
            } catch (RuntimeException parseFailure) {
                // 模型干活了，但交付的东西我们读不懂。跟"它没干活"一样
                // 属于外部依赖的锅，不是后端故障 —— 给它一张能看的图。
                LOG.warn("第 {} 轮返回内容无法解析，转兜底场景: {}",
                        round, parseFailure.toString());
                String note = "模型返回的内容无法解析；"
                        + fallback.fallbackOutcome(editMode)
                        + "，请把需求说得更具体后重试";
                if (fallback.deliverFallback(emitter, note,
                            fallback.fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                GenerationEvents.sendError(emitter,
                        "兜底场景合成失败");
                return;
            }

            // 解析闸门（判定逻辑单一真相源：SceneGate）：管线四环对坏
            // JSON 都是"原样透传"（宽容是它们的设计纪律 —— 能修就修，
            // 不把关），所以"链路跑完"**不代表读得通**。先真读一次：
            // 读不通就当 issue 交给修正轮（对模型来说"整份重发一个合法
            // JSON"几乎必成），并且不记进 lastParseable。
            ValidationIssue malformed = SceneGate.parseGate(json);
            if (malformed == null) {
                // 已能解析出合法结构：记下来，兜底时优先下发它（不丢进度）
                lastParseable = json;
            }

            GenerationEvents.sendEvent(emitter, "validation",
                    "第 " + round + " 轮校验中...");
            List<ValidationIssue> issues = new ArrayList<>();
            if (malformed != null) {
                // 读不通就无所谓"结构"了：结构校验 / 几何体检 / 改图保真
                // 对着一段读不出来的文本都说不出更有用的话，全跑一遍只会
                // 让修正 prompt 里堆满互相矛盾的提示。只报这一条，让模型
                // 把注意力全放在"重发一份合法 JSON"上。
                issues.add(malformed);
            } else {
                // 结构校验 + 几何体检（判定逻辑单一真相源：SceneGate）。
                // 体检器只报告、绝不改坐标（怎么挪由 LLM 在修正轮里
                // 自己决定），所以这里只是追加 issue。
                issues.addAll(sceneGate.validateAll(json));
                if (editMode && !takeOver && round == 1) {
                    // 只在第一轮提醒"别把用户的图换成新图"：它是一条提醒、
                    // 不是门禁 —— 第二轮 LLM 仍坚持重画就放行，
                    // 用户本来就可能明确要求"重新画一张"
                    ValidationIssue rewrite =
                            SceneGate.detectRewrite(previousJson, json);
                    if (rewrite != null) {
                        issues.add(rewrite);
                    }
                    // id 全换看 detectRewrite，**保留 id 但整图重排**
                    // 归这里。两者合起来才覆盖 PromptService 那句
                    // "没有要求改动的元素必须原样保留 id / 几何"。
                    ValidationIssue drift =
                            sceneDrift.check(previousJson, json);
                    if (drift != null) {
                        issues.add(drift);
                    }
                }
            }

            // 空场景拦截（闸门统一出口）：模型交出 {"elements":[]} 时，
            // 即使校验器一笔没挑，这份结果也绝不能落库下发 ——
            // 那正是"画布全白、用户以为成功生成了"的同款事故。
            // 注意判的是**结果**，不是 issue 数量：校验器自己也可能
            // 漏报空数组，所以这里独立判一次。
            ValidationIssue emptyIssue =
                    SceneGate.emptySceneIssue(json);
            if (emptyIssue != null && issues.isEmpty()) {
                issues.add(emptyIssue);
            }

            if (issues.isEmpty()) {
                // 校验通过即发：不再用同一个 prompt
                // 空跑后两轮。想在图质量上继续投入只能靠"修正轮"，
                // 而修正轮的前提是**有 issues** —— 没 issue 就没有
                // 可修的东西，再摇一次只是重掷骰子。
                LOG.info("第 {} 轮校验通过，直接落库并下发",
                        round);
                json = seedBuilder.stampSchemaVersion(json);
                saveModel(sessionId, json, !takeOver);
                GenerationEvents.sendEvent(emitter, "result", json);
                emitter.complete();
                return;
            }

            LOG.warn("第 {} 轮校验失败，共 {} 个 issue",
                    round, issues.size());
            memoryStore.recordIssues(sessionId, issues);
            // 明细必须跟着一起发：只写"共 N 个问题"时，
            // 前端和排查日志都只知道数量、不知道是什么 —— 大图第一次
            // 出来 35 个 issue，我们连主因是什么都得靠猜。
            GenerationEvents.sendEvent(emitter, "validation",
                    "第 " + round + " 轮校验失败: "
                            + issueDigest(issues));

            if (round == BACKSTOP_MAX_ROUNDS) {
                // 五轮都修不动 = 模型给不出可用结果，分两种：
                // (a) 场景**为空**（鱼骨图那组实测），(b) 图非空但那几条
                // 校验几轮都改不掉（f05 那批 frame/text + group、f30 那批
                // 箭头绑 frame）。旧实现两种都下发纯 error，用户整句输入
                // 毫无回响 —— 与 f12 白卷是同一类事故，只是触发条件换成
                // "图非空"。两种一律走兜底下发，区别只在
                // 给回的是原图还是占位框，逻辑只有这一套。
                String summary = fallback.truncatedSummary(
                        issueDigest(issues));
                String note = "已重试 " + BACKSTOP_MAX_ROUNDS
                        + " 轮仍未通过校验（" + summary
                        + "）：" + fallback.fallbackOutcome(editMode)
                        + "，请把图形拆小后重试";
                if (fallback.deliverFallback(emitter, note,
                            fallback.fallbackScene(previousJson,
                                    effectiveSceneJson, takeOver,
                                    lastParseable, note))) {
                    return;
                }
                // 兜底自身失败（objectMapper 都挂了）才退回 error
                GenerationEvents.sendError(emitter,
                        "已重试 " + BACKSTOP_MAX_ROUNDS
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
                GenerationEvents.sendError(emitter,
                        "组装修正消息失败: "
                                + e.getMessage());
                return;
            }
        }
    }

    /**
     * Agent Loop 路径：messages 单线积累 + 4 工具派发
     *
     * 会话状态（当前图 / 手绘旁路 / take-over）的读取逻辑与
     * generateInternal 完全一致；差别只在"怎么跟模型打交道"：
     *
     * - user 消息复用 buildUserBlock（当前场景
     *       + 修改规则 + 需求），system 复用 buildSystemBlock
     *       —— smoke（AgentLoopRealLlmIT）已验证 3 家候选对"这套提示词
     *       + tools 定义"都能正确走 tool_calls，不另造一份会漂移的文案；
     *
     * - 过程经 AgentLoopListener 翻译成 SSE 事件：
     *       `tool_call`/`tool_result` 两类新事件，前端当前
     *       忽略未知类型，属"可先忽略"的设计；
     *
     * - 终态处置与 legacy 同纪律：COMPLETED 落库下发；PARTIAL 走
     *       deliverFallback（最后一份可解析进度优先，不伪装成功）。
     */
    private void generateViaAgent(
            AgentLoopService agent,
            String sessionId,
            String userText,
            SseEmitter emitter,
            long deadline) {
        // 会话状态读取：与 generateInternal 相同的口径（含 Bug A take-over）
        String previousJson = currentModelJson(sessionId);
        boolean editMode = previousJson != null;
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

        String systemBlock;
        String userBlock;
        try {
            systemBlock = promptService.buildSystemBlock();
            userBlock = promptService.buildUserBlock(
                    userText, effectiveSceneJson);
        } catch (IOException e) {
            // 与 legacy 同口径：读不到提示词资源是后端自己的故障，
            // 保持 error 语义，不下发假图
            GenerationEvents.sendError(emitter,
                    "加载 schema 失败: " + e.getMessage());
            return;
        }

        GenerationEvents.sendEvent(emitter, "thinking",
                "Agent 模式（候选 " + agent.candidateName()
                        + "），messages 单线积累 + 工具循环");
        AgentLoopListener listener = new AgentLoopListener() {
            @Override
            public void onStep(int step, int maxSteps) {
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
            }
        };

        AgentResult result;
        try {
            result = agent.run(systemBlock, userBlock,
                    userElements, previousJson,
                    deadline, listener);
        } catch (Exception e) {
            // LLM 是外部依赖：与 legacy"候选全挂"同路径兜底。
            // 此前这条路径静默（连"Agent run 终态"都跳过），
            // 被上游 502 打穿时后端日志零行、只能靠 fallback scene 的
            // 【AI 备注】归因 —— 不可接受，故补一行日志。
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

        LOG.info("Agent run 终态 {} steps={} reason={}",
                result.status(), result.steps(),
                result.reason());
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

        // PARTIAL：与 legacy"多轮修不动"同纪律 —— 最后一份可解析
        // 进度优先（fallbackScene 的 lastParseable 槽位），
        // 说明文案如实（几步、为什么停），不伪装成功
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

    /**
     * 把一轮校验问题压成一行摘要（委托给兜底决策层）。
     * 保留在本类是因为主循环与单测都以它为入口。
     */
    String issueDigest(List<ValidationIssue> issues) {
        return fallback.issueDigest(issues);
    }
}
