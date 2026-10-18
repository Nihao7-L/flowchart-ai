package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.LayoutEngine;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneGeometryKit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 箭头端点吸附回归。
 *
 * <p>来历：用户 m06890 截图「箭头开始位置为什么会是悬空状态」——
 * 「库存是否充足」菱形的一条出边起点浮在菱形正下方空白处，左右出边
 * 却贴着菱形。探针实测死四边全部离菱形真实斜边 10.0~11.5px：ELK 的
 * section start/end 落在节点**外接矩形**边上（它看不到菱形斜边），
 * 而前端 snapArrowEndsToAnchors 也只沿当前方向把端点推到 bbox 边、
 * 且对「端点在 bbox 上」直接不吸 —— 两个矩形口径叠加，斜边形状就漏出
 * 一个内接正方形半径的空隙。
 *
 * <p>修复：{@link SceneGeometryKit#edgePointOnShape} 在两条回写路径
 * （ELK writeBackEdgeRoutes、ScenePlanner.writeRoute）把端点按形状真实
 * 轮廓收进边界。菱形取射线与四条边的交点，椭圆解二次方程，矩形原样
 * 返回（零观感变化）。
 */
@DisplayName("箭头端点吸附到形状真实边界")
class ArrowAttachTest {

    private final ScenePlanner planner = new ScenePlanner();
    private final LayoutEngine engine = new ElkLayoutEngine();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("菱形出边端点贴在斜边上，不再悬空 10px")
    void diamondEndpointsTouchOutline() throws Exception {
        List<String> elements = new ArrayList<>();
        elements.add(shape("hub", "库存是否充足", "diamond", "库存检查"));
        for (int i = 0; i < 4; i++) {
            elements.add(shape("k" + i, "分支" + i, "rectangle", "库存检查"));
            elements.add(arrow("ek" + i, "hub", "k" + i));
        }
        JsonNode root = arrange(elements);

        JsonNode hub = node(root, "hub");
        double[] cut = new double[] {
                hub.path("x").asDouble(), hub.path("y").asDouble(),
                hub.path("width").asDouble(), hub.path("height").asDouble()};
        // 修前实测：四边距真实斜边 10.0 / 11.5 / 10.0 / 11.5
        for (int i = 0; i < 4; i++) {
            JsonNode a = node(root, "ek" + i);
            double[] p = firstPoint(a);
            double d = outlineDistance(p[0], p[1], hub);
            assertThat(d)
                    .as("ek%d 首点(%s) 到菱形真实轮廓的距离", i, p)
                    .isLessThan(1.0);
            assertThat(SceneGeometryKit.hasSlopedBoundary("diamond")).isTrue();
            assertThat(cut[2]).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("矩形端点保持 bbox 口径（零观感变化）")
    void rectangleEndpointsStayOnBox() throws Exception {
        List<String> elements = new ArrayList<>();
        elements.add(shape("r0", "起点", "rectangle", "流程"));
        elements.add(shape("r1", "终点", "rectangle", "流程"));
        elements.add(shape("e0", "圆端", "ellipse", "流程"));
        elements.add(arrow("a0", "r0", "r1"));
        elements.add(arrow("a1", "r1", "e0"));
        JsonNode root = arrange(elements);

        JsonNode r0 = node(root, "r0");
        double[] p = firstPoint(node(root, "a0"));
        assertThat(outlineDistance(p[0], p[1], r0)).isLessThan(1.0);

        JsonNode e0 = node(root, "e0");
        double[] q = lastPoint(node(root, "a1"));
        // 椭圆入端同样贴真实弧线（修前是最远能差半个短半轴）
        assertThat(outlineDistance(q[0], q[1], e0)).isLessThan(1.0);
    }

    @Test
    @DisplayName("跨模块箭头同样收端点（A* 路由回写路径）")
    void crossModuleArrowsTouchOutline() throws Exception {
        List<String> elements = new ArrayList<>();
        elements.add(shape("d0", "分支", "diamond", "库存检查"));
        elements.add(shape("d1", "兜底", "diamond", "库存检查"));
        elements.add(shape("n0", "通知0", "rectangle", "通知"));
        elements.add(shape("n1", "通知1", "rectangle", "通知"));
        elements.add(arrow("c1", "d0", "n0"));
        elements.add(arrow("c2", "n1", "d1"));
        JsonNode root = arrange(elements);

        JsonNode d0 = node(root, "d0");
        double[] p = firstPoint(node(root, "c1"));
        assertThat(outlineDistance(p[0], p[1], d0))
                .as("c1 首点应贴在菱形 d0 斜边上").isLessThan(1.0);

        JsonNode d1 = node(root, "d1");
        double[] q = lastPoint(node(root, "c2"));
        assertThat(outlineDistance(q[0], q[1], d1))
                .as("c2 末点应贴在菱形 d1 斜边上").isLessThan(1.0);
    }

    @Test
    @DisplayName("端点仍在形状附近（不会收过头穿到对侧）")
    void snappedEndpointsStayNearAnchor() throws Exception {
        List<String> elements = new ArrayList<>();
        elements.add(shape("hub", "判断", "diamond", "判断区"));
        for (int i = 0; i < 3; i++) {
            elements.add(shape("k" + i, "分支" + i, "rectangle", "判断区"));
            elements.add(arrow("ek" + i, "hub", "k" + i));
        }
        JsonNode root = arrange(elements);
        JsonNode hub = node(root, "hub");
        double cx = hub.path("x").asDouble()
                + hub.path("width").asDouble() / 2;
        double cy = hub.path("y").asDouble()
                + hub.path("height").asDouble() / 2;
        for (int i = 0; i < 3; i++) {
            double[] p = firstPoint(node(root, "ek" + i));
            double dist = Math.hypot(p[0] - cx, p[1] - cy);
            assertThat(dist)
                    .as("ek%d 首点仍在外接矩形一半范围内", i)
                    .isLessThan(Math.max(hub.path("width").asDouble(),
                            hub.path("height").asDouble()) / 2 + 1);
        }
    }

    // ==================== helpers ====================

    private JsonNode arrange(List<String> elements) throws Exception {
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, new LinkedHashSet<>());
        return mapper.readTree(laid);
    }

    private static JsonNode node(JsonNode root, String id) {
        for (JsonNode el : root.get("elements")) {
            if (id.equals(el.path("id").asText())) {
                return el;
            }
        }
        throw new IllegalStateException("找不到元素 " + id);
    }

    /** 首点绝对坐标 = x/y + points[0] */
    private static double[] firstPoint(JsonNode arrow) {
        JsonNode pts = arrow.path("points");
        assertThat(pts.isArray()).as("箭头应有 points").isTrue();
        return new double[] {
                arrow.path("x").asDouble() + pts.get(0).get(0).asDouble(),
                arrow.path("y").asDouble() + pts.get(0).get(1).asDouble()};
    }

    /** 末点绝对坐标 = x/y + points[size-1] */
    private static double[] lastPoint(JsonNode arrow) {
        JsonNode pts = arrow.path("points");
        assertThat(pts.isArray()).as("箭头应有 points").isTrue();
        int last = pts.size() - 1;
        return new double[] {
                arrow.path("x").asDouble() + pts.get(last).get(0).asDouble(),
                arrow.path("y").asDouble() + pts.get(last).get(1).asDouble()};
    }

    /**
     * 点到形状真实边界的距离。
     *
     * <p>菱形按 |dx|/(w/2)+|dy|/(h/2)=1 沿射线折返；椭圆按二次曲线；
     * 矩形按外接矩形。这是判据本体，与实现（SceneGeometryKit
     * .edgePointOnShape）算法不同 —— 刻意用独立口径互证。
     */
    private static double outlineDistance(double px, double py, JsonNode el) {
        double x = el.path("x").asDouble();
        double y = el.path("y").asDouble();
        double w = el.path("width").asDouble();
        double h = el.path("height").asDouble();
        double cx = x + w / 2;
        double cy = y + h / 2;
        double dx = px - cx;
        double dy = py - cy;
        String type = el.path("type").asText("");
        if ("diamond".equals(type)) {
            if (dx == 0 && dy == 0) {
                return 0;
            }
            double s = Math.abs(dx) / (w / 2) + Math.abs(dy) / (h / 2);
            double t = 1 / s;
            return Math.hypot(px - (cx + dx * t), py - (cy + dy * t));
        }
        if ("ellipse".equals(type)) {
            if (dx == 0 && dy == 0) {
                return 0;
            }
            double a = dx / (w / 2);
            double b = dy / (h / 2);
            double t = 1 / Math.sqrt(a * a + b * b);
            return Math.hypot(px - (cx + dx * t), py - (cy + dy * t));
        }
        return distToBox(px, py, el);
    }

    /** 点到包围盒的最短距离（盒内 = 0） */
    private static double distToBox(double px, double py, JsonNode el) {
        double x = el.path("x").asDouble();
        double y = el.path("y").asDouble();
        double dx = Math.max(Math.max(x - px, px - (x + el.path("width").asDouble())), 0);
        double dy = Math.max(Math.max(y - py, py - (y + el.path("height").asDouble())), 0);
        return Math.hypot(dx, dy);
    }

    private static String sceneOf(String... elements) {
        return "{\"elements\":[" + String.join(",", elements) + "]}";
    }

    private static String shape(String id, String label, String type,
            String group) {
        return "{\"id\":\"" + id
                + "\",\"type\":\"" + type
                + "\",\"x\":0,\"y\":0,\"width\":160,\"height\":60,"
                + "\"label\":{\"text\":\"" + label + "\"}"
                + ",\"group\":\"" + group + "\"}";
    }

    private static String arrow(String id, String from, String to) {
        return "{\"id\":\"" + id
                + "\",\"type\":\"arrow\",\"start\":{\"id\":\"" + from
                + "\"},\"end\":{\"id\":\"" + to + "\"}}";
    }
}
