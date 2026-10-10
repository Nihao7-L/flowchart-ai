package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * v2-41 端到端回归：真后端管线（与 {@code GenerationService} 调用顺序一致）
 * 不再重排 LLM 原生坐标。
 *
 * <p>用户核心诉求：「前端真实渲染（REAL）不如 NEW 好看，一定是竖排原因」。
 * v2-41 把 {@code GenerationService} 里的 {@code scenePlanner.arrange(...)}
 * 换成 {@code scenePlanner.claimFramesOnly(...)}，后端不再做
 * 「尺寸抹平 + 竖排重排」。本测试在不调 LLM 的前提下，把一条
 * {@code bind → dedup → claimFramesOnly} 的真实管线跑通，并断言：
 * <ul>
 *   <li>每个图形的 x/y/width/height 与 LLM 给的<b>原生坐标逐值相等</b>
 *       （即 REAL == NEW，后端一行几何都没动）；</li>
 *   <li>「画框认领」照常生效（frame.children 填上同模块成员）；</li>
 *   <li>管线不增删元素。</li>
 * </ul>
 */
class BackendPipelineNativeGeometryTest {

    private final ScenePlanner planner = new ScenePlanner();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("v2-41：bind→dedup→claimFramesOnly 整条后端管线不重排，原生坐标原样保留（REAL==NEW）")
    void backendPipelineKeepsNativeGeometry() throws Exception {
        // LLM 原生坐标：彼此错位、大小不一。
        // 若后端仍做「尺寸抹平 + 竖排重排」，这些坐标会立刻被改成竖条 / 统一尺寸，被断言抓到。
        String json = "{\"elements\":["
                + "{\"id\":\"f-order\",\"type\":\"frame\",\"x\":20,\"y\":20,"
                + "\"width\":400,\"height\":300,\"name\":\"订单域\"},"
                + "{\"id\":\"o1\",\"type\":\"rectangle\",\"x\":40,\"y\":60,"
                + "\"width\":160,\"height\":60,\"label\":{\"text\":\"创建订单\"},"
                + "\"group\":\"订单域\"},"
                + "{\"id\":\"o2\",\"type\":\"rectangle\",\"x\":240,\"y\":180,"
                + "\"width\":160,\"height\":60,\"label\":{\"text\":\"支付订单\"},"
                + "\"group\":\"订单域\"},"
                + "{\"id\":\"z1\",\"type\":\"rectangle\",\"x\":500,\"y\":40,"
                + "\"width\":140,\"height\":50,\"label\":{\"text\":\"独立节点\"},"
                + "\"group\":\"其它\"},"
                + "{\"id\":\"e1\",\"type\":\"arrow\",\"start\":{\"id\":\"o1\"},"
                + "\"end\":{\"id\":\"o2\"}}"
                + "]}";

        // 完整复刻 GenerationService 的调用顺序（v2-41）：bind → dedup → claimFramesOnly
        String pipeline = new SceneDeduper().dedup(
                new SceneBinder().bind(json), null);
        pipeline = planner.claimFramesOnly(pipeline);

        JsonNode out = mapper.readTree(pipeline);
        assertEquals(5, out.get("elements").size(), "管线不应增删元素");

        // 关键断言：每个图形的原生坐标一个像素都不动（REAL 与 LLM-NEW 逐值相等）
        assertNative("o1", 40, 60, 160, 60, out);
        assertNative("o2", 240, 180, 160, 60, out);
        assertNative("z1", 500, 40, 140, 50, out);
        assertNative("f-order", 20, 20, 400, 300, out);

        // 同时「画框认领」仍生效：frame.children 含同模块两个图形
        JsonNode frame = findFrame(pipeline, "订单域");
        assertNotNull(frame, "应存在「订单域」框");
        assertEquals(2, frame.get("children").size(),
                "框应认领同模块两个图形");
    }

    private void assertNative(String id, double x, double y,
            double w, double h, JsonNode out) {
        JsonNode el = null;
        for (JsonNode e : out.get("elements")) {
            if (id.equals(e.path("id").asText())) {
                el = e;
                break;
            }
        }
        assertNotNull(el, "缺失元素: " + id);
        assertEquals(x, el.get("x").asDouble(), 0.001, id + ".x");
        assertEquals(y, el.get("y").asDouble(), 0.001, id + ".y");
        assertEquals(w, el.get("width").asDouble(), 0.001, id + ".width");
        assertEquals(h, el.get("height").asDouble(), 0.001, id + ".height");
    }

    private JsonNode findFrame(String json, String name)
            throws Exception {
        for (JsonNode element : mapper.readTree(json).get("elements")) {
            if ("frame".equals(element.path("type").asText())
                    && name.equals(element.path("name").asText())) {
                return element;
            }
        }
        return null;
    }
}
