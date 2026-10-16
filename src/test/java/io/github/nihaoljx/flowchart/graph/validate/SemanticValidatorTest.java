package io.github.nihaoljx.flowchart.graph.validate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义校验器（D3）单测：场景内保守检查（重复连线）+ 参考结构比对
 * （必含节点/连线 recall 与点名化 issue）+ 安全性（坏 JSON / 空输入）。
 */
class SemanticValidatorTest {

    private final SemanticValidator validator = new SemanticValidator();

    private static Map<String, Object> node(String id, String label) {
        return Map.of("id", id, "type", "rectangle",
                "x", 0, "y", 0, "width", 120, "height", 60,
                "label", Map.of("text", label));
    }

    private static Map<String, Object> arrow(String id, String from, String to) {
        return Map.of("id", id, "type", "arrow",
                "startBinding", Map.of("elementId", from),
                "endBinding", Map.of("elementId", to),
                "label", Map.of("text", "确认"));
    }

    private static String scene(Object... elements) {
        StringBuilder sb = new StringBuilder("{\"elements\":[");
        for (int i = 0; i < elements.length; i++) {
            sb.append(i > 0 ? "," : "").append(JsonUtil.json(elements[i]));
        }
        return sb.append("]}").toString();
    }

    /** 简单 JSON 序列化助手（避免测试引 Jackson 全套） */
    static final class JsonUtil {
        private static final com.fasterxml.jackson.databind.ObjectMapper M =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static String json(Object o) {
            try {
                return M.writeValueAsString(o);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    @DisplayName("同向同标签重复连线 → 报重复 issue")
    void duplicateEdgeDetected() {
        String json = scene(
                node("a", "开始"), node("b", "结束"),
                arrow("e1", "a", "b"), arrow("e2", "a", "b"));
        List<ValidationIssue> issues = validator.validate(json);
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).reason().contains("重复连线"));
    }

    @Test
    @DisplayName("同向但标签不同（平行消息）→ 不算重复")
    void parallelEdgesWithDifferentLabelsPass() {
        Map<String, Object> e1 = Map.of("id", "e1", "type", "arrow",
                "startBinding", Map.of("elementId", "a"),
                "endBinding", Map.of("elementId", "b"),
                "label", Map.of("text", "下单"));
        Map<String, Object> e2 = Map.of("id", "e2", "type", "arrow",
                "startBinding", Map.of("elementId", "a"),
                "endBinding", Map.of("elementId", "b"),
                "label", Map.of("text", "撤单"));
        String json = scene(node("a", "用户"), node("b", "系统"), e1, e2);
        assertTrue(validator.validate(json).isEmpty());
    }

    @Test
    @DisplayName("无重复的干净场景 → 零 issue")
    void cleanScenePasses() {
        String json = scene(
                node("a", "开始"), node("b", "结束"),
                arrow("e1", "a", "b"));
        assertTrue(validator.validate(json).isEmpty());
    }

    @Test
    @DisplayName("参考比对：缺节点、缺连线都点名报出")
    void referenceComparisonNamesMissing() {
        String json = scene(node("a", "开始"), node("b", "处理"),
                arrow("e1", "a", "b"));
        ReferenceSpec ref = ReferenceSpec.of(
                List.of("开始", "处理", "结束"),
                List.of(new ReferenceSpec.RequiredEdge("开始", "处理"),
                        new ReferenceSpec.RequiredEdge("处理", "结束")));
        SemanticReport report = validator.checkAgainstReference(json, ref);
        assertEquals(2, report.issues().size());
        assertTrue(report.issues().stream()
                .anyMatch(i -> i.reason().contains("「结束」")));
        assertTrue(report.issues().stream()
                .anyMatch(i -> i.reason().contains("处理 → 结束")));
        assertEquals(2, report.foundNodes());
        assertEquals(1, report.foundEdges());
        assertEquals(2.0 / 3, report.nodeRecall(), 1e-9);
        assertEquals(0.5, report.edgeRecall(), 1e-9);
    }

    @Test
    @DisplayName("参考比对：全命中 → 零 issue、recall 全 1")
    void referenceFullyMatched() {
        String json = scene(node("a", "开始"), node("b", "结束"),
                arrow("e1", "a", "b"));
        ReferenceSpec ref = ReferenceSpec.of(
                List.of("开始", "结束"),
                List.of(new ReferenceSpec.RequiredEdge("开始", "结束")));
        SemanticReport report = validator.checkAgainstReference(json, ref);
        assertTrue(report.issues().isEmpty());
        assertEquals(1.0, report.nodeRecall());
        assertEquals(1.0, report.edgeRecall());
    }

    @Test
    @DisplayName("坏 JSON / 空输入 → 安全返回空（不打断生成）")
    void badInputSafe() {
        assertTrue(validator.validate("not json").isEmpty());
        assertTrue(validator.validate(null).isEmpty());
        SemanticReport r = validator.checkAgainstReference("bad", null);
        assertEquals(0, r.issues().size());
        assertEquals(1.0, r.nodeRecall());
        assertEquals(1.0, r.edgeRecall());
    }
}
