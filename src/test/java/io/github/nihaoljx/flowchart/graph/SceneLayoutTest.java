package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布局测试（v2-36）
 *
 * <p>验收口径逐条对应 v2-36 计划：零重叠、边够长、标签装得下、
 * 长宽比受控、同输入同输出，以及最关键的<strong>两种模式</strong>
 * （新建全量重排 / 编辑只动新增）。
 */
class SceneLayoutTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private static final double GRID = 20.0;

    /** 五节点链：分层、零重叠、边长的基本盘 */
    private static final String CHAIN = """
            {"elements":[
            {"id":"a","type":"rectangle","x":0,"y":0,"width":100,"height":60},
            {"id":"b","type":"rectangle","x":10,"y":5,"width":100,"height":60},
            {"id":"c","type":"rectangle","x":20,"y":10,"width":100,"height":60},
            {"id":"d","type":"rectangle","x":30,"y":15,"width":100,"height":60},
            {"id":"e","type":"rectangle","x":40,"y":20,"width":100,"height":60},
            {"id":"ab","type":"arrow","start":{"id":"a"},"end":{"id":"b"}},
            {"id":"bc","type":"arrow","start":{"id":"b"},"end":{"id":"c"}},
            {"id":"cd","type":"arrow","start":{"id":"c"},"end":{"id":"d"}},
            {"id":"de","type":"arrow","start":{"id":"d"},"end":{"id":"e"}}]}
            """;

    /** 一条分叉再汇聚的图：层内排序的用武之地 */
    private static final String DIAMOND = """
            {"elements":[
            {"id":"root","type":"rectangle","x":0,"y":0,"width":100,"height":60},
            {"id":"l","type":"rectangle","x":300,"y":0,"width":100,"height":60},
            {"id":"m","type":"rectangle","x":300,"y":200,"width":100,"height":60},
            {"id":"r","type":"rectangle","x":300,"y":400,"width":100,"height":60},
            {"id":"sink","type":"rectangle","x":600,"y":0,"width":100,"height":60},
            {"id":"a1","type":"arrow","start":{"id":"root"},"end":{"id":"l"}},
            {"id":"a2","type":"arrow","start":{"id":"root"},"end":{"id":"m"}},
            {"id":"a3","type":"arrow","start":{"id":"root"},"end":{"id":"r"}},
            {"id":"a4","type":"arrow","start":{"id":"l"},"end":{"id":"sink"}},
            {"id":"a5","type":"arrow","start":{"id":"m"},"end":{"id":"sink"}},
            {"id":"a6","type":"arrow","start":{"id":"r"},"end":{"id":"sink"}}]}
            """;

    private final SceneLayout layout = new SceneLayout();

    // ==================== 断言工具 ====================

    /** 取出所有图形的 {id, x, y, w, h} */
    private static List<double[]> boxes(String json)
            throws Exception {
        JsonNode elements = MAPPER
                .readTree(json).get("elements");
        List<double[]> result = new ArrayList<>();
        for (JsonNode element : elements) {
            String type = element.get("type").asText();
            if (!"rectangle".equals(type)
                    && !"ellipse".equals(type)
                    && !"diamond".equals(type)) {
                continue;
            }
            result.add(new double[] {
                element.get("x").asDouble(),
                element.get("y").asDouble(),
                element.get("width").asDouble(),
                element.get("height").asDouble(),
            });
        }
        return result;
    }

    private static JsonNode elementOf(
            String json, String id) throws Exception {
        for (JsonNode element : MAPPER
                .readTree(json).get("elements")) {
            if (id.equals(
                    element.get("id").asText())) {
                return element;
            }
        }
        return null;
    }

    /** 两两图形是否零重叠（容差 0，网格对齐后是整数比较） */
    private static void assertNoOverlap(String json)
            throws Exception {
        List<double[]> boxes = boxes(json);
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1;
                    j < boxes.size(); j++) {
                double[] a = boxes.get(i);
                double[] b = boxes.get(j);
                boolean overlapping =
                        a[0] < b[0] + b[2]
                        && b[0] < a[0] + a[2]
                        && a[1] < b[1] + b[3]
                        && b[1] < a[1] + a[3];
                assertFalse(overlapping,
                        "图形互相压住: "
                                + java.util.Arrays
                                        .toString(a)
                                + " vs "
                                + java.util.Arrays
                                        .toString(b));
            }
        }
    }

    private static double[] bounds(String json)
            throws Exception {
        List<double[]> boxes = boxes(json);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] box : boxes) {
            minX = Math.min(minX, box[0]);
            minY = Math.min(minY, box[1]);
            maxX = Math.max(maxX, box[0] + box[2]);
            maxY = Math.max(maxY, box[1] + box[3]);
        }
        return new double[] {
            maxX - minX, maxY - minY,
        };
    }

    private static Set<String> idsOf(String json)
            throws Exception {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode element : MAPPER
                .readTree(json).get("elements")) {
            ids.add(element.get("id").asText());
        }
        return ids;
    }

    private static double number(
            JsonNode node, String field) {
        return node == null || node.get(field) == null
                ? 0
                : node.get(field).asDouble();
    }

    // ==================== 新建模式：全量重排 ====================

    @Test
    @DisplayName("五节点链：逐层铺开、零重叠、坐标吸附网格")
    void chainIsLayered() throws Exception {
        String out = layout.layout(CHAIN, Set.of());

        assertNoOverlap(out);
        // 逐层推进：五个节点必须沿某一个轴严格递增地排开。
        // 不预设竖排还是横排 —— 主方向由长宽比决定，两种都合法
        List<double[]> chain = boxes(out);
        boolean down = true;
        boolean right = true;
        for (int i = 1; i < chain.size(); i++) {
            if (chain.get(i)[1] <= chain.get(i - 1)[1]) {
                down = false;
            }
            if (chain.get(i)[0] <= chain.get(i - 1)[0]) {
                right = false;
            }
        }
        assertTrue(down || right,
                "五个节点应沿主方向逐层铺开");
        for (double[] box : chain) {
            assertEquals(0, Math.abs(box[0] % GRID),
                    1e-6, "x 未吸附网格: " + box[0]);
            assertEquals(0, Math.abs(box[1] % GRID),
                    1e-6, "y 未吸附网格: " + box[1]);
        }
    }

    @Test
    @DisplayName("分叉汇聚：兄弟同层、按序排开、零重叠")
    void diamondStaysReadable() throws Exception {
        String out = layout.layout(DIAMOND, Set.of());

        assertNoOverlap(out);
        double y1 = number(elementOf(out, "l"), "y");
        double y2 = number(elementOf(out, "m"), "y");
        double y3 = number(elementOf(out, "r"), "y");
        assertEquals(y1, y2, 1e-6, "l / m 应同层");
        assertEquals(y2, y3, 1e-6, "m / r 应同层");
        assertTrue(number(elementOf(out, "sink"), "y")
                > y1, "sink 应在兄弟层之后");
        double xl = number(elementOf(out, "l"), "x");
        double xm = number(elementOf(out, "m"), "x");
        double xr = number(elementOf(out, "r"), "x");
        assertTrue(xl < xm && xm < xr,
                "兄弟节点应按序排开");
    }

    @Test
    @DisplayName("LLM 给了互相压住的坐标：重排后必须分开")
    void overlappingInputIsSeparated() throws Exception {
        String stacked = """
                {"elements":[
                {"id":"a","type":"rectangle","x":100,"y":100,"width":200,"height":80},
                {"id":"b","type":"rectangle","x":100,"y":100,"width":200,"height":80},
                {"id":"c","type":"rectangle","x":100,"y":100,"width":200,"height":80},
                {"id":"ab","type":"arrow","start":{"id":"a"},"end":{"id":"b"}},
                {"id":"bc","type":"arrow","start":{"id":"b"},"end":{"id":"c"}}]}
                """;
        String out = layout.layout(stacked, Set.of());
        assertNoOverlap(out);
    }

    @Test
    @DisplayName("单个图形 / 两个图形无箭头：不崩、不出 NaN")
    void minimalInputsSurvive() throws Exception {
        String single = """
                {"elements":[
                {"id":"only","type":"rectangle","x":5,"y":7,"width":90,"height":40}]}
                """;
        String outSingle = layout.layout(single, Set.of());
        assertNoOverlap(outSingle);
        JsonNode only = elementOf(outSingle, "only");
        assertTrue(Double.isFinite(number(only, "x")));
        assertTrue(Double.isFinite(number(only, "y")));

        String pair = """
                {"elements":[
                {"id":"p1","type":"ellipse","x":0,"y":0,"width":100,"height":60},
                {"id":"p2","type":"diamond","x":0,"y":0,"width":100,"height":60}]}
                """;
        String outPair = layout.layout(pair, Set.of());
        assertNoOverlap(outPair);
    }

    @Test
    @DisplayName("自环箭头与悬空端点：跳过该边，不出错也不删元素")
    void selfLoopAndDanglingAreSkipped() throws Exception {
        String weird = """
                {"elements":[
                {"id":"a","type":"rectangle","x":0,"y":0,"width":100,"height":60},
                {"id":"b","type":"rectangle","x":0,"y":0,"width":100,"height":60},
                {"id":"loop","type":"arrow","start":{"id":"a"},"end":{"id":"a"}},
                {"id":"ghost","type":"arrow","start":{"id":"a"},"end":{"id":"nope"}}]}
                """;
        String out = layout.layout(weird, Set.of());
        assertNoOverlap(out);
        assertNotNull(elementOf(out, "loop"));
        assertNotNull(elementOf(out, "ghost"));
    }

    @Test
    @DisplayName("边上标签 8 个汉字：层间距被拉长到装得下")
    void longEdgeLabelWidensGap() throws Exception {
        String labeled = """
                {"elements":[
                {"id":"a","type":"rectangle","x":0,"y":0,"width":100,"height":60},
                {"id":"b","type":"rectangle","x":0,"y":200,"width":100,"height":60},
                {"id":"ab","type":"arrow","start":{"id":"a"},
                 "end":{"id":"b"},"label":{"text":"需要人工复核确认"}}]}
                """;
        String out = layout.layout(labeled, Set.of());
        double gap = number(elementOf(out, "b"), "y")
                - number(elementOf(out, "a"), "y")
                - number(elementOf(out, "a"), "height");
        // 8 个汉字按浏览器实测 20px/字 = 160px，再加两侧最起码的 12px 留白 = 184px
        // （估算常数 2026-09-18 用 tools/e2e/probe_text_metrics.py 标定，旧值 16px/字 低估 25%）
        assertTrue(gap >= 8 * 20.0 + 2 * 12.0,
                "层间距 " + gap + " 装不下 8 字标签");
    }

    @Test
    @DisplayName("十层单链：不挑更细长的方向（横排会是 38:1）")
    void tallChainKeepsBetterOrientation()
            throws Exception {
        String out = layout.layout(chain(10), Set.of());

        assertNoOverlap(out);
        double[] size = bounds(out);
        double aspect = size[0] / size[1];
        // 单链无论横竖都是长条，能达到的极限就是竖排（约 1:9.4）。
        // 这里守的是"别翻成 38:1 的横排"—— 判据必须是"确实更好才换"，
        // 而不是"超出 [0.5, 2.0] 就换"（2026-09-18 实测踩过）
        assertTrue(aspect < 1.0,
                "十层链被翻成横排，长宽比 " + aspect);
    }

    /** 用 Jackson 拼一条 n 层单链，避免手写转义 */
    private static String chain(int length)
            throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode elements = root.putArray("elements");
        for (int i = 0; i < length; i++) {
            ObjectNode shape = elements.addObject();
            shape.put("id", "n" + i);
            shape.put("type", "rectangle");
            shape.put("x", 0);
            shape.put("y", i * 50);
            shape.put("width", 100);
            shape.put("height", 60);
        }
        for (int i = 0; i < length - 1; i++) {
            ObjectNode edge = elements.addObject();
            edge.put("id", "e" + i);
            edge.put("type", "arrow");
            edge.putObject("start").put("id", "n" + i);
            edge.putObject("end").put("id", "n" + (i + 1));
        }
        return MAPPER.writeValueAsString(root);
    }

    @Test
    @DisplayName("确定性：同一输入跑两次，输出字节一致")
    void sameInputSameOutput() {
        String first = layout.layout(CHAIN, Set.of());
        String second = layout.layout(CHAIN, Set.of());
        assertEquals(first, second,
                "布局必须是纯函数：同输入同输出");
    }

    @Test
    @DisplayName("frame 与独立 text 不参与摆放")
    void frameAndTextStayPut() throws Exception {
        String withFrame = """
                {"elements":[
                {"id":"a","type":"rectangle","x":0,"y":0,"width":100,"height":60},
                {"id":"b","type":"rectangle","x":0,"y":0,"width":100,"height":60},
                {"id":"f1","type":"frame","x":900,"y":900,"width":400,"height":300},
                {"id":"t1","type":"text","x":50,"y":800,"text":"标题"},
                {"id":"ab","type":"arrow","start":{"id":"a"},"end":{"id":"b"}}]}
                """;
        String out = layout.layout(withFrame, Set.of());
        assertEquals(900,
                number(elementOf(out, "f1"), "x"), 1e-6,
                "frame 不该被移动");
        assertEquals(50,
                number(elementOf(out, "t1"), "x"), 1e-6,
                "独立 text 不该被移动");
    }

    @Test
    @DisplayName("坏 JSON / 缺 elements：原样返回，不抛异常")
    void malformedInputReturnsUnchanged() {
        String broken = "not json at all";
        assertEquals(broken,
                layout.layout(broken, Set.of()));
        String noElements = MAPPER
                .createObjectNode()
                .put("foo", 1)
                .toString();
        assertEquals(noElements,
                layout.layout(noElements, Set.of()));
    }

    // ==================== 编辑模式：只动新增 ====================

    @Test
    @DisplayName("编辑模式：已有图形的坐标一个都不动")
    void editModeKeepsFrozenCoordinates()
            throws Exception {
        // 会话里已有一张图（坐标是 LLM 当初给的，故意不是网格值）
        String existing = """
                {"elements":[
                {"id":"old1","type":"rectangle","x":37,"y":91,"width":180,"height":70},
                {"id":"old2","type":"rectangle","x":517,"y":93,"width":180,"height":70},
                {"id":"oldEdge","type":"arrow",
                 "start":{"id":"old1"},"end":{"id":"old2"}}]}
                """;
        // 用户说"再加一个审批节点"：LLM 返回全量场景（旧的原样带过 + 新元素）
        String updated = """
                {"elements":[
                {"id":"old1","type":"rectangle","x":37,"y":91,"width":180,"height":70},
                {"id":"old2","type":"rectangle","x":517,"y":93,"width":180,"height":70},
                {"id":"oldEdge","type":"arrow",
                 "start":{"id":"old1"},"end":{"id":"old2"}},
                {"id":"fresh","type":"rectangle","x":40,"y":95,"width":180,"height":70},
                {"id":"newEdge","type":"arrow",
                 "start":{"id":"old2"},"end":{"id":"fresh"}}]}
                """;
        String out = layout.layout(
                updated, idsOf(existing));

        JsonNode old1 = elementOf(out, "old1");
        JsonNode old2 = elementOf(out, "old2");
        assertEquals(37, number(old1, "x"), 1e-6,
                "既有图形 x 必须原样保留");
        assertEquals(91, number(old1, "y"), 1e-6,
                "既有图形 y 必须原样保留");
        assertEquals(180, number(old1, "width"), 1e-6,
                "既有图形尺寸必须原样保留");
        assertEquals(517, number(old2, "x"), 1e-6);
        assertEquals(93, number(old2, "y"), 1e-6);
    }

    @Test
    @DisplayName("编辑模式：新增图形不压住既有图形")
    void editModeAvoidsExistingShapes()
            throws Exception {
        String existing = """
                {"elements":[
                {"id":"old1","type":"rectangle","x":0,"y":0,"width":180,"height":70}]}
                """;
        // 新增图形给的坐标正好压在既有图形上
        String updated = """
                {"elements":[
                {"id":"old1","type":"rectangle","x":0,"y":0,"width":180,"height":70},
                {"id":"fresh1","type":"rectangle","x":10,"y":10,"width":180,"height":70},
                {"id":"fresh2","type":"rectangle","x":10,"y":10,"width":180,"height":70},
                {"id":"e1","type":"arrow",
                 "start":{"id":"fresh1"},"end":{"id":"fresh2"}}]}
                """;
        String out = layout.layout(
                updated, idsOf(existing));

        assertNoOverlap(out);
        assertEquals(0,
                number(elementOf(out, "old1"), "x"), 1e-6,
                "既有图形不该被挪走");
    }

    @Test
    @DisplayName("编辑模式：没有新增图形时原样返回")
    void editModeWithoutNewShapesIsNoop() {
        String existing = """
                {"elements":[
                {"id":"old1","type":"rectangle","x":37,"y":91,"width":180,"height":70}]}
                """;
        assertEquals(existing,
                layout.layout(existing, Set.of("old1")));
    }

    @Test
    @DisplayName("未绑定两点箭头：图形挪走后重新贴到边界上")
    void unboundArrowIsReattached() throws Exception {
        // 箭头是点式的（两端没有绑定），端点在两个框上
        String pointwise = """
                {"elements":[
                {"id":"a","type":"rectangle","x":0,"y":0,"width":100,"height":60},
                {"id":"b","type":"rectangle","x":300,"y":0,"width":100,"height":60},
                {"id":"a1","type":"arrow","x":100,"y":30,
                 "points":[[0,0],[200,0]]}]}
                """;
        String out = layout.layout(pointwise, Set.of());

        JsonNode arrow = elementOf(out, "a1");
        JsonNode nodeA = elementOf(out, "a");
        JsonNode nodeB = elementOf(out, "b");
        JsonNode points = arrow.get("points");
        assertEquals(2, points.size(),
                "重连后仍是两点式箭头");

        double startX = number(arrow, "x");
        double startY = number(arrow, "y");
        double endX = startX
                + points.get(1).get(0).asDouble();
        double endY = startY
                + points.get(1).get(1).asDouble();

        // 两端都必须贴在各自图形的边界上，箭头才叫"连着"。
        // 不预设方向：主方向由长宽比决定，竖排横排都要成立
        assertTrue(onBoundary(nodeA, startX, startY),
                "箭头起点没贴在 a 的边界上: "
                        + startX + "," + startY);
        assertTrue(onBoundary(nodeB, endX, endY),
                "箭头终点没贴在 b 的边界上: "
                        + endX + "," + endY);
    }

    /** 点是否落在矩形边界上（容差 1px） */
    private static boolean onBoundary(
            JsonNode shape, double px, double py) {
        double x = number(shape, "x");
        double y = number(shape, "y");
        double w = number(shape, "width");
        double h = number(shape, "height");
        boolean inside = px >= x - 1 && px <= x + w + 1
                && py >= y - 1 && py <= y + h + 1;
        if (!inside) {
            return false;
        }
        boolean onVerticalEdge =
                Math.abs(px - x) <= 1
                || Math.abs(px - (x + w)) <= 1;
        boolean onHorizontalEdge =
                Math.abs(py - y) <= 1
                || Math.abs(py - (y + h)) <= 1;
        return onVerticalEdge || onHorizontalEdge;
    }
}
