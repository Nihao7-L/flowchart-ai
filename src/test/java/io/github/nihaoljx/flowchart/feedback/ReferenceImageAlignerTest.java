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
 * matches 判定（无参考/低分都不算匹配）。
 *
 * <p>2026-10-16 重写：所有入口都带需求文本；对齐第一步是判形态，
 * 无参考 ⇒ UNVERIFIABLE（不给分），绝不再用「覆盖率封顶 0.6」冒充。
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

    private static final String AUTH_REQUEST = "画一张 React + AWS 的认证架构流程图";

    private static final String GOOD = scene(
            node("a", "登录页", 0), node("b", "认证服务", 400),
            arrow("e1", "a", "b"));

    @Test
    @DisplayName("直接对齐：结构全命中 → VERIFIED 且 matches 通过")
    void directAlignMatches() {
        FidelityReport r = aligner.align(AUTH_REQUEST, GOOD, AUTH_REF);
        assertEquals(FidelityReport.Verdict.VERIFIED, r.verdict());
        assertTrue(aligner.matches(r, 0.9));
    }

    @Test
    @DisplayName("提取器路径：从草图提取参考结构 → 与直接对齐结论一致")
    void extractorPath() {
        SketchToSpecExtractor fake = (image, hint) -> AUTH_REF;
        FidelityReport viaSketch =
                aligner.align(AUTH_REQUEST, GOOD, new byte[] {1}, fake);
        FidelityReport direct = aligner.align(AUTH_REQUEST, GOOD, AUTH_REF);
        assertEquals(direct.verdict(), viaSketch.verdict());
        assertEquals(direct.fidelity(), viaSketch.fidelity());
        assertTrue(aligner.matches(viaSketch, 0.9));
    }

    @Test
    @DisplayName("提取失败/无提取器 → 降级为无参考（UNVERIFIABLE、不给分、不抛异常）")
    void extractionFailureDegrades() {
        SketchToSpecExtractor broken = (image, hint) -> {
            throw new RuntimeException("vision backend down");
        };
        FidelityReport r1 = aligner.align(AUTH_REQUEST, GOOD, new byte[] {1}, broken);
        assertEquals(FidelityReport.Verdict.UNVERIFIABLE, r1.verdict());
        assertFalse(r1.scored());
        FidelityReport r2 = aligner.align(AUTH_REQUEST, GOOD, new byte[] {1}, null);
        assertEquals(FidelityReport.Verdict.UNVERIFIABLE, r2.verdict());
        assertFalse(aligner.matches(r2, 0.9), "不给分不算匹配");
    }

    @Test
    @DisplayName("matches：结构不达标（缺边）→ VERIFIED 但低分，判不匹配")
    void highConfidenceButLowScoreFails() {
        String missing = scene(node("a", "登录页", 0));
        FidelityReport r = aligner.align(AUTH_REQUEST, missing, AUTH_REF);
        assertEquals(FidelityReport.Verdict.VERIFIED, r.verdict());
        assertFalse(aligner.matches(r, 0.9), "低分即不匹配");
    }
}
