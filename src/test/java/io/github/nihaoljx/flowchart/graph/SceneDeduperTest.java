package io.github.nihaoljx.flowchart.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连线去重单元测试（v2-34）
 *
 * <p>背景：用户删掉一条线、自己手绘一条补上之后，后端模型里那条旧线
 * 还在（删除当时没有回写通道），而用户手绘的线又不在模型里 ——
 * 下一轮 LLM 把旧线原样输出，与用户手绘的那条并存，同一处出现两条线
 * （2026-09-16 用户实测：「多添加线的地方是我删除一次然后又添加上去的」）。
 *
 * <p>测的重点是"该删的删、不该删的一个字都不许动"：
 * 误删比漏删难发现得多 —— 用户会去数"多出来的线"，但不会去数"少了几条"。
 */
class SceneDeduperTest {

    private final SceneDeduper deduper =
            new SceneDeduper();

    private static String scene(String... elements) {
        return "{\"elements\":["
                + String.join(",", elements) + "]}";
    }

    private static String shape(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"rectangle\","
                + "\"x\":0,\"y\":0,\"width\":100,\"height\":60}";
    }

    private static String boundArrow(
            String id, String from, String to) {
        return "{\"id\":\"" + id + "\",\"type\":\"arrow\","
                + "\"start\":{\"id\":\"" + from + "\"},"
                + "\"end\":{\"id\":\"" + to + "\"}}";
    }

    private static String labeledBoundArrow(
            String id, String from, String to,
            String label) {
        return "{\"id\":\"" + id + "\",\"type\":\"arrow\","
                + "\"label\":{\"text\":\"" + label + "\"},"
                + "\"start\":{\"id\":\"" + from + "\"},"
                + "\"end\":{\"id\":\"" + to + "\"}}";
    }

    private static String freeArrow(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"arrow\","
                + "\"x\":100,\"y\":50,"
                + "\"points\":[[0,0],[200,0]]}";
    }

    @Test
    @DisplayName("输出内重复：同向同端点的两条绑定箭头只留第一条")
    void dropsDuplicateInsideOutput() {
        String json = scene(shape("n1"), shape("n2"),
                boundArrow("a1", "n1", "n2"),
                boundArrow("a2", "n1", "n2"));

        String out = deduper.dedup(json, null);

        assertTrue(out.contains("\"a1\""),
                "第一条应保留: " + out);
        assertFalse(out.contains("\"a2\""),
                "第二条同端点箭头应被丢弃: " + out);
    }

    @Test
    @DisplayName("不误删「是 / 否」分支：同端点但 label 不同，两条都留")
    void keepsLabeledBranches() {
        String json = scene(shape("n1"), shape("n2"),
                labeledBoundArrow("a1", "n1", "n2", "是"),
                labeledBoundArrow("a2", "n1", "n2", "否"));

        String out = deduper.dedup(json, null);

        assertTrue(out.contains("\"a1\"")
                        && out.contains("\"a2\""),
                "分支端点相同属业务需要，不能删: " + out);
    }

    @Test
    @DisplayName("跨集合：与用户手绘连线重复的无标签箭头被丢弃")
    void dropsLinkDuplicatingUserDrawn() {
        List<Map<String, Object>> userElements = List.of(
                Map.of("id", "u1", "type", "arrow",
                        "startId", "n1", "endId", "n2"));

        String out = deduper.dedup(
                scene(shape("n1"), shape("n2"),
                        boundArrow("a1", "n1", "n2")),
                userElements);

        assertFalse(out.contains("\"a1\""),
                "用户已经画了这条线，LLM 再画一条就是重复: "
                        + out);
    }

    @Test
    @DisplayName("跨集合：带 label 的箭头不删（可能是用户要的分支语义）")
    void keepsLabeledLinkAgainstUserDrawn() {
        List<Map<String, Object>> userElements = List.of(
                Map.of("id", "u1", "type", "arrow",
                        "startId", "n1", "endId", "n2"));

        String out = deduper.dedup(
                scene(shape("n1"), shape("n2"),
                        labeledBoundArrow(
                                "a1", "n1", "n2", "是")),
                userElements);

        assertTrue(out.contains("\"a1\""),
                "带 label 的线语义可能是分支，不能当重复删掉: "
                        + out);
    }

    @Test
    @DisplayName("方向敏感：B→A 与 A→B 不是同一条，不删")
    void doesNotMergeReversedDirection() {
        List<Map<String, Object>> userElements = List.of(
                Map.of("id", "u1", "type", "arrow",
                        "startId", "n2", "endId", "n1"));

        String out = deduper.dedup(
                scene(shape("n1"), shape("n2"),
                        boundArrow("a1", "n1", "n2")),
                userElements);

        assertTrue(out.contains("\"a1\""),
                "双向箭头是合法表达，方向不同不能互删: " + out);
    }

    @Test
    @DisplayName("自由连线：端点落在容差内时判为重复")
    void dropsFreeLinkWithSameEndpoints() {
        // AI 的端点 = (100,50) → (300,50)；用户手绘的差 5px
        List<Map<String, Object>> userElements = List.of(
                Map.of("id", "u1", "type", "arrow",
                        "x", 105, "y", 52,
                        "points", List.of(
                                List.of(0, 0),
                                List.of(200, 0))));

        String out = deduper.dedup(
                scene(shape("n1"), shape("n2"),
                        freeArrow("a1")),
                userElements);

        assertFalse(out.contains("\"a1\""),
                "端点差在容差内应视为同一条: " + out);
    }

    @Test
    @DisplayName("自由连线：端点相差很远时两条都留")
    void keepsFreeLinkWhenFarApart() {
        List<Map<String, Object>> userElements = List.of(
                Map.of("id", "u1", "type", "arrow",
                        "x", 100, "y", 400,
                        "points", List.of(
                                List.of(0, 0),
                                List.of(200, 0))));

        String out = deduper.dedup(
                scene(shape("n1"), shape("n2"),
                        freeArrow("a1")),
                userElements);

        assertTrue(out.contains("\"a1\""),
                "位置不同的两条线不是重复: " + out);
    }

    @Test
    @DisplayName("图形与文字一律不动；无重复时返回原字符串实例")
    void leavesNonLinksUntouched() {
        String text = "{\"id\":\"t1\",\"type\":\"text\","
                + "\"text\":\"开始\",\"x\":0,\"y\":0}";
        String json = scene(shape("n1"), shape("n2"), text);

        // 同一实例：不做无谓的重排与格式化，也让调用方能靠
        // "字节不变"判断到底改没改过
        assertSame(json, deduper.dedup(json, null));
        assertSame(json, deduper.dedup(json, List.of()));
    }

    @Test
    @DisplayName("坏输入一律原样返回，绝不让去重变成新的故障点")
    void survivesMalformedInput() {
        assertSame("not json",
                deduper.dedup("not json", null));
        assertSame("{\"elements\":{}}",
                deduper.dedup("{\"elements\":{}}", null));
        assertSame("{}", deduper.dedup("{}", null));
    }
}
