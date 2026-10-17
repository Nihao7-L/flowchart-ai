package io.github.nihaoljx.flowchart.graph.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网格 A* 正交路由器的出入口判据。
 *
 * <p>这里是「箭头到最后还要折一下」的回归闸门。节点坐标一般不是 20px
 * 网格的整数倍（ELK 算出来的 x/y 带小数），锚点若整体吸附到最近网格点
 * 就会偏离节点边 ±10px，路由被迫在末端补一个假折弯；正确做法是只沿
 * 所在边量化（法向坐标精确留在边线上），并把出入口的两步钉死成直线。
 *
 * <p>断言口径：首段 / 末段必须长到足以证明「贴着节点的那一段是直的」
 * —— 首段 = 锚点走到网格 + 至少一格，末段 = 至少一格 + 网格走回锚点。
 */
@DisplayName("OrthoAStarRouter 出入口直线 stub")
class OrthoAStarRouterTest {

    private static final double GRID = 20;

    // ==================== 普通场景：跨列连线的出入口 ====================

    @Test
    @DisplayName("节点坐标不在网格上时，出入口仍是直线、不补假折弯")
    void stubsAreStraightWhenNodesAreOffGrid() {
        List<ScenePlanner.Node> nodes = new ArrayList<>();
        // 刻意全部用非 20 倍数的坐标（83 / 137 / 613 / 1079 ...）
        nd(nodes, "a0", 137, 53);
        nd(nodes, "a1", 137, 149);
        nd(nodes, "a2", 137, 251);
        nd(nodes, "b0", 613, 97);
        nd(nodes, "b1", 613, 193);
        nd(nodes, "c0", 1079, 41);

        OrthoAStarRouter.Grid grid = new OrthoAStarRouter.Grid(nodes);
        String[][] pairs = {
            {"a0", "b0"},
            {"a1", "c0"},
            {"b1", "a2"},
            {"c0", "a0"},
        };
        for (String[] pair : pairs) {
            ScenePlanner.Node from = byId(nodes, pair[0]);
            ScenePlanner.Node to = byId(nodes, pair[1]);
            List<double[]> route =
                    OrthoAStarRouter.route(from, to, 0, 1, 0, 1, nodes, grid);
            assertThat(route).as("%s->%s 应能路由", pair[0], pair[1]).isNotNull();

            double head = segLen(route.get(0), route.get(1));
            double tail = segLen(route.get(route.size() - 2),
                    route.get(route.size() - 1));
            System.out.println("ROUTE " + pair[0] + "->" + pair[1]
                    + " bends=" + (route.size() - 2)
                    + " head=" + head + " tail=" + tail + " "
                    + render(route));

            // 首段：锚点 -> 网格（10~30px，由障碍外扩 8px 决定）+ 至少一格
            assertThat(head)
                    .as("%s->%s 首段应包含一整格直行", pair[0], pair[1])
                    .isGreaterThanOrEqualTo(GRID + 10 - 1e-9);
            // 末段：至少一格 + 网格 -> 锚点
            assertThat(tail)
                    .as("%s->%s 末段应包含一整格直行", pair[0], pair[1])
                    .isGreaterThanOrEqualTo(GRID + 8 - 1e-9);
            // 出入口第一个顶点必须落在节点边线上（只沿边量化，法向不吸附）
            assertOnBoxEdge(from, route.get(0), pair[0]);
            assertOnBoxEdge(to, route.get(route.size() - 1), pair[1]);
            grid.mark(route.toArray(new double[0][]));
        }
    }

    // ==================== 锚点贴边量化 ====================

    @Test
    @DisplayName("锚点只沿所在边量化：法向坐标留在节点边线上")
    void anchorsStayOnTheBoxEdge() {
        List<ScenePlanner.Node> nodes = new ArrayList<>();
        nd(nodes, "a0", 137, 53);
        nd(nodes, "b0", 581, 337);

        OrthoAStarRouter.Grid grid = new OrthoAStarRouter.Grid(nodes);
        List<double[]> route = OrthoAStarRouter.route(
                byId(nodes, "a0"), byId(nodes, "b0"), 0, 1, 0, 1, nodes, grid);
        assertThat(route).isNotNull();

        assertOnBoxEdge(byId(nodes, "a0"), route.get(0), "a0");
        assertOnBoxEdge(byId(nodes, "b0"), route.get(route.size() - 1), "b0");
    }

    // ==================== 不穿节点 ====================

    @Test
    @DisplayName("路线不穿过任何节点框（含 8px 外扩）")
    void routesDoNotPenetrateNodes() {
        List<ScenePlanner.Node> nodes = new ArrayList<>();
        nd(nodes, "a0", 137, 53);
        nd(nodes, "blocker", 373, 49);
        nd(nodes, "a1", 137, 251);
        nd(nodes, "c0", 1009, 141);

        OrthoAStarRouter.Grid grid = new OrthoAStarRouter.Grid(nodes);
        List<double[]> route = OrthoAStarRouter.route(
                byId(nodes, "a0"), byId(nodes, "c0"), 0, 1, 0, 1, nodes, grid);
        assertThat(route).isNotNull();
        System.out.println("ROUTE blocker " + render(route));

        List<ScenePlanner.Node> obstacles = new ArrayList<>();
        obstacles.add(byId(nodes, "blocker"));
        assertThat(hitsNodes(route, obstacles))
                .as("绕行应避开中间节点").isFalse();
    }

    // ==================== 工具 ====================

    private static void nd(List<ScenePlanner.Node> all,
            String id, double x, double y) {
        ObjectNode j = new ObjectMapper().createObjectNode();
        j.put("id", id).put("type", "rectangle")
                .put("x", x).put("y", y).put("width", 160).put("height", 60);
        all.add(new ScenePlanner.Node(j, id, 160, 60));
    }

    private static ScenePlanner.Node byId(
            List<ScenePlanner.Node> nodes, String id) {
        for (ScenePlanner.Node n : nodes) {
            if (n.id.equals(id)) {
                return n;
            }
        }
        throw new IllegalArgumentException(id);
    }

    /** 端点必须落在节点边线上（允许网格量化带来的 ±11px 沿边偏移） */
    private static void assertOnBoxEdge(
            ScenePlanner.Node n, double[] p, String who) {
        boolean onVertical = Math.abs(p[0] - n.x) < 1e-6
                || Math.abs(p[0] - (n.x + n.w)) < 1e-6;
        boolean onHorizontal = Math.abs(p[1] - n.y) < 1e-6
                || Math.abs(p[1] - (n.y + n.h)) < 1e-6;
        assertThat(onVertical || onHorizontal)
                .as("%s 的端点 %s 应贴在节点(%s,%s,%s,%s)的边线上",
                        who, render1(p), n.x, n.y, n.w, n.h)
                .isTrue();
    }

    /** 折线是否穿进任一节点框（外扩 8px，贴框不算） */
    private static boolean hitsNodes(
            List<double[]> pts, List<ScenePlanner.Node> nodes) {
        for (ScenePlanner.Node n : nodes) {
            double[] box = {n.x - 8, n.y - 8, n.x + n.w + 8, n.y + n.h + 8};
            for (int i = 0; i + 1 < pts.size(); i++) {
                if (SceneGeometryKit.segHitsBox(
                        new double[] {pts.get(i)[0], pts.get(i)[1],
                            pts.get(i + 1)[0], pts.get(i + 1)[1]}, box)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double segLen(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
    }

    private static String render(List<double[]> pts) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : pts) {
            sb.append('(').append(p[0]).append(',').append(p[1]).append(") ");
        }
        return sb.toString().trim();
    }

    private static String render1(double[] p) {
        return "(" + p[0] + "," + p[1] + ")";
    }
}
