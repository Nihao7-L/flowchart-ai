package io.github.nihaoljx.flowchart.graph.validate;

import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.graph.transform.ArrowGeometry;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.SceneDrift;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 场景闸门（双管线收口后的**唯一**判定实现）
 *
 * 收拢"LLM 交付物能不能收下"的全部分判定，供两条管线共用：
 *
 * - agent 工具（submit_spec / submit_scene / update_elements）
 *   经 process 调用 —— 校验是闸门不是模型动作（2026-10-15
 *   用户拍板「那些不是工具」，validate_scene 已撤下）；
 *
 * - 生成编排层（GenerationService）按步组合调用：
 *   runPipeline + parseGate + validateAll + detectRewrite
 *   + emptySceneIssue —— 兜底语义（解析失败给图、不伪装成功）
 *   留在循环侧，判定逻辑只有这里一份。
 *
 * 管线顺序固定：bind → dedup → sanitize → 箭头几何归一化 → claimFramesOnly
 * （吸附在去重前、sanitize 在 validate 前、几何归一化在 sanitize 后、
 * 认领在最后）。
 * 判定顺序固定：parseGate → 结构校验 + 几何体检 → 空场景拦截
 * → 改图保真（编辑模式首次提交）。
 */
public final class SceneGate {

    /**
     * 严格 JSON 解析器：FAIL_ON_TRAILING_TOKENS ——
     * 对齐前端 JSON.parse 的严格解析口径，"JSON 后多了个字符"
     * 也算读不通，不放过。
     */
    private static final ObjectMapper STRICT_JSON =
            new ObjectMapper().enable(
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final SceneBinder binder;
    private final SceneDeduper deduper;
    private final SceneSanitizer sanitizer;
    private final ScenePlanner planner;
    private final GraphValidator validator;
    private final GeometryAudit audit;

    /**
     * 语义校验（D3）：场景内保守语义检查（现阶段只报同向同标签
     * 重复连线这类高置信问题）。参考结构比对（checkAgainstReference）
     * 不进闸门 —— 它是按需调用的硬指标（D5 打分 / 参考图验证），不是
     * 每次提交都必须有的门禁。
     */
    private final SemanticValidator semantic = new SemanticValidator();

    /**
     * 改图保真检查（可选）。编辑模式首次提交时跑
     * `check(基线, 提交结果)`，防"保留 id 但整图重排"。
     * null = 不做（新建模式 / 单测省事）。
     */
    private final SceneDrift drift;

    public SceneGate(SceneBinder binder, SceneDeduper deduper,
                     SceneSanitizer sanitizer, ScenePlanner planner,
                     GraphValidator validator, GeometryAudit audit) {
        this(binder, deduper, sanitizer, planner, validator, audit, null);
    }

    public SceneGate(SceneBinder binder, SceneDeduper deduper,
                     SceneSanitizer sanitizer, ScenePlanner planner,
                     GraphValidator validator, GeometryAudit audit,
                     SceneDrift drift) {
        this.binder = binder;
        this.deduper = deduper;
        this.sanitizer = sanitizer;
        this.planner = planner;
        this.validator = validator;
        this.audit = audit;
        this.drift = drift;
    }

    /**
     * process 的产物：管线后 JSON + 校验结论。
     *
     * @param json      管线产物（parseGate 未过时可能是半成品，仍带回）
     * @param issues    校验 issue（parseGate 未过时只含那一条）
     * @param parseable 是否读得通（false 时调用方不得把它当有效进度）
     */
    public record Outcome(String json, List<ValidationIssue> issues,
                          boolean parseable) {
    }

    /**
     * 全管线 + 校验（submit_scene 的门）。
     *
     * @param rawSceneJson 模型提交的原始场景 JSON
     * @param ctx          运行上下文（手绘旁路 / 编辑基线都从这里取）
     */
    public Outcome process(String rawSceneJson, ToolContext ctx) {
        String json;
        try {
            json = runPipeline(rawSceneJson, ctx.userElements());
        } catch (RuntimeException parseFailure) {
            ValidationIssue issue = new ValidationIssue(
                    "scene",
                    "场景无法解析: " + parseFailure,
                    "整份重发一个合法的场景 JSON");
            return new Outcome(null, List.of(issue), false);
        }
        ValidationIssue malformed = parseGate(json);
        if (malformed != null) {
            return new Outcome(json, List.of(malformed), false);
        }
        List<ValidationIssue> issues = new ArrayList<>();
        // report 1.3 第二步（跨类单次解析）：parseGate 已经用 STRICT_JSON
        // 把这份 json 读通过一次，这里取一次树，交给结构校验、几何体检
        // 与空场景判定三家复用 —— 原先这一路要重复 readTree 四次。
        // 语义校验（semantic）仍收字符串，见 validateAll 的注释。
        JsonNode sceneRoot = tryReadTree(json);
        if (sceneRoot != null) {
            issues.addAll(validateAll(json, sceneRoot));
        } else {
            // 理论上到不了：parseGate 已确认读得通。真到了就退回字符串
            // 入口，宁可多解析几次也不能漏掉校验。
            issues.addAll(validateAll(json));
        }
        // 空场景拦截：判的是**结果**不是 issue 数量 —— 校验器自己
        // 也可能漏报空数组，这里独立判一次
        ValidationIssue empty = emptySceneIssue(sceneRoot);
        if (empty != null && issues.isEmpty()) {
            issues.add(empty);
        }
        // 编辑保真：只提醒一次 ——
        // 只做一次（首次提交），之后坚持重画就放行
        if (issues.isEmpty() && ctx.previousJson() != null
                && !ctx.rewriteChecked()) {
            ctx.markRewriteChecked();
            ValidationIssue rewrite =
                    detectRewrite(ctx.previousJson(), json);
            if (rewrite != null) {
                issues.add(rewrite);
            } else if (drift != null) {
                ValidationIssue drifted =
                        drift.check(ctx.previousJson(), json);
                if (drifted != null) {
                    issues.add(drifted);
                }
            }
        }
        return new Outcome(json, List.copyOf(issues), true);
    }

    /**
     * 静默读树：读不通返回 null（不抛、不报 issue）。
     *
     * <p>只在「前面已经过 parseGate 确认读得通」的位置使用，
     * 用来把同一份 json 的多次解析收敛成一次。解析失败时返回 null，
     * 调用方按"读不懂就不判定"处理，语义与原先"catch 后返回 null"一致。
     */
    private static JsonNode tryReadTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return STRICT_JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 管线五步：bind → dedup → sanitize → 箭头几何归一化 → claimFramesOnly。
     *
     * 输入不可解析时抛 RuntimeException，由调用方决定语义
     * （agent 工具转修正 issue；编排层转兜底场景）——
     * 这里不吞异常、不把关，判定是后面 parseGate 的职责。
     */
    public String runPipeline(
            String rawSceneJson,
            List<Map<String, Object>> userElements) {
        String json = binder.bind(rawSceneJson);
        json = deduper.dedup(json, userElements);
        json = sanitizer.sanitize(json);
        // 箭头几何归一化：自由绘制（submit_scene）路径的箭头坐标由 LLM 原生
        // 给出、会画斜线，这里与 ELK 路径的 Polyline 正交化对齐；对已正交的
        // submit_spec 幂等（无变化原样返回）。排在 sanitize 后（此时 points
        // 已是相对坐标）、claimFramesOnly 前（认领只动 frame）。见 ArrowGeometry。
        json = ArrowGeometry.normalize(json);
        return planner.claimFramesOnly(json);
    }

    /**
     * 只做结构校验 + 几何体检（不含 parseGate / 空场景拦截），
     * 供"已确认读得通"的调用方追加到 issue 列表。
     */
    public List<ValidationIssue> validateAll(String json) {
        List<ValidationIssue> issues = new ArrayList<>(
                validator.validate(json));
        // 语义校验（D3）：连接语义的高置信问题（重复连线等），
        // 与结构校验同权 —— 是闸门的一部分
        issues.addAll(semantic.validate(json));
        // 几何体检：结构合法不等于画得能看。体检器只报告、
        // 绝不改坐标（怎么挪由 LLM 在修正轮里自己决定）。
        issues.addAll(audit.audit(json));
        return issues;
    }

    /**
     * 校验版本（report 1.3 的第二步）：调用方已持有解析树时，
     * 结构与几何两道都走树入口，省掉两次重复解析。
     *
     * <p>语义校验仍收字符串 —— 它判的是连线语义、内部要读原始文本，
     * 且不是热路径，留在字符串入口即可。
     *
     * @param json 同一份 json（语义校验用）
     * @param root 它的解析树（结构校验与几何体检用）
     */
    private List<ValidationIssue> validateAll(
            String json, JsonNode root) {
        List<ValidationIssue> issues = new ArrayList<>(
                validator.validateTree(root));
        issues.addAll(semantic.validate(json));
        issues.addAll(audit.auditTree(root));
        return issues;
    }

    /**
     * 空场景拦截：模型交出 {"elements":[]} 时，即使校验器一笔没挑，
     * 这份结果也不能落库下发。返回拦截 issue；非空返回 null。
     *
     * 判的是**结果**不是 issue 数量：校验器自己也可能漏报空数组，
     * 调用方须独立判一次。解析失败一律不算空 —— 坏 JSON 由
     * parseGate / 结构校验去报更准确的问题。
     */
    public static ValidationIssue emptySceneIssue(String json) {
        if (json == null || json.isBlank()) {
            return new ValidationIssue(
                    "elements",
                    "场景为空：一个可见元素都没有"
                            + "（不要返回 {\"elements\":[]}）",
                    "至少输出一个可见元素");
        }
        try {
            JsonNode elements =
                    STRICT_JSON.readTree(json).get("elements");
            boolean empty = elements == null
                    || !elements.isArray()
                    || elements.isEmpty();
            return empty ? emptySceneIssue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 空场景拦截（已解析版本）：report 1.3 的第一步。
     *
     * <p>调用方若已持有 {@code STRICT_JSON.readTree(json)} 的树（例如
     * {@link #process} 里 parseGate 刚校验过的那一份），直接用这个入口，
     * 省掉一次 14KB 级别的重复解析。语义与字符串版本完全一致：
     * 树为 null 或 elements 不是非空数组 ⇒ 判空；树读不懂（null）
     * 在字符串版本里本就返回 null，这里的 null 同样表示"不判定"，
     * 但 process 已在 parseGate 处拦掉读不通的情况，因此不会漏判。
     *
     * @param sceneRoot 已解析的场景根节点
     * @return 判空时返回 issue；非空返回 null
     */
    private static ValidationIssue emptySceneIssue(JsonNode sceneRoot) {
        JsonNode elements = sceneRoot == null
                ? null
                : sceneRoot.get("elements");
        boolean empty = elements == null
                || !elements.isArray()
                || elements.isEmpty();
        return empty ? emptySceneIssue() : null;
    }

    /** 空场景 issue 的唯一构造点（避免两处文案漂移） */
    private static ValidationIssue emptySceneIssue() {
        return new ValidationIssue(
                "elements",
                "场景为空：一个可见元素都没有"
                        + "（不要返回 {\"elements\":[]}）",
                "至少输出一个可见元素");
    }

    /**
     * 解析闸门：确认交付物真的能被标准解析器读出来、且是场景结构。
     *
     * 为什么"链路走完了"不等于"读得通"：管线四环的设计纪律都是
     * **宽容**的 —— 遇到坏 JSON 一律原样透传，因为它们的职责是
     * "能修就修"，不是"把关"。所以一段谁都不认识的文本可以安然
     * 穿过整条链，一路走到下发。
     *
     * 闸门怎么用：失败不直接丢弃，而是转成一条 issue 交给修正 ——
     * 这类问题的修法对模型来说极其简单（"重新输出一遍合法 JSON"），
     * 给一轮几乎必成。
     *
     * @param json 管线处理完、准备交给校验器的场景 JSON
     * @return 读不通 / 不是场景结构时返回一条 issue；否则返回 null
     */
    public static ValidationIssue parseGate(String json) {
        if (json == null || json.isBlank()) {
            return new ValidationIssue(
                    "$",
                    "交付内容为空",
                    "必须输出一个包含 elements 的 JSON 对象");
        }
        try {
            JsonNode root = STRICT_JSON.readTree(json);
            if (root == null || !root.isObject()
                    || !root.has("elements")
                    || !root.get("elements").isArray()) {
                return new ValidationIssue(
                        "$",
                        "顶层不是 JSON 对象或缺 elements 数组"
                                + "（实际是 "
                                + (root == null ? "空" : root.getNodeType())
                                + "）",
                        "必须输出一个对象：{\"elements\":[…]}");
            }
            return null;
        } catch (Exception e) {
            // 最常见的就是"JSON 后面还跟着一句话"：Jackson 默认容忍尾随
            // 内容，前端不容忍。这里如实报出来，让模型知道要整份重发。
            return new ValidationIssue(
                    "$",
                    "交付内容不是合法 JSON: " + e.getMessage(),
                    "只输出一个 JSON 对象：前后不要任何多余字符、"
                            + "不要写注释、不要用 ``` 包裹、"
                            + "不要加「以上」之类的收尾话");
        }
    }

    /**
     * 「改图」保真检查：识别"把用户的图换成了另一张新图"。
     *
     * 判据刻意保守：只在"旧元素几乎全没留下"时报警。
     * 用户合法地删掉一半元素不该被拦（那会让检查变成阻碍），
     * 而"绝大部分 id 都换了"几乎只可能是"忽略了当前场景"。
     * 解析失败不报（结构校验会给更准的 issue），
     * 不因自己读不懂而阻断提交。
     *
     * @param previousJson 编辑前的基线模型（JSON 文本）
     * @param json         本轮提交的场景
     * @return 需要 LLM 修正的问题；判定通过返回 null
     */
    public static ValidationIssue detectRewrite(
            String previousJson, String json) {
        try {
            JsonNode beforeRoot = STRICT_JSON.readTree(previousJson);
            Set<String> before = idsOf(beforeRoot.get("elements"));
            if (before.size() < 2) {
                // 单元素场景不做判断：只画了一个框时说"换个颜色"，
                // id 变化属于正常，拦它只会制造无谓的修正轮
                return null;
            }
            JsonNode after = STRICT_JSON.readTree(json)
                    .get("elements");
            if (after == null || !after.isArray()
                    || after.isEmpty()) {
                return new ValidationIssue(
                        "elements",
                        "修改后的场景一个元素都没有（原场景有 "
                                + before.size() + " 个）",
                        "必须在原场景基础上修改：只去掉用户要求"
                                + "删除的元素，其余元素原样保留");
            }
            Set<String> afterIds = idsOf(after);
            Set<String> kept = new HashSet<>(afterIds);
            kept.retainAll(before);
            // 收紧：只查"id 有交集"不够 —— LLM 重画时复用少数几个 id
            // 也能通过 → 用户看到新旧两张图并排。要求
            // ≥3 元素的旧图 id 重叠率 ≥ 30%。
            if (before.size() >= 3) {
                double overlapRatio =
                        (double) kept.size() / before.size();
                if (overlapRatio < 0.3) {
                    return new ValidationIssue(
                            "elements[].id",
                            "original " + before.size()
                                    + " elements, only "
                                    + kept.size() + " ids kept"
                                    + " (overlap " + String.format(
                                            "%.0f", overlapRatio * 100)
                                    + "%) - looks like a new diagram",
                            "keep unmodified elements id/geometry/style,"
                                    + " only change what user asked;"
                                    + " only discard all when user"
                                    + " explicitly asks to redraw");
                }
                return null;
            }
            // 旧图 < 3 个元素：id 全换 ≠ 一定出错（可能就是"另画一张"），
            // 再要求新场景规模明显缩水才报警 —— 正常换主题时
            // 新旧图规模相当，少了这条"另画一张"的正常路径会白跑一轮修正
            if (afterIds.size() * 2 >= before.size()) {
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
            return null;
        }
    }

    /** 收集一份场景（elements 数组节点）里所有元素的 id */
    private static Set<String> idsOf(JsonNode elements) {
        Set<String> result = new HashSet<>();
        if (elements == null || !elements.isArray()) {
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
}
