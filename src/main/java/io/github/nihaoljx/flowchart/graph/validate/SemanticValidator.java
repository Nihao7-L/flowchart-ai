package io.github.nihaoljx.flowchart.graph.validate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义校验器（D3）：把「中间表示可程序化验证」的最大优势用起来。
 *
 * <p>{@link GraphValidator} 只做 schema/引用完整性（字段、类型、绑定形态），
 * {@link GeometryAudit} 只做几何测量 —— 两边都不回答「这张图逻辑对不对」。
 * 本类补上语义层，分两级：
 *
 * <ul>
 *   <li>{@link #validate(String)} —— 场景内保守检查（只报高置信问题，
 *       现阶段：同向同标签重复连线）。进 {@code SceneGate.validateAll}，
 *       作为闸门的一部分硬性拦截；</li>
 *   <li>{@link #checkAgainstReference(String, ReferenceSpec)} —— 与参考结构
 *       比对（必含节点/连线是否齐全），产出 recall 硬指标 + 点名化 issue。
 *       这是「连接正确性」的机器判据，也是 {@code FidelityScorer}(D5)
 *       结构保真分的底座。</li>
 * </ul>
 *
 * <p>匹配一律按**标签文本**（trim 后精确相等）：参考结构是人写的，
 * 不可能预知模型的 id。解析失败一律返回空/零值 —— 绝不打断生成，
 * 坏 JSON 由 parseGate / 结构校验去报更准的问题。
 */
public final class SemanticValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * 场景内保守语义检查。刻意只报「几乎不可能是故意的」问题：
     * 孤立节点（可能是模块框/标题，误报高）与方向颠倒等都不在此列。
     */
    public List<ValidationIssue> validate(String sceneJson) {
        List<ValidationIssue> issues = new ArrayList<>();
        List<Map<String, Object>> elements = parseElements(sceneJson);
        if (elements == null) {
            return issues;
        }
        // 同向同标签重复连线：两条箭头绑同一对节点且标签相同 ——
        // 平行消息（时序图）标签必然不同，同标签重复几乎只会是模型抽风
        Map<String, String> seen = new LinkedHashMap<>();
        for (Map<String, Object> e : elements) {
            if (!"arrow".equals(e.get("type"))) {
                continue;
            }
            String s = bindingId(e, "startBinding");
            String t = bindingId(e, "endBinding");
            if (s == null || t == null) {
                continue;
            }
            String id = str(e.get("id"));
            String key = s + "->" + t + "|" + textOf(e);
            String prev = seen.get(key);
            if (prev != null) {
                issues.add(new ValidationIssue(
                        "elements[].id",
                        "箭头 " + id + " 与 " + prev
                                + " 是同向同标签的重复连线",
                        "删除重复的那条；若两条线含义不同，"
                                + "给它们写不同的标签"));
            } else {
                seen.put(key, id);
            }
        }
        return issues;
    }

    /**
     * 与参考结构比对：必含节点/连线是否齐全（连接正确性硬指标）。
     *
     * @return 语义报告（issue + 节点/连线 recall），供闸门与 D5 打分共用
     */
    public SemanticReport checkAgainstReference(String sceneJson,
                                                ReferenceSpec ref) {
        List<Map<String, Object>> elements = parseElements(sceneJson);
        if (elements == null || ref == null) {
            return SemanticReport.empty(ref);
        }
        // 1) 收集形状标签（箭头/文字线不算节点）
        Set<String> labels = new LinkedHashSet<>();
        Map<String, String> labelById = new LinkedHashMap<>();
        for (Map<String, Object> e : elements) {
            String type = str(e.get("type"));
            String id = str(e.get("id"));
            String label = textOf(e);
            if ("arrow".equals(type) || label.isEmpty()) {
                continue;
            }
            labels.add(label);
            labelById.put(id, label);
        }
        // 2) 收集有向连线（按起终点标签对）
        Set<String> edgeKeys = new HashSet<>();
        for (Map<String, Object> e : elements) {
            if (!"arrow".equals(e.get("type"))) {
                continue;
            }
            String s = bindingId(e, "startBinding");
            String t = bindingId(e, "endBinding");
            if (s == null || t == null) {
                continue;
            }
            String sl = labelById.get(s);
            String tl = labelById.get(t);
            if (sl != null && tl != null) {
                edgeKeys.add(sl + "->" + tl);
            }
        }
        // 3) 必含节点
        List<ValidationIssue> issues = new ArrayList<>();
        int foundNodes = 0;
        for (String want : ref.requiredNodes()) {
            if (labels.contains(norm(want))) {
                foundNodes++;
            } else {
                issues.add(new ValidationIssue(
                        "spec.nodes[].label",
                        "缺少要求的节点「" + want + "」",
                        "补上节点「" + want + "」再提交"));
            }
        }
        // 4) 必含连线
        int foundEdges = 0;
        for (ReferenceSpec.RequiredEdge want : ref.requiredEdges()) {
            String key = norm(want.fromLabel()) + "->" + norm(want.toLabel());
            if (edgeKeys.contains(key)) {
                foundEdges++;
            } else {
                issues.add(new ValidationIssue(
                        "spec.edges[]",
                        "缺少要求的连线「" + want.fromLabel()
                                + " → " + want.toLabel() + "」",
                        "补上这条连线（起点绑定「" + want.fromLabel()
                                + "」，终点绑定「" + want.toLabel() + "」）"));
            }
        }
        return new SemanticReport(List.copyOf(issues),
                foundNodes, ref.requiredNodes().size(),
                foundEdges, ref.requiredEdges().size());
    }

    // ---- 场景解析 ----

    private List<Map<String, Object>> parseElements(String sceneJson) {
        if (sceneJson == null || sceneJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    MAPPER.readTree(sceneJson);
            com.fasterxml.jackson.databind.JsonNode elements;
            if (root.isArray()) {
                elements = root;
            } else if (root.isObject() && root.has("elements")
                    && root.get("elements").isArray()) {
                elements = root.get("elements");
            } else {
                return null;
            }
            return MAPPER.convertValue(elements,
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static String bindingId(Map<String, Object> e, String key) {
        Object o = e.get(key);
        if (o instanceof Map<?, ?> m) {
            Object el = m.get("elementId");
            if (el instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private static String textOf(Map<?, ?> e) {
        Object t = e.get("text");
        if (t instanceof String s && !s.isEmpty()) {
            return s;
        }
        Object label = e.get("label");
        if (label instanceof Map<?, ?> lm) {
            Object lt = lm.get("text");
            if (lt instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }
}
