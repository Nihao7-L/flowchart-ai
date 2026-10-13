package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.graph.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.GraphValidator;
import io.github.nihaoljx.flowchart.graph.SceneBinder;
import io.github.nihaoljx.flowchart.graph.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.SceneSanitizer;
import io.github.nihaoljx.flowchart.graph.ValidationIssue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public SceneGate(SceneBinder binder, SceneDeduper deduper,
                     SceneSanitizer sanitizer, ScenePlanner planner,
                     GraphValidator validator, GeometryAudit audit) {
        this.binder = binder;
        this.deduper = deduper;
        this.sanitizer = sanitizer;
        this.planner = planner;
        this.validator = validator;
        this.audit = audit;
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
     * @param userElements 用户手绘旁路元素（去重比对用，可空表）
     */
    public Outcome process(String rawSceneJson,
                           List<Map<String, Object>> userElements) {
        String json;
        try {
            json = binder.bind(rawSceneJson);
            json = deduper.dedup(json, userElements);
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
        return new Outcome(json, List.copyOf(issues), true);
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
