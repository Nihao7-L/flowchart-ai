package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.graph.validate.ReferenceSpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构优先保真度量（D5）单测：有参考高分 / 无参考封顶 /
 * 猫式孤立形状被打下来 / 连线端点双格式兼容。
 */
class FidelityScorerTest {

    private final FidelityScorer scorer = new FidelityScorer();

    private static Map<String, Object> node(String id, String label) {
        return node(id, label, 0);
    }

    private static Map<String, Object> node(String id, String label, double x) {
        return Map.of("id", id, "type", "rectangle",
                "x", x, "y", 0.0, "width", 120, "height", 60,
                "label", Map.of("text", label));
    }

    private static Map<String, Object> arrow(String id, String from, String to) {
        return Map.of("id", id, "type", "arrow",
                "start", Map.of("id", from),
                "end", Map.of("id", to));
    }

    private static String scene(Object... elements) {
        StringBuilder sb = new StringBuilder("{\"elements\":[");
        for (int i = 0; i < elements.length; i++) {
            try {
                sb.append(i > 0 ? "," : "").append(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString(elements[i]));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("有参考且全命中 → 结构 1.0、总分 1.0、置信 HIGH")
    void fullReferenceMatch() {
        String json = scene(node("a", "开始", 0), node("b", "结束", 400),
                arrow("e1", "a", "b"));
        FidelityReport r = scorer.score(json, ReferenceSpec.of(
                List.of("开始", "结束"),
                List.of(new ReferenceSpec.RequiredEdge("开始", "结束"))));
        assertEquals(1.0, r.structural(), 1e-9);
        assertEquals(1.0, r.overall(), 1e-9);
        assertEquals(FidelityReport.Confidence.HIGH, r.confidence());
        assertTrue(r.verifiable());
    }

    @Test
    @DisplayName("无参考 + 全连通 → 封顶 0.6、置信 LOW（语义不可验证）")
    void noReferenceCapped() {
        String json = scene(node("a", "开始", 0), node("b", "结束", 400),
                arrow("e1", "a", "b"));
        FidelityReport r = scorer.score(json, null);
        assertEquals(DiagramScorer.NO_REF_CAP, r.overall(), 1e-9);
        assertEquals(FidelityReport.Confidence.LOW, r.confidence());
    }

    @Test
    @DisplayName("猫式场景（半数形状零连线）→ 覆盖率拉低总分，绝不满分")
    void disconnectedShapesPenalized() {
        // b 无任何连线（覆盖率 1/2）且无参考
        String json = scene(node("a", "开始"), node("b", "孤岛"),
                arrow("e1", "a", "a2"));
        // 注：a2 未定义会让连线悬空，用「两个形状只有一条边」更直白：
        String json2 = scene(node("a", "开始", 0), node("b", "孤岛", 400),
                node("c", "终点", 800));
        FidelityReport r = scorer.score(json2, null);
        assertTrue(r.structural() < 1.0,
                "孤岛形状必须拉低结构分: " + r.structural());
        assertTrue(r.overall() <= DiagramScorer.NO_REF_CAP + 1e-9);
    }

    @Test
    @DisplayName("契约格式 start:{id} 与渲染格式 startBinding 均可解析端点")
    void bothEndpointFormats() {
        String contract = scene(node("a", "甲", 0), node("b", "乙", 400),
                arrow("e1", "a", "b"));
        String render = scene(node("a", "甲", 0), node("b", "乙", 400),
                Map.of("id", "e1", "type", "arrow",
                        "startBinding", Map.of("elementId", "a"),
                        "endBinding", Map.of("elementId", "b")));
        FidelityReport r1 = scorer.score(contract, null);
        FidelityReport r2 = scorer.score(render, null);
        assertEquals(r1.structural(), r2.structural(), 1e-9);
        assertEquals(1.0, r1.structural(), 1e-9);
    }

    @Test
    @DisplayName("有参考但缺边 + 几何问题多 → 低分且诚实")
    void missingEdgeScoresLow() {
        String json = scene(node("a", "开始", 0), node("b", "处理", 400),
                arrow("e1", "a", "b"));
        FidelityReport r = scorer.score(json, ReferenceSpec.of(
                List.of("开始", "处理", "结束"),
                List.of(new ReferenceSpec.RequiredEdge("处理", "结束"))));
        assertTrue(r.structural() < 0.5,
                "缺节点缺边应拉低结构分: " + r.structural());
        assertTrue(r.overall() < 0.6, "缺节点缺边应显著低分: " + r.overall());
        assertEquals(FidelityReport.Confidence.HIGH, r.confidence());
    }
}
