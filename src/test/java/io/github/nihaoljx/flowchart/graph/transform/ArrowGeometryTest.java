package io.github.nihaoljx.flowchart.graph.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ArrowGeometry.normalize 的单元测试。
 *
 * 它只负责「JSON 层封装」：把每根 arrow 的 points 交给
 * Polyline 正交化后写回。路线级铁律（全正交 / 最少拐弯）由
 * {@link io.github.nihaoljx.flowchart.graph.layout.PolylineTest} 锁死，
 * 这里只验证：选对了元素（只动 arrow）、保住了别的字段、没坏就不动。
 */
class ArrowGeometryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode points(String json, int idx)
            throws Exception {
        return MAPPER.readTree(json).get("elements").get(idx)
                .get("points");
    }

    /** 断言某个元素的 points 全正交：相邻两点必有一轴坐标相等 */
    private static void assertOrthogonal(JsonNode pts) {
        for (int i = 1; i < pts.size(); i++) {
            double dx = Math.abs(pts.get(i).get(0).asDouble()
                    - pts.get(i - 1).get(0).asDouble());
            double dy = Math.abs(pts.get(i).get(1).asDouble()
                    - pts.get(i - 1).get(1).asDouble());
            assertTrue(dx < 1e-9 || dy < 1e-9,
                    "段 " + (i - 1) + " 非正交: "
                            + pts.get(i - 1) + " -> " + pts.get(i));
        }
    }

    @Test
    @DisplayName("带斜段的绑定箭头 → points 全正交，start/end 绑定原样保留")
    void boundDiagonalArrowOrthogonalized() throws Exception {
        String in = "{\"elements\":["
                + "{\"id\":\"n1\",\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":50},"
                + "{\"id\":\"n2\",\"type\":\"rectangle\",\"x\":300,\"y\":-300,"
                + "\"width\":100,\"height\":50},"
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"start\":{\"id\":\"n1\"},\"end\":{\"id\":\"n2\"},"
                + "\"x\":50,\"y\":25,"
                + "\"points\":[[0,0],[20,10],[280,10],[280,-290],"
                + "[300,-290],[320,-300]]}]}";
        String out = ArrowGeometry.normalize(in);
        JsonNode pts = points(out, 2);
        assertOrthogonal(pts);
        // 首点保持 [0,0]（Excalidraw 约定，前端据此重算吸附）
        assertEquals(0, pts.get(0).get(0).asInt());
        assertEquals(0, pts.get(0).get(1).asInt());
        // 绑定关系一个字都没动
        JsonNode arrow = MAPPER.readTree(out).get("elements").get(2);
        assertEquals("n1", arrow.get("start").get("id").asText());
        assertEquals("n2", arrow.get("end").get("id").asText());
    }

    @Test
    @DisplayName("已正交的箭头（含 ELK 直线边 3 点共线路由）→ 原样返回、不塌点")
    void alreadyOrthogonalUnchanged() {
        // 直线边 3 点共线路由是 submit_spec（ELK）故意保留的不变量
        // （首/中/末，前端据此推导端点）—— 无斜线就必须原样返回，
        // 绝不塌成 2 点（否则反噬布局引擎，SubmitSpecToolTest 会红）。
        String in = "{\"elements\":["
                + "{\"id\":\"a1\",\"type\":\"arrow\",\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[50,0],[100,0]]}]}";
        assertSame(in, ArrowGeometry.normalize(in));
    }

    @Test
    @DisplayName("整数坐标保持整数（不序列化成 [0.0,0.0]）")
    void integersStayIntegers() throws Exception {
        String in = "{\"elements\":["
                + "{\"id\":\"a1\",\"type\":\"arrow\",\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[100,80]]}]}";
        String out = ArrowGeometry.normalize(in);
        assertFalse(out.contains(".0"),
                "新坐标应为整数形态，实际: " + out);
        assertOrthogonal(points(out, 0));
    }

    @Test
    @DisplayName("line / 非 arrow 的斜线 points → 一律不碰")
    void nonArrowUntouched() {
        String in = "{\"elements\":["
                + "{\"id\":\"l1\",\"type\":\"line\",\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[100,80]]},"
                + "{\"id\":\"f1\",\"type\":\"freedraw\",\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[50,30]]}]}";
        assertSame(in, ArrowGeometry.normalize(in));
    }

    @Test
    @DisplayName("points 里混进非法点（字符串 / 长度不足）→ 整条不动")
    void invalidPointUntouched() {
        String in = "{\"elements\":["
                + "{\"id\":\"a1\",\"type\":\"arrow\",\"x\":10,\"y\":20,"
                + "\"points\":[[0,0],[\"20\",20],[30]]}]}";
        assertSame(in, ArrowGeometry.normalize(in));
    }

    @Test
    @DisplayName("无 elements 数组 / 坏 JSON → 原样返回不抛异常")
    void malformedReturnsOriginal() {
        String noElements = "{\"foo\":1}";
        assertSame(noElements, ArrowGeometry.normalize(noElements));
        String broken = "not a json";
        assertSame(broken, ArrowGeometry.normalize(broken));
    }

    @Test
    @DisplayName("混合场景：只正交带斜段的箭头，其余元素原样")
    void mixedSceneOnlyDiagonalArrowChanged() throws Exception {
        String in = "{\"elements\":["
                + "{\"id\":\"a0\",\"type\":\"arrow\",\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[30,0],[30,-430]]},"
                + "{\"id\":\"a1\",\"type\":\"arrow\",\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[30,0],[30,-430],[-260,0]]}]}";
        String out = ArrowGeometry.normalize(in);
        JsonNode els = MAPPER.readTree(out).get("elements");
        // a0 本就正交，保持原 3 点
        assertEquals(3, els.get(0).get("points").size());
        // a1 末段斜线被拆直，点数增加且全正交
        assertOrthogonal(els.get(1).get("points"));
        assertTrue(els.get(1).get("points").size() > 4);
    }
}
