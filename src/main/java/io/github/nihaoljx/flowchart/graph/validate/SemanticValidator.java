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
            String s = edgeEndpoint(e, "start");
            String t = edgeEndpoint(e, "end");
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
            String s = edgeEndpoint(e, "start");
            String t = edgeEndpoint(e, "end");
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

    /**
     * 形状覆盖率（0..1）：有连线挂靠、或是 frame 容器、或是文字标注的
     * 形状占全部形状的比例。
     *
     * <p><b>它衡量的是「图内部自洽」，不是「符合需求」——绝不可当保真分用。</b>
     * 2026-10-16 ref_cat 事故的错误就在这里：猫被画成 15 个椭圆由 9 条箭头
     * 串起来，覆盖率接近 1，于是这份"零件关系图"拿到了高分，而它离
     * 「一只猫」的距离是零分。凡是拿"内部自洽"当"符合要求"的度量都会
     * 奖励"自说自话的完整"（自洽的废话也是自洽的）。因此本方法留在
     * 这里只作**诊断信息**，{@code FidelityScorer} 不再引用它打分。
     *
     * <p>读不懂的场景保守返回 1（读不懂不惩罚，坏 JSON 由 parseGate 报）。
     */
    public double shapeCoverage(String sceneJson) {
        List<Map<String, Object>> elements = parseElements(sceneJson);
        if (elements == null) {
            return 1.0;
        }
        Set<String> shapeIds = new LinkedHashSet<>();
        Set<String> covered = new HashSet<>();
        for (Map<String, Object> e : elements) {
            String type = str(e.get("type"));
            String id = str(e.get("id"));
            if (id.isEmpty()) {
                continue;
            }
            if ("text".equals(type) || "frame".equals(type)) {
                // 文字标注与容器不要求连线，视为已覆盖；
                // frame 的 children 被 frame 统辖，也算覆盖
                covered.add(id);
                Object children = e.get("children");
                if (children instanceof List<?> l) {
                    for (Object c : l) {
                        if (c instanceof String s) {
                            covered.add(s);
                        }
                    }
                }
                continue;
            }
            if (!"arrow".equals(type)) {
                shapeIds.add(id);
            }
        }
        for (Map<String, Object> e : elements) {
            if (!"arrow".equals(e.get("type"))) {
                continue;
            }
            String s = edgeEndpoint(e, "start");
            String t = edgeEndpoint(e, "end");
            if (s != null) {
                covered.add(s);
            }
            if (t != null) {
                covered.add(t);
            }
        }
        if (shapeIds.isEmpty()) {
            return 1.0;
        }
        long hit = shapeIds.stream().filter(covered::contains).count();
        return (double) hit / shapeIds.size();
    }

    /** 能力边界说明的标记：输出里带它 = 系统如实说明"这个我画不了" */
    public static final String LIMITATION_MARKER = "【AI 备注】";

    /**
     * 场景里是否带了「能力边界说明」（【AI 备注】）。
     *
     * <p>用途是把「如实拒绝」与「交一张假图冒充」区分开：两者对参考图
     * 的保真都是零，但前者是可信行为（用户看到的是明确的"做不到"），
     * 后者是产品事故（用户以为拿到了图）。度量报告据此给不同的结论。
     *
     * @param sceneJson 场景 JSON；解析失败返回 false
     */
    public static boolean declaresLimitation(String sceneJson) {
        List<Map<String, Object>> elements = parseElements(sceneJson);
        if (elements == null) {
            return false;
        }
        for (Map<String, Object> e : elements) {
            if (textOf(e).contains(LIMITATION_MARKER)) {
                return true;
            }
        }
        return false;
    }

    // ---- 场景解析 ----

    /**
     * 取箭头端点绑定的元素 id。兼容两种格式：契约层的
     * {@code start:{id}} / {@code end:{id}} 与 Excalidraw 渲染层的
     * {@code startBinding:{elementId}} / {@code endBinding:{elementId}}
     * （两者口径不同是历史事实，量具必须同时认）。
     */
    static String edgeEndpoint(Map<String, Object> e, String side) {
        Object o = e.get(side);
        if (o instanceof Map<?, ?> m) {
            Object id = m.get("id");
            if (id instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        Object o2 = e.get(side + "Binding");
        if (o2 instanceof Map<?, ?> m) {
            Object el = m.get("elementId");
            if (el instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /** 解析场景 elements（兼容信封与裸数组）；失败返回 null。供度量器复用 */
    public static List<Map<String, Object>> parseElements(String sceneJson) {
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
