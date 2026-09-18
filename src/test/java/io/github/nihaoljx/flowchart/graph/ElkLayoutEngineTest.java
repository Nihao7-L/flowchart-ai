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
