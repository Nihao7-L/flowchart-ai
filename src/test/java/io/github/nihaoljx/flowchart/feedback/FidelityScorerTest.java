package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.graph.validate.ReferenceSpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构优先保真度量（D5）单测。
 *
 * <p>2026-10-16 重写后核心契约：
 * ① 有参考且全命中 → VERIFIED + 分数 1.0；
 * ② 无参考 → UNVERIFIABLE，fidelity == null（不给分，绝不拿覆盖率顶替）；
 * ③ 绘画/构图类需求（NON_DIAGRAM）交图表结构且未声明边界 → FORM_MISMATCH + 0.0；
 * ④ 如实带【AI 备注】拒绝 → REFUSED，fidelity == null（不给分但可信）。
 *
 * <p>其中 ③ 是对「猫事故」的回归断言：用 E2E 实测产出的猫零件图
 * （15 椭圆/菱形 + 9 箭头）喂进去，结论必须是 0 分 —— 旧版错给 0.6。
 */
class FidelityScorerTest {

    private final FidelityScorer scorer = new FidelityScorer();

    /** 事故现场的需求原文（与 ref_cat_ours_2026-10-16.json 配套） */
    private static final String CAT_REQUEST =
            "画一只可爱的卡通猫，用最简单的几何形状拼：一个椭圆当身体、"
            + "一个圆当脑袋、两个三角形当耳朵、两个小圆当眼睛、一个小三角当鼻子、"
            + "两条弧线当胡须、一条卷曲线当尾巴，最后在旁边写一行字 Meow~ ♥。"
            + "整体配色用暖色（浅黄身体、粉色耳朵内侧）。";

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

    private static Map<String, Object> textNote(String id, String text) {
        return Map.of("id", id, "type", "text",
                "x", 0.0, "y", 0.0, "text", text);
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

    private static String loadResource(String path) {
        try (InputStream in = FidelityScorerTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "缺失测试资源: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final ReferenceSpec AUTH_REF = ReferenceSpec.of(
            List.of("登录页", "认证服务"),
            List.of(new ReferenceSpec.RequiredEdge("登录页", "认证服务")));

    private static final String AUTH_REQUEST = "画一张 React + AWS 的认证架构流程图";

    @Test
    @DisplayName("有参考且全命中 → 结构 1.0、VERIFIED、verifiable")
    void fullReferenceMatch() {
        String json = scene(node("a", "登录页", 0), node("b", "认证服务", 400),
                arrow("e1", "a", "b"));
        FidelityReport r = scorer.score(AUTH_REQUEST, json, AUTH_REF);
        assertEquals(FidelityReport.Verdict.VERIFIED, r.verdict());
        assertEquals(1.0, r.fidelity(), 1e-9);
        assertTrue(r.verifiable());
    }

    @Test
    @DisplayName("无参考 → UNVERIFIABLE、fidelity==null（不给分，不封顶 0.6）")
    void noReferenceUnverifiable() {
        String json = scene(node("a", "登录页", 0), node("b", "认证服务", 400),
                arrow("e1", "a", "b"));
        FidelityReport r = scorer.score(AUTH_REQUEST, json, null);
        assertEquals(FidelityReport.Verdict.UNVERIFIABLE, r.verdict());
        assertNull(r.fidelity());
        assertFalse(r.scored());
        assertFalse(r.verifiable());
    }

    @Test
    @DisplayName("【回归·猫事故】猫零件图对 ref_cat → FORM_MISMATCH 且 0.0 分")
    void catSceneFormMismatchZero() {
        String catScene = loadResource("/bench/ref_cat_ours_2026-10-16.json");
        FidelityReport r = scorer.score(CAT_REQUEST, catScene, null);
        assertEquals(FidelityReport.Verdict.FORM_MISMATCH, r.verdict(),
                "绘画需求交出节点连线图表结构且未声明边界，必须判 0 分");
        assertEquals(0.0, r.fidelity(), 1e-9,
                "用户诉求：这种'猫'该打 0 分，不是 0.6");
        assertTrue(r.scored());
        assertFalse(r.verifiable());
    }

    @Test
    @DisplayName("【回归·猫事故】同需求但输出带【AI 备注】如实拒绝 → REFUSED、不给分")
    void catSceneRefusedWhenHonest() {
        String honest = scene(
                textNote("lim", "【AI 备注】我是图解助手，不画写实/卡通形象…"),
                node("a", "建议：猫的生命周期流程图", 0));
        FidelityReport r = scorer.score(CAT_REQUEST, honest, null);
        assertEquals(FidelityReport.Verdict.REFUSED, r.verdict());
        assertNull(r.fidelity());
        assertFalse(r.scored());
    }

    @Test
    @DisplayName("契约格式 start:{id} 与渲染格式 startBinding 均可解析端点")
    void bothEndpointFormats() {
        String contract = scene(node("a", "登录页", 0), node("b", "认证服务", 400),
                arrow("e1", "a", "b"));
        String render = scene(node("a", "登录页", 0), node("b", "认证服务", 400),
                Map.of("id", "e1", "type", "arrow",
                        "startBinding", Map.of("elementId", "a"),
                        "endBinding", Map.of("elementId", "b")));
        FidelityReport r1 = scorer.score(AUTH_REQUEST, contract, null);
        FidelityReport r2 = scorer.score(AUTH_REQUEST, render, null);
        // 无参考 ⇒ 两者都是 UNVERIFIABLE、都不给分，且结论一致
        assertEquals(r1.verdict(), r2.verdict());
        assertEquals(r1.display(), r2.display());
        assertFalse(r1.scored());
    }

    @Test
    @DisplayName("有参考但缺节点缺边 → 低分、VERIFIED、matches 不通过")
    void missingEdgeScoresLow() {
        String json = scene(node("a", "登录页", 0));
        FidelityReport r = scorer.score(AUTH_REQUEST, json, AUTH_REF);
        assertEquals(FidelityReport.Verdict.VERIFIED, r.verdict());
        assertNotNull(r.fidelity());
        assertTrue(r.fidelity() < 0.6,
                "缺节点缺边应显著低分: " + r.fidelity());
        assertFalse(new ReferenceImageAligner().matches(r, 0.9));
    }
}
