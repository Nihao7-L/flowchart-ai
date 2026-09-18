package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.graph.GraphValidator;
import io.github.nihaoljx.flowchart.graph.LayoutEngine;
import io.github.nihaoljx.flowchart.graph.SceneBinder;
import io.github.nihaoljx.flowchart.graph.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.SceneDrift;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
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
     */
    public static final long TOTAL_BUDGET_MS = 420_000L;

    /** 留给"发结果 + 关闭流"的收尾余量（毫秒），不参与 LLM 调用 */
    private static final long TAIL_RESERVE_MS = 20_000L;

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
     */
    private static final int MAX_ROUND_SECONDS = 300;

    /** 低于这个秒数就不再开新一轮：开也跑不完，不如早点如实报错 */
    private static final int MIN_ROUND_SECONDS = 10;

    private final PromptService promptService;
    private final LlmProvider llmProvider;
    private final GraphValidator graphValidator;
    private final SceneBinder sceneBinder;
    private final SceneDeduper sceneDeduper;
    /** 编辑模式的几何保真校验（v2-37）：防"保留 id 但整图重排坐标" */
    private final SceneDrift sceneDrift;
    /** 布局（v2-36）：坐标由它确定性算出，LLM 给的只是种子值 */
    private final LayoutEngine sceneLayout;
    private final SessionStore sessionStore;

    /** 会话模型的序列化/反序列化（后端持 IR 唯一真相源，见 ADR-2） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GenerationService(
            PromptService promptService,
            LlmProvider llmProvider,
            GraphValidator graphValidator,
            SceneBinder sceneBinder,
            SceneDeduper sceneDeduper,
            SceneDrift sceneDrift,
            LayoutEngine sceneLayout,
            SessionStore sessionStore) {
        this.promptService = promptService;
        this.llmProvider = llmProvider;
        this.graphValidator = graphValidator;
        this.sceneBinder = sceneBinder;
        this.sceneDeduper = sceneDeduper;
        this.sceneDrift = sceneDrift;
        this.sceneLayout = sceneLayout;
        this.sessionStore = sessionStore;
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
            generateInternal(
                    sessionId, userText,
                    emitter, deadline);
        } catch (RuntimeException e) {
            // 生成本身跑在独立线程里：异常一旦不接住就直接打穿线程，
            // 前端什么反馈都收不到（曾因 emitter 已被容器关闭而裸崩）
            LOG.error("生成线程异常终止", e);
            sendError(emitter,
                    "生成异常终止: " + e.getMessage());
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
        String prompt;
        try {
            prompt = promptService
                    .buildPrompt(userText, effectiveSceneJson);
        } catch (IOException e) {
            sendError(emitter,
                    "加载 schema 失败: "
                            + e.getMessage());
            return;
        }

        for (int round = 1;
                round <= MAX_ROUNDS; round++) {
            int roundTimeout = planRound(deadline);
            if (roundTimeout < MIN_ROUND_SECONDS) {
                long usedSec = (TOTAL_BUDGET_MS
                        - (deadline
                                - System.currentTimeMillis()))
                        / 1000;
                // 预算不足以再开一轮。到得了这里说明前面每一轮都校验没过，
                // 因此**没有**可发的合格结果 —— 只能带原因收尾。
                sendError(emitter,
                        "生成超时：已耗时 " + usedSec
                                + " 秒，剩余时间不足以完成第 "
                                + round
                                + " 轮；请把图形拆小后重试");
                return;
            }

            LOG.info("生成轮次 {}/{}，本轮上限 {} 秒",
                    round, MAX_ROUNDS, roundTimeout);
            sendEvent(emitter, "thinking",
                    "第 " + round + " 轮生成中...");

            String raw;
            try {
                raw = llmProvider.chat(
                        prompt, roundTimeout);
            } catch (Exception e) {
                sendError(emitter,
                        "LLM 调用失败: "
                                + e.getMessage());
                return;
            }
            if (raw == null || raw.isBlank()) {
                sendError(emitter,
                        "LLM 返回内容为空");
                return;
            }

            // 吸附归一化：把"两端都落在图形上的两点箭头"升级成绑定式，
            // 让连线在用户拖动图形时能跟着走（v2-33）
            String json = sceneBinder.bind(
                    extractJson(raw));
            // 去重：丢掉与"用户手绘连线"撞端点的线，以及 LLM 自己
            // 画的重复线（v2-34）。必须排在 bind 之后 —— 吸附后的线
            // 是绑定式，比 id 对比比坐标可靠
            json = sceneDeduper.dedup(
                    json, userElements);
            // 布局：把"摆在哪"从 LLM 手里收回（v2-36）。顺序有讲究 ——
            // 必须在 bind 之后（分层要知道"哪两个图形相连"），
            // 在 dedup 之后（别为马上要被丢掉的线排版）。
            // 编辑模式传入上一版 id 全集：既有图形的坐标一个都不许动，
            // 否则会被 v2-37 的 SceneDrift 判成"整图重排"——两个
            // 组件当场互相打架（见 v2-36 决策 3）
            json = sceneLayout.layout(json,
                    idsOfJson(effectiveSceneJson));

            sendEvent(emitter, "validation",
                    "第 " + round + " 轮校验中...");
            List<ValidationIssue> issues =
                    new ArrayList<>(
                            graphValidator
                                    .validate(json));
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

            if (issues.isEmpty()) {
                // 校验通过即发（2026-09-17）：不再用同一个 prompt
                // 空跑后两轮。想在图质量上继续投入只能靠"修正轮"，
                // 而修正轮的前提是**有 issues** —— 没 issue 就没有
                // 可修的东西，再摇一次只是重掷骰子。
                LOG.info("第 {} 轮校验通过，直接落库并下发",
                        round);
                saveModel(sessionId, json, !takeOver);
                sendEvent(emitter, "result", json);
                emitter.complete();
                return;
            }

            LOG.warn("第 {} 轮校验失败，共 {} 个 issue",
                    round, issues.size());
            sendEvent(emitter, "validation",
                    "第 " + round + " 轮校验失败: "
                            + issues.size()
                            + " 个问题");

            if (round == MAX_ROUNDS) {
                String summary = issues.stream()
                        .map(i -> i.field() + ": "
                                + i.reason())
                        .reduce((a, b) ->
                                a + "; " + b)
                        .orElse("未知");
                sendError(emitter,
                        "已重试 " + MAX_ROUNDS
                                + " 轮仍不通过: "
                                + summary);
                return;
            }

            try {
                prompt = promptService
                        .buildFixPrompt(
                                userText, issues,
                                previousJson);
            } catch (IOException e) {
                sendError(emitter,
                        "构建修正 prompt 失败: "
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
}
