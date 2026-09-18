package io.github.nihaoljx.flowchart.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 几何保真校验（v2-37）单测。
 *
 * <p>判据的关键不在"能不能发现漂移"，而在**不误报**：
 * 用户的合法请求（挪一两个元素、整体平移、加元素、改颜色改文字）
 * 一旦被拦，代价是白跑一轮修正（30~100 秒）甚至拿不到结果。
 * 所以这里的用例一半在测"不该报的时候确实没报"。
 */
class SceneDriftTest {

    private final SceneDrift drift = new SceneDrift();

    private static String scene(String... elements) {
        return "{\"elements\":[" + String.join(",", elements) + "]}";
    }

    private static String rect(String id, double x, double y) {
        return "{\"id\":\"" + id + "\",\"type\":\"rectangle\","
                + "\"x\":" + x + ",\"y\":" + y
                + ",\"width\":100,\"height\":60}";
    }

    /** 四个节点横排：所有用例的"原图" */
    private static final String FOUR = scene(
            rect("n1", 0, 0), rect("n2", 200, 0),
            rect("n3", 400, 0), rect("n4", 600, 0));

    @Test
    @DisplayName("几何完全一致 → 通过")
    void identicalScenePasses() {
        assertNull(drift.check(FOUR, FOUR));
    }

    @Test
    @DisplayName("坐标只有亚像素级抄写差异（取整）→ 通过")
    void roundingTolerancePasses() {
        String same = scene(
                rect("n1", 0.4, 0.2), rect("n2", 200, 0),
                rect("n3", 400, 0), rect("n4", 600, 0));
        assertNull(drift.check(FOUR, same));
    }

    @Test
    @DisplayName("定向修改：只挪 1 个元素 → 通过（这是用户可能明确要求的）")
    void singleMovePasses() {
        String moved = scene(
                rect("n1", 0, 300), rect("n2", 200, 0),
                rect("n3", 400, 0), rect("n4", 600, 0));
        assertNull(drift.check(FOUR, moved));
    }

    @Test
    @DisplayName("改文字/颜色（几何不动）→ 通过")
    void labelOnlyChangePasses() {
        String relabeled = scene(
                "{\"id\":\"n1\",\"type\":\"rectangle\","
                        + "\"x\":0,\"y\":0,\"width\":100,\"height\":60,"
                        + "\"label\":{\"text\":\"开始\"},"
                        + "\"backgroundColor\":\"#ff0000\"}",
                rect("n2", 200, 0), rect("n3", 400, 0),
                rect("n4", 600, 0));
        assertNull(drift.check(FOUR, relabeled));
    }

    @Test
    @DisplayName("整图重排：保留 id 但坐标全换、相对排布变了 → 必须报")
    void fullRelayoutIsFlagged() {
        String relayouted = scene(
                rect("n1", 40, 320), rect("n2", 420, 120),
                rect("n3", 60, 20), rect("n4", 520, 600));
        ValidationIssue issue = drift.check(FOUR, relayouted);
        assertNotNull(issue, "整图重排必须被识别");
        assertEquals("elements[].x", issue.field());
    }

    @Test
    @DisplayName("整体平移（所有元素位移一致）→ 通过")
    void uniformTranslationPasses() {
        String shifted = scene(
                rect("n1", 200, 80), rect("n2", 400, 80),
                rect("n3", 600, 80), rect("n4", 800, 80));
        assertNull(drift.check(FOUR, shifted));
    }

    @Test
    @DisplayName("尺寸被统一改小（位置也跟着变）→ 报")
    void resizeIsFlagged() {
        String resized = scene(
                "{\"id\":\"n1\",\"type\":\"rectangle\","
                        + "\"x\":200,\"y\":80,\"width\":40,\"height\":20}",
                "{\"id\":\"n2\",\"type\":\"rectangle\","
                        + "\"x\":400,\"y\":80,\"width\":40,\"height\":20}",
                "{\"id\":\"n3\",\"type\":\"rectangle\","
                        + "\"x\":600,\"y\":80,\"width\":40,\"height\":20}",
                "{\"id\":\"n4\",\"type\":\"rectangle\","
                        + "\"x\":800,\"y\":80,\"width\":40,\"height\":20}");
        assertNotNull(drift.check(FOUR, resized));
    }

    @Test
    @DisplayName("可比元素不足 3 个 → 不判断（样本太小，误报代价更高）")
    void tooFewKeptElementsSkips() {
        String two = scene(rect("n1", 0, 0), rect("n2", 200, 0));
        String bothMoved = scene(rect("n1", 900, 900), rect("n2", 100, 800));
        assertNull(drift.check(two, bothMoved));
    }

    @Test
    @DisplayName("漂移占比不过半 → 不判断（局部改动）")
    void minorityDriftSkips() {
        String before = scene(
                rect("n1", 0, 0), rect("n2", 200, 0),
                rect("n3", 400, 0), rect("n4", 600, 0),
                rect("n5", 800, 0), rect("n6", 1000, 0));
        String after = scene(
                rect("n1", 999, 999), rect("n2", 111, 777),
                rect("n3", 400, 0), rect("n4", 600, 0),
                rect("n5", 800, 0), rect("n6", 1000, 0));
        assertNull(drift.check(before, after));
    }

    @Test
    @DisplayName("新增元素不算漂移（老元素一个没动）")
    void addedElementsDoNotCount() {
        String grown = scene(
                rect("n1", 0, 0), rect("n2", 200, 0),
                rect("n3", 400, 0), rect("n4", 600, 0),
                rect("n5", 0, 200), rect("n6", 200, 200));
        assertNull(drift.check(FOUR, grown));
    }

    @Test
    @DisplayName("被删元素不参与（删元素是用户可能明确要求的）")
    void deletedElementsDoNotCount() {
        String shrunk = scene(rect("n1", 0, 0), rect("n2", 200, 0));
        assertNull(drift.check(FOUR, shrunk));
    }

    @Test
    @DisplayName("两端绑定的箭头几何由端点推导，其 x/y 变化不算漂移")
    void boundArrowGeometryIsIgnored() {
        String before = scene(
                rect("n1", 0, 0), rect("n2", 200, 0),
                rect("n3", 400, 0), rect("n4", 600, 0),
                "{\"id\":\"a1\",\"type\":\"arrow\","
                        + "\"start\":{\"id\":\"n1\"},\"end\":{\"id\":\"n2\"}}");
        String after = scene(
                rect("n1", 0, 0), rect("n2", 200, 0),
                rect("n3", 400, 0), rect("n4", 600, 0),
                "{\"id\":\"a1\",\"type\":\"arrow\",\"x\":9999,\"y\":9999,"
                        + "\"points\":[[0,0],[10,10]],"
                        + "\"start\":{\"id\":\"n1\"},\"end\":{\"id\":\"n2\"}}");
        assertNull(drift.check(before, after));
    }

    @Test
    @DisplayName("自由线（points 路径）被整条改动 → 参与漂移判断")
    void freeLinePathChangeCounts() {
        String before = scene(
                rect("n1", 0, 0), rect("n2", 200, 0),
                "{\"id\":\"l1\",\"type\":\"line\",\"x\":0,\"y\":100,"
                        + "\"points\":[[0,0],[80,40]]}");
        String after = scene(
                rect("n1", 900, 900), rect("n2", 700, 300),
                "{\"id\":\"l1\",\"type\":\"line\",\"x\":50,\"y\":100,"
                        + "\"points\":[[0,0],[10,4]]}");
        assertNotNull(drift.check(before, after));
    }

    @Test
    @DisplayName("坏 JSON / 空场景 → 不报（交给 GraphValidator 报字段级问题）")
    void malformedInputsReturnNull() {
        assertNull(drift.check("{not json", FOUR));
        assertNull(drift.check(FOUR, "{not json"));
        assertNull(drift.check(scene(), FOUR));
        assertNull(drift.check(null, FOUR));
        assertNull(drift.check(FOUR, null));
        assertNull(drift.check("", FOUR));
    }
}
