package io.github.nihaoljx.flowchart.graph.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SequenceLayout 单测：锁死时序图排版的三条画法铁律
 * （参与者横排 / 生命线竖虚线 / 消息按 edges 序逐行水平）。
 */
class SequenceLayoutTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Map<String, SpecNode> nodes(
            String... idLabelPairs) {
        Map<String, SpecNode> map = new LinkedHashMap<>();
        for (int i = 0; i < idLabelPairs.length; i += 2) {
            map.put(idLabelPairs[i], new SpecNode(
                    idLabelPairs[i], idLabelPairs[i + 1],
                    "rectangle", null, null));
        }
        return map;
    }

    /** 占位场景：节点在原点带尺寸，箭头只带绑定（buildScene 的产物形态） */
    private static String placeholderScene(
            Map<String, SpecNode> nodes, List<SpecEdge> edges)
            throws Exception {
        var root = M.createObjectNode();
        var elements = root.putArray("elements");
        for (SpecNode n : nodes.values()) {
            var el = elements.addObject();
            el.put("id", n.id());
            el.put("type", "rectangle");
            el.put("x", 0);
            el.put("y", 0);
            el.put("width", 160);
            el.put("height", 48);
            el.putObject("label").put("text", n.label());
        }
        int i = 0;
        for (SpecEdge e : edges) {
            var el = elements.addObject();
            el.put("id", "arrow_" + i++);
            el.put("type", "arrow");
            el.putObject("start").put("id", e.from());
            el.putObject("end").put("id", e.to());
            if (e.label() != null) {
                el.putObject("label").put("text", e.label());
            }
        }
        return M.writeValueAsString(root);
    }

    private static JsonNode elementById(JsonNode root, String id) {
        for (JsonNode el : root.get("elements")) {
            if (id.equals(el.path("id").asText())) {
                return el;
            }
        }
        return null;
    }

    @Test
    @DisplayName("参与者横排等距、同 y；消息按 edges 序逐行、行距恒定")
    void participantsRowAndTimedMessages() throws Exception {
        Map<String, SpecNode> nodes = nodes(
                "user", "用户", "fe", "前端", "auth", "认证服务");
        List<SpecEdge> edges = List.of(
                new SpecEdge("user", "fe", "输入密码", null),
                new SpecEdge("fe", "auth", "POST /login", null),
                new SpecEdge("auth", "fe", "返回 Token", null));
        SequenceLayout layout = new SequenceLayout();
        String out = layout.arrange(
                placeholderScene(nodes, edges), nodes, edges);
        JsonNode root = M.readTree(out);

        JsonNode user = elementById(root, "user");
        JsonNode fe = elementById(root, "fe");
        JsonNode auth = elementById(root, "auth");
        assertNotNull(user);
        assertNotNull(fe);
        assertNotNull(auth);
        assertEquals(user.get("y").asDouble(), fe.get("y").asDouble());
        assertEquals(fe.get("y").asDouble(), auth.get("y").asDouble());
        double colW = fe.get("x").asDouble() - user.get("x").asDouble();
        assertTrue(colW > 0, "参与者应自左向右横排");
        assertEquals(colW,
                auth.get("x").asDouble() - fe.get("x").asDouble(),
                1e-6, "列间距应恒定");

        double prevY = Double.NEGATIVE_INFINITY;
        for (int j = 0; j < edges.size(); j++) {
            JsonNode msg = elementById(root, "msg_" + j);
            assertNotNull(msg, "第 " + j + " 条消息应存在");
            assertEquals("arrow", msg.get("type").asText());
            double y = msg.get("y").asDouble();
            assertTrue(y > prevY, "消息应按 edges 数组序自上而下");
            if (j > 0) {
                // 坐标统一 snap 到 20 栅格：64 → 60
                assertEquals(60.0, y - prevY, 1e-6,
                        "行距应恒定（栅格吸附后 60px）");
            }
            prevY = y;
            JsonNode points = msg.get("points");
            assertEquals(2, points.size());
            assertEquals(0, points.get(0).get(0).asInt());
            assertEquals(0, points.get(0).get(1).asInt(),
                    "消息箭头应是水平线（dy=0）");
        }
        assertEquals("输入密码",
                elementById(root, "msg_0").path("label")
                        .path("text").asText());
        assertEquals("POST /login",
                elementById(root, "msg_1").path("label")
                        .path("text").asText());
    }

    @Test
    @DisplayName("每个参与者一条竖直虚线生命线，落在列中心、贯到图底")
    void lifelinesDashedAtColumnCenters() throws Exception {
        Map<String, SpecNode> nodes = nodes("a", "A", "b", "B");
        List<SpecEdge> edges = List.of(
                new SpecEdge("a", "b", "m1", null),
                new SpecEdge("b", "a", "m2", null));
        SequenceLayout layout = new SequenceLayout();
        JsonNode root = M.readTree(layout.arrange(
                placeholderScene(nodes, edges), nodes, edges));

        JsonNode lifeA = elementById(root, "life_a");
        JsonNode lifeB = elementById(root, "life_b");
        assertNotNull(lifeA);
        assertNotNull(lifeB);
        assertEquals("dashed", lifeA.get("strokeStyle").asText());
        assertEquals(2, lifeA.get("width").asInt());
        double lastMsgY = elementById(root, "msg_1")
                .get("y").asDouble();
        assertTrue(lifeA.get("y").asDouble()
                        + lifeA.get("height").asDouble()
                        >= lastMsgY + 40,
                "生命线应贯过最后一条消息");
        JsonNode boxA = elementById(root, "a");
        double centerA = boxA.get("x").asDouble()
                + boxA.get("width").asDouble() / 2;
        assertEquals(centerA, lifeA.get("x").asDouble() + 1, 1e-6,
                "生命线应落在参与者列中心");
    }

    @Test
    @DisplayName("注册表：sequence 命中、未知值降级 generic、types 含两者")
    void registryRouting() {
        LayoutFamilyRegistry registry = new LayoutFamilyRegistry(
                List.of(new GenericLayoutFamily(
                                new GenericLayoutFamily
                                        .ScenePlannerHolder(
                                        new ScenePlanner(),
                                        new ElkLayoutEngine())),
                        new SequenceLayout()));
        assertEquals("sequence",
                registry.forType("sequence").type());
        assertEquals("generic",
                registry.forType("sequencex").type(),
                "未知图型应降级 generic");
        assertEquals("generic", registry.forType(null).type());
        assertEquals("generic", registry.forType("").type());
        assertTrue(registry.has("sequence"));
        assertTrue(registry.has("generic"));
        assertTrue(registry.types().contains("sequence"));
    }
}
