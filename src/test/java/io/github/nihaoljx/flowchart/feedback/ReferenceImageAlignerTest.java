package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.graph.validate.ReferenceSpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D8 参考图结构对齐器单测：直接对齐 / 提取器路径 / 提取失败降级 /
 * matches 判定（低置信或低分都算不匹配）。
 */
class ReferenceImageAlignerTest {

    private final ReferenceImageAligner aligner = new ReferenceImageAligner();

    private static Map<String, Object> node(String id, String label, double x) {
        return Map.of("id", id, "type", "rectangle",
                "x", x, "y", 0.0, "width", 120, "height", 60,
                "label", Map.of("text", label));
    }

    private static Map<String, Object> arrow(String id, String from, String to) {
        return Map.of("id", id, "type", "arrow",
                "start", Map.of("id", from), "end", Map.of("id", to));
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

    private static final ReferenceSpec AUTH_REF = ReferenceSpec.of(
            List.of("登录页", "认证服务"),
            List.of(new ReferenceSpec.RequiredEdge("登录页", "认证服务")));

    private static final String GOOD = scene(
            node("a", "登录页", 0), node("b", "认证服务", 400),
            arrow("e1", "a", "b"));

    @Test
    @DisplayName("直接对齐：结构全命中 → HIGH 且 matches 通过")
    void directAlignMatches() {
        FidelityReport r = aligner.align(GOOD, AUTH_REF);
        assertEquals(FidelityReport.Confidence.HIGH, r.confidence());
        assertTrue(aligner.matches(r, 0.9));
    }

    @Test
    @DisplayName("提取器路径：从草图提取参考结构 → 同直接对齐")
    void extractorPath() {
        SketchToSpecExtractor fake = (image, hint) -> AUTH_REF;
        FidelityReport viaSketch = aligner.align(GOOD, new byte[] {1}, fake);
        FidelityReport direct = aligner.align(GOOD, AUTH_REF);
        assertEquals(direct.overall(), viaSketch.overall(), 1e-9);
        assertTrue(aligner.matches(viaSketch, 0.9));
    }

    @Test
    @DisplayName("提取失败/无提取器 → 降级为无参考（LOW、封顶、不抛异常）")
    void extractionFailureDegrades() {
        SketchToSpecExtractor broken = (image, hint) -> {
            throw new RuntimeException("vision backend down");
        };
        FidelityReport r1 = aligner.align(GOOD, new byte[] {1}, broken);
        assertEquals(FidelityReport.Confidence.LOW, r1.confidence());
        assertTrue(r1.overall() <= DiagramScorer.NO_REF_CAP + 1e-9);
        FidelityReport r2 = aligner.align(GOOD, new byte[] {1}, null);
        assertEquals(FidelityReport.Confidence.LOW, r2.confidence());
        assertFalse(aligner.matches(r2, 0.9), "低置信不算匹配");
    }

    @Test
    @DisplayName("matches：结构不达标（缺边）→ HIGH 但低分，判不匹配")
    void highConfidenceButLowScoreFails() {
        String missing = scene(node("a", "登录页", 0));
        FidelityReport r = aligner.align(missing, AUTH_REF);
        assertEquals(FidelityReport.Confidence.HIGH, r.confidence());
        assertFalse(aligner.matches(r, 0.9), "低分即不匹配");
    }
}
