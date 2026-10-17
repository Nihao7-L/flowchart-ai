package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.LayoutSpec;

/**
 * ELK 布局引擎测试（v2-38）
 *
 * <p>只守<b>契约</b>，不守具体坐标 —— 引擎可以换，契约不能换：
 * 两种模式（新建全量重排 / 编辑只动新增）、零重叠、失败原样返回、
 * 同输入同输出。坐标长什么样由 {@code LayoutBench} 用指标管。
 */
class ElkLayoutEngineTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    /** 五节点链 */
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

    private final ElkLayoutEngine layout = new ElkLayoutEngine();

    @Test
    @DisplayName("新建模式：图形互不重叠")
    void newSceneSeparatesShapes() throws Exception {
        List<double[]> boxes = boxes(
                layout.layout(CHAIN, Set.of()));
        assertEquals(5, boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                assertFalse(overlaps(boxes.get(i), boxes.get(j)),
                        "图形 " + i + " 与 " + j + " 重叠");
            }
        }
    }

    @Test
    @DisplayName("新建模式：沿主方向层层递进")
    void newSceneIsLayered() throws Exception {
        List<double[]> boxes = boxes(
                layout.layout(CHAIN, Set.of()));
        double[] first = boxes.get(0);
        double[] last = boxes.get(boxes.size() - 1);
        boolean spreadVertically =
                last[1] > first[1] + 100;
        boolean spreadHorizontally =
                last[0] > first[0] + 100;
        assertTrue(spreadVertically || spreadHorizontally,
                "首尾节点没有拉开层次");
    }

    @Test
    @DisplayName("新建模式：链状流程图从上往下推进，不压成横排")
    void chainFlowsTopDown() throws Exception {
        // 流程图模板写死"主流程从上往下单向推进，不要画成左右来回"。
        // 这里守的是那条要求：五节点竖链必须一层一层往下走。
        //
        // 曾经的真回归：整图路径开了 viewportAware，而"双向都出区间就取
        // 更宽"那条启发式对链状图恒成立 —— 横排的高度恒等于最大单节点高
        // （约 60~120），竖排的高度恒等于节点数×层高（约 500+），于是
        // right[3] < down[3] 永远为真，任何 5 节点以上的流程图都会被转置
        // 成一条 9:1 的细长横条。layoutModule 早已因此关掉该启发式，
        // 整图路径却漏了。
        List<double[]> boxes = boxes(
                layout.layout(CHAIN, Set.of()));
        double firstY = boxes.get(0)[1];
        double lastY = boxes.get(boxes.size() - 1)[1];
        double spreadY = Math.abs(lastY - firstY);
        double minHeight = boxes.stream()
                .mapToDouble(b -> b[3]).min().orElse(0);
        assertTrue(spreadY > minHeight * 2,
                "五个节点只拉开了 " + spreadY + "px 的纵向距离，"
                        + "说明被排版成横排了（应从上往下）");
    }

    @Test
    @DisplayName("新建模式：长竖链也不会被压成横条")
    void longChainStaysVertical() throws Exception {
        StringBuilder json = new StringBuilder("{\"elements\":[");
        for (int i = 0; i < 7; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"n").append(i)
                    .append("\",\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                            + "\"width\":160,\"height\":48}");
        }
        for (int i = 0; i < 6; i++) {
            json.append(",{\"id\":\"e").append(i)
                    .append("\",\"type\":\"arrow\",\"start\":{\"id\":\"n")
                    .append(i).append("\"},\"end\":{\"id\":\"n")
                    .append(i + 1).append("\"}}");
        }
        json.append("]}");

        List<double[]> boxes = boxes(
                layout.layout(json.toString(), Set.of()));
        double dy = Math.abs(boxes.get(boxes.size() - 1)[1]
                - boxes.get(0)[1]);
        double dx = Math.abs(boxes.get(boxes.size() - 1)[0]
                - boxes.get(0)[0]);
        assertTrue(dy > dx,
                "七节点链排成了横条（dx=" + dx + " > dy=" + dy
                        + "），流程图应自上而下");
    }

    @Test
    @DisplayName("新建模式：宽扁的层级图仍允许横排")
    void wideLayerGraphMayGoHorizontal() throws Exception {
        // 收紧方向启发式时不能把"该横排的图"也竖起来：三层、每层五个
        // 并列兄弟，竖排会得到 5 列 × 3 行的方块，横排才有 3 行 5 列的
        // 自然读法（且与 16:9 视口同向）。
        StringBuilder json = new StringBuilder("{\"elements\":[");
        for (int layer = 0; layer < 3; layer++) {
            for (int col = 0; col < 5; col++) {
                if (layer > 0 || col > 0) {
                    json.append(',');
                }
                json.append("{\"id\":\"n").append(layer).append('_')
                        .append(col)
                        .append("\",\"type\":\"rectangle\",\"x\":0,"
                                + "\"y\":0,\"width\":160,\"height\":48}");
            }
        }
        for (int col = 0; col < 5; col++) {
            for (int layer = 0; layer < 2; layer++) {
                json.append(",{\"id\":\"e").append(layer).append('_')
                        .append(col)
                        .append("\",\"type\":\"arrow\",\"start\":{\"id\":\"n")
                        .append(layer).append('_').append(col)
                        .append("\"},\"end\":{\"id\":\"n")
                        .append(layer + 1).append('_').append(col)
                        .append("\"}}");
            }
        }
        json.append("]}");

        List<double[]> boxes = boxes(
                layout.layout(json.toString(), Set.of()));
        assertEquals(15, boxes.size());
        // 契约只要求"排得开且成层"，方向交给 LayoutSpec 判据；
        // 这里断言不被压成一条线即可（两条轴都摊开）
        double minX = boxes.stream().mapToDouble(b -> b[0]).min().orElse(0);
        double maxX = boxes.stream().mapToDouble(b -> b[0] + b[2]).max().orElse(0);
        double minY = boxes.stream().mapToDouble(b -> b[1]).min().orElse(0);
        double maxY = boxes.stream().mapToDouble(b -> b[1] + b[3]).max().orElse(0);
        assertTrue(maxX - minX > 400 && maxY - minY > 100,
                "层级图塌成了一条线：" + (maxX - minX) + "x" + (maxY - minY));
    }

    @Test
    @DisplayName("编辑模式：冻结坐标一个像素都不动")
    void editModeKeepsFrozenCoordinates() throws Exception {
        String result = layout.layout(CHAIN,
                Set.of("a", "b", "c", "d", "e"));
        // 全部冻结 = 无可摆放图形 = 原样返回
        assertEquals(MAPPER.readTree(CHAIN),
                MAPPER.readTree(result));

        String partial = layout.layout(CHAIN, Set.of("a"));
        JsonNode before = MAPPER.readTree(CHAIN).get("elements")
                .get(0);
        JsonNode after = MAPPER.readTree(partial).get("elements")
                .get(0);
        assertEquals(before.get("x").asDouble(),
                after.get("x").asDouble(), 0.001);
        assertEquals(before.get("y").asDouble(),
                after.get("y").asDouble(), 0.001);
    }

    @Test
    @DisplayName("畸形输入原样返回，不抛异常")
    void malformedInputReturnsUnchanged() {
        assertEquals("not json at all",
                layout.layout("not json at all", Set.of()));
        assertEquals("{\"elements\":[]}",
                layout.layout("{\"elements\":[]}", Set.of()));
        assertEquals("{}", layout.layout("{}", Set.of()));
    }

    @Test
    @DisplayName("同输入同输出")
    void sameInputSameOutput() {
        String once = layout.layout(CHAIN, Set.of());
        String twice = layout.layout(CHAIN, Set.of());
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("尺寸写回契约：图形按渲染尺寸改写宽高，不是种子里那个")
    void writesBackRenderedSize() throws Exception {
        // 布局是按"渲染端会画多大"算层间距的（LayoutSpec#nodeSize），
        // 所以必须把同一套尺寸写回契约 —— 否则渲染端按旧尺寸重排文字、
        // 把装不下的容器撑高，层间距当场失效（2026-09-18 实测题 2）。
        String laid = layout.layout(CHAIN, Set.of());
        int checked = 0;
        for (JsonNode element : MAPPER.readTree(laid).get("elements")) {
            if (!"rectangle".equals(element.path("type").asText())) {
                continue;
            }
            checked++;
            assertEquals(LayoutSpec.MIN_NODE_WIDTH,
                    element.get("width").asDouble(), 1e-9,
                    "无标签图形的宽度应统一到下限");
            assertTrue(element.get("height").asDouble()
                            >= LayoutSpec.NODE_HEIGHT,
                    "高度不得低于下限");
        }
        assertEquals(5, checked);
    }

    @Test
    @DisplayName("编辑模式：冻结图形的尺寸也一个像素不动")
    void editModeKeepsFrozenSize() throws Exception {
        String partial = layout.layout(CHAIN, Set.of("a", "b", "c", "d"));
        JsonNode frozen = MAPPER.readTree(partial).get("elements").get(0);
        assertEquals(100, frozen.get("width").asDouble(), 1e-9);
        assertEquals(60, frozen.get("height").asDouble(), 1e-9);
    }

    // ==================== 工具 ====================

    private static List<double[]> boxes(String json)
            throws Exception {
        List<double[]> result = new ArrayList<>();
        for (JsonNode element : MAPPER.readTree(json)
                .get("elements")) {
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

    private static boolean overlaps(double[] a, double[] b) {
        double width = Math.min(a[0] + a[2], b[0] + b[2])
                - Math.max(a[0], b[0]);
        double height = Math.min(a[1] + a[3], b[1] + b[3])
                - Math.max(a[1], b[1]);
        return width > 1 && height > 1;
    }
}
