package io.github.nihaoljx.flowchart.graph.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * relation → 视觉样式编译（v2-40 P2）的单元测试。
 *
 * <p>只验证 {@link ScenePlanner#compileRelations} 这一纯函数：
 * 给定一组 JsonNode，确认关系语义被正确翻译成 strokeStyle / 端点形状，
 * 且对"无 relation / 非法 relation / 非 arrow"三类输入保持零副作用。
 */
class RelationCompileTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode arrowWithRelation(String rel) {
        ObjectNode a = mapper.createObjectNode();
        a.put("id", "e1");
        a.put("type", "arrow");
        ObjectNode start = mapper.createObjectNode();
        start.put("id", "n1");
        ObjectNode end = mapper.createObjectNode();
        end.put("id", "n2");
        a.set("start", start);
        a.set("end", end);
        if (rel != null) {
            a.put("relation", rel);
        }
        return a;
    }

    private List<JsonNode> listOf(ObjectNode... nodes) {
        List<JsonNode> list = new ArrayList<>();
        for (ObjectNode n : nodes) {
            list.add(n);
        }
        return list;
    }

    @Test
    @DisplayName("flow → 实线 + 末端三角")
    void flowCompilesToSolidTriangle() {
        ObjectNode a = arrowWithRelation("flow");
        assertTrue(ScenePlanner.compileRelations(listOf(a)));
        assertEquals("solid", a.get("strokeStyle").asText());
        assertEquals("triangle", a.get("endArrowhead").asText());
        assertFalse(a.has("startArrowhead"));
    }

    @Test
    @DisplayName("call → 实线 + 末端三角（与 flow 同款）")
    void callCompilesToSolidTriangle() {
        ObjectNode a = arrowWithRelation("call");
        ScenePlanner.compileRelations(listOf(a));
        assertEquals("solid", a.get("strokeStyle").asText());
        assertEquals("triangle", a.get("endArrowhead").asText());
    }

    @Test
    @DisplayName("async → 虚线 + 开三角")
    void asyncCompilesToDashedOutline() {
        ObjectNode a = arrowWithRelation("async");
        ScenePlanner.compileRelations(listOf(a));
        assertEquals("dashed", a.get("strokeStyle").asText());
        assertEquals("triangle_outline", a.get("endArrowhead").asText());
    }

    @Test
    @DisplayName("data → 点线，无端点形状")
    void dataCompilesToDotted() {
        ObjectNode a = arrowWithRelation("data");
        ScenePlanner.compileRelations(listOf(a));
        assertEquals("dotted", a.get("strokeStyle").asText());
        assertFalse(a.has("startArrowhead"));
        assertFalse(a.has("endArrowhead"));
    }

    @Test
    @DisplayName("one-to-many → 鸦脚基数端点，不动线型")
    void oneToManyCompilesToCrowfoot() {
        ObjectNode a = arrowWithRelation("one-to-many");
        ScenePlanner.compileRelations(listOf(a));
        assertEquals("crowfoot_one", a.get("startArrowhead").asText());
        assertEquals("crowfoot_many", a.get("endArrowhead").asText());
        assertFalse(a.has("strokeStyle"));
    }

    @Test
    @DisplayName("many-to-many → 两端鸦脚")
    void manyToManyCompilesToCrowfootBoth() {
        ObjectNode a = arrowWithRelation("many-to-many");
        ScenePlanner.compileRelations(listOf(a));
        assertEquals("crowfoot_many", a.get("startArrowhead").asText());
        assertEquals("crowfoot_many", a.get("endArrowhead").asText());
    }

    @Test
    @DisplayName("block → 起点横杠")
    void blockCompilesToBar() {
        ObjectNode a = arrowWithRelation("block");
        ScenePlanner.compileRelations(listOf(a));
        assertEquals("bar", a.get("startArrowhead").asText());
    }

    @Test
    @DisplayName("inherit → 实线 + 空心三角")
    void inheritCompilesToOutlineTriangle() {
        ObjectNode a = arrowWithRelation("inherit");
        ScenePlanner.compileRelations(listOf(a));
        assertEquals("solid", a.get("strokeStyle").asText());
        assertEquals("triangle_outline", a.get("endArrowhead").asText());
    }

    @Test
    @DisplayName("无 relation 的箭头：不被改动，且返回 false（零回归）")
    void noRelationUntouched() {
        ObjectNode a = arrowWithRelation(null);
        assertFalse(ScenePlanner.compileRelations(listOf(a)));
        assertFalse(a.has("strokeStyle"));
        assertFalse(a.has("startArrowhead"));
        assertFalse(a.has("endArrowhead"));
    }

    @Test
    @DisplayName("非法 relation：被忽略、不抛异常、不改样式")
    void unknownRelationIgnored() {
        ObjectNode a = arrowWithRelation("telepathy");
        assertFalse(ScenePlanner.compileRelations(listOf(a)));
        assertFalse(a.has("strokeStyle"));
    }

    @Test
    @DisplayName("非 arrow 元素（即使带 relation）被跳过")
    void nonArrowSkipped() {
        ObjectNode rect = mapper.createObjectNode();
        rect.put("id", "n1");
        rect.put("type", "rectangle");
        rect.put("relation", "flow");
        assertFalse(ScenePlanner.compileRelations(listOf(rect)));
        assertFalse(rect.has("strokeStyle"));
    }
}
