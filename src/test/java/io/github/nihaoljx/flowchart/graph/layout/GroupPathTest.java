package io.github.nihaoljx.flowchart.graph.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块路径读写（`group` 的两形态契约）。
 *
 * <p>验收基线：数组路径必须原样读出来 —— 这正是当初被
 * {@code asText()} 静默吃掉、导致组合态被拆成两个并排模块的那一处。
 */
class GroupPathTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("字符串 group 读成单名路径")
    void parsesFlatString() {
        assertEquals(List.of("接入层"),
                GroupPath.parse(node("{\"group\":\"接入层\"}")));
    }

    @Test
    @DisplayName("数组 group 从内到外原样读出（asText() 会读成空串）")
    void parsesNestedArray() {
        List<String> path = GroupPath.parse(
                node("{\"group\":[\"支付中\",\"处理中\",\"订单\"]}"));
        assertEquals(3, path.size());
        assertEquals("支付中", path.get(0), "第 0 项必须是最内层");
        assertEquals("订单", path.get(2));
    }

    @Test
    @DisplayName("无分组 / null / 数字 / 对象 → 空表，不抛异常")
    void tolerantOnOddInput() {
        assertTrue(GroupPath.parse(null).isEmpty());
        assertTrue(GroupPath.parse(node("{}")).isEmpty());
        assertTrue(GroupPath.parse(node("{\"group\":null}")).isEmpty());
        assertTrue(GroupPath.parse(node("{\"group\":7}")).isEmpty());
        assertTrue(GroupPath.parse(node("{\"group\":{\"a\":1}}")).isEmpty());
        assertTrue(GroupPath.parse(node("{\"group\":\"\"}")).isEmpty());
        assertTrue(GroupPath.parse(node("{\"group\":\"   \"}")).isEmpty());
        assertTrue(GroupPath.parse(node("{\"group\":[]}")).isEmpty());
    }

    @Test
    @DisplayName("归一：去空白、丢非字符串项、截断 24 字、同名去重")
    void normalizesNames() {
        assertEquals(List.of("订单"),
                GroupPath.parse(node("{\"group\":\"  订单  \"}")));
        assertEquals(List.of("订单", "处理中"),
                GroupPath.parse(node("{\"group\":[\"订单\", 7, \"处理中\"]}")));
        assertEquals(List.of("订单", "处理中"),
                GroupPath.parse(node("{\"group\":[\"订单\",\"订单\",\"处理中\"]}")),
                "同名层去重：一个名字在一条路径里只出现一次");
        assertEquals(List.of("订单"),
                GroupPath.of("订单"));
        assertTrue(GroupPath.of(null).isEmpty());
        String longName = "模".repeat(30);
        assertEquals(24,
                GroupPath.parse(node("{\"group\":\"" + longName + "\"}"))
                        .get(0).length());
    }

    @Test
    @DisplayName("单层写字符串、多层写数组、空路径不写字段")
    void writesTwoShapes() {
        ObjectNode one = MAPPER.createObjectNode();
        GroupPath.write(one, List.of("接入层"));
        assertTrue(one.path("group").isTextual(),
                "单层仍写字符串：契约两形态，既有单测与前端按字符串读");
        assertEquals("接入层", one.path("group").asText());

        ObjectNode many = MAPPER.createObjectNode();
        GroupPath.write(many, List.of("支付中", "处理中", "订单"));
        assertTrue(many.path("group").isArray());
        assertEquals(3, many.path("group").size());
        assertEquals("支付中", many.path("group").get(0).asText());

        ObjectNode empty = MAPPER.createObjectNode();
        GroupPath.write(empty, List.of());
        assertFalse(empty.has("group"), "无分组不该写空字段");
        ObjectNode nulled = MAPPER.createObjectNode();
        GroupPath.write(nulled, null);
        assertFalse(nulled.has("group"));
    }

    @Test
    @DisplayName("读写往返：解析后写回，形态与内容不变")
    void roundTrips() {
        JsonNode src = node("{\"group\":[\"支付中\",\"处理中\",\"订单\"]}");
        ObjectNode back = MAPPER.createObjectNode();
        GroupPath.write(back, GroupPath.parse(src));
        assertEquals(src.path("group"), back.path("group"));
    }

    @Test
    @DisplayName("容器键取后缀：a/c 与 b/c 是两个容器，同名的内层不合并")
    void containerKeyIsSuffix() {
        List<String> a = List.of("c", "a");
        List<String> b = List.of("c", "b");
        assertEquals("c/a", GroupPath.containerKey(a, 0));
        assertEquals("a", GroupPath.containerKey(a, 1));
        assertEquals("c/b", GroupPath.containerKey(b, 0));
        assertFalse(GroupPath.containerKey(a, 0)
                .equals(GroupPath.containerKey(b, 0)),
                "同名内层 c 必须因外层不同而分成两个容器");
        assertEquals("", GroupPath.containerKey(a, 2), "越界返回空键");
        assertEquals("", GroupPath.containerKey(null, 0));
        assertEquals("", GroupPath.containerKey(a, -1));
    }

    @Test
    @DisplayName("配色键/展示名：最内层到最外层用 / 连接")
    void keyAndDisplay() {
        assertEquals("支付中/处理中/订单",
                GroupPath.key(List.of("支付中", "处理中", "订单")));
        assertEquals("", GroupPath.key(null));
        assertEquals("", GroupPath.key(List.of()));
        assertEquals("处理中/订单", GroupPath.display(
                node("{\"group\":[\"处理中\",\"订单\"]}")));
        assertEquals("接入层", GroupPath.display(
                node("{\"group\":\"接入层\"}")));
        assertEquals("", GroupPath.display(node("{}")));
    }

    @Test
    @DisplayName("最内层归属：innermost 取第 0 项（分区配色按最内层算）")
    void innermostIsFirst() {
        assertEquals("支付中",
                GroupPath.innermost(List.of("支付中", "处理中", "订单")));
        assertEquals("接入层", GroupPath.innermost(List.of("接入层")));
        assertNull(GroupPath.innermost(List.of()));
        assertNull(GroupPath.innermost(null));
    }
}
