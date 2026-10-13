package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.agent.ToolContext;
import io.github.nihaoljx.flowchart.graph.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.GraphValidator;
import io.github.nihaoljx.flowchart.graph.SceneBinder;
import io.github.nihaoljx.flowchart.graph.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.SceneDrift;
import io.github.nihaoljx.flowchart.graph.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.SceneSanitizer;
import io.github.nihaoljx.flowchart.graph.ValidationIssue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 场景闸门（v2-55）：把 legacy 管线的"管线四环 + 校验"原样包装给工具用
 *
 * <p>两个入口对应两个工具：
 * <ul>
 *   <li>{@link #process} —— submit_scene 用：bind → dedup → sanitize →
 *       claimFramesOnly → parseGate → 结构校验 + 几何体检 + 空场景拦截，
 *       顺序与 {@code GenerationService} 逐环一致（吸附在去重前、
 *       sanitize 在 validate 前、认领在最后）；</li>
 *   <li>{@link #validateOnly} —— validate_scene 用：对已受理的场景
 *       只跑 parseGate + 校验（bind/dedup/sanitize 是改写动作，不重复做）。</li>
 * </ul>
 *
 * <p><b>为什么原样照抄而不是重写</b>：这一段是生产链路的行为基线
 * （顺序敏感、注释里全是血泪教训），v2-55 的定位是"降格为工具与观察、
 * 不重写"。drawback：与 GenerationService 有第二份相似代码 ——
 * step 3 接线后按"谁存活删谁"收口，届时消灭。
 */
public final class SceneGate {

    /**
     * 严格 JSON 解析器（与 GenerationService.STRICT_JSON 同口径）：
     * FAIL_ON_TRAILING_TOKENS —— 对齐前端 JSON.parse 的严格解析，
     * "JSON 后多了个字符"也算读不通，不放过。
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
     * 改图保真检查（v2-37，可选）。编辑模式首次提交时跑
     * {@code check(基线, 提交结果)}，防"保留 id 但整图重排"。
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
            json = binder.bind(rawSceneJson);
            json = deduper.dedup(json, ctx.userElements());
            json = sanitizer.sanitize(json);
            json = planner.claimFramesOnly(json);
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
        List<ValidationIssue> issues = new ArrayList<>(
                validator.validate(json));
        issues.addAll(audit.audit(json));
        if (isEmptyScene(json) && issues.isEmpty()) {
            issues.add(new ValidationIssue(
                    "elements",
                    "场景为空：一个可见元素都没有"
                            + "（不要返回 {\"elements\":[]}）",
                    "至少输出一个可见元素"));
        }
        // 编辑保真（v2-55 步骤 3 补）：与 legacy 第 1 轮同口径 ——
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
     * 「改图」保真检查（从 GenerationService.detectRewrite 移植的保守版）
     *
     * <p>判据刻意一致：只在"旧元素一个都没留下"或"id 保留率过低"时报警，
     * 用户合法地删掉一半元素不该被拦。解析失败不报（结构校验会给更准的
     * issue），不因自己读不懂而阻断提交。
     */
    static ValidationIssue detectRewrite(
            String previousJson, String json) {
        try {
            JsonNode beforeRoot = STRICT_JSON.readTree(previousJson);
            Set<String> before = idsOf(beforeRoot.get("elements"));
            if (before.size() < 2) {
                // 单元素场景不做判断：只画了一个框时说"换个颜色"，
                // id 变化属于正常（与 legacy 同判据）
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
            // 再要求新场景规模明显缩水才报警（与 legacy 同判据）
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

    /**
     * 只校验（validate_scene 的门）：对已受理场景重跑
     * parseGate + 结构校验 + 几何体检 + 空场景拦截。
     */
    public List<ValidationIssue> validateOnly(String json) {
        ValidationIssue malformed = parseGate(json);
        if (malformed != null) {
            return List.of(malformed);
        }
        List<ValidationIssue> issues = new ArrayList<>(
                validator.validate(json));
        issues.addAll(audit.audit(json));
        if (isEmptyScene(json) && issues.isEmpty()) {
            issues.add(new ValidationIssue(
                    "elements",
                    "场景为空：一个可见元素都没有",
                    "至少输出一个可见元素"));
        }
        return List.copyOf(issues);
    }

    /**
     * 解析闸门（对齐 legacy）：STRICT_JSON 读得通、根是对象、
     * elements 是数组 —— 三者差一都算 malformed。
     */
    private static ValidationIssue parseGate(String json) {
        try {
            JsonNode root = STRICT_JSON.readTree(json);
            if (root == null || !root.isObject()
                    || !root.has("elements")
                    || !root.get("elements").isArray()) {
                return new ValidationIssue(
                        "scene",
                        "JSON 读得通但不是场景结构（缺 elements 数组）",
                        "重发 {\"elements\":[...]} 形态的场景 JSON");
            }
            return null;
        } catch (Exception e) {
            return new ValidationIssue(
                    "scene",
                    "场景 JSON 读不通: " + e.getMessage(),
                    "整份重发一个合法 JSON（不要带任何收尾文字）");
        }
    }

    /** 空场景判定：elements 数组存在但没有任何元素 */
    private static boolean isEmptyScene(String json) {
        try {
            JsonNode elements =
                    STRICT_JSON.readTree(json).get("elements");
            return elements == null || elements.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
