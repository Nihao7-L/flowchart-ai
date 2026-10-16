package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;

/**
 * update_elements 单元测试（v2-57）
 *
 * <p>验收基线：ops 应用语义（add/update/remove + id 纪律）、基座选择
 * （validScene 优先 → previousJson → 新建拒绝）、未动元素原样保留、
 * 增量产物走与全量相同的 SceneGate 校验（不回退红线）。
 */
class UpdateElementsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 两框一线的最小合法场景（与 SubmitSceneToolTest 同源） */
    private static final String GOOD_SCENE = """
            {"elements":[
              {"id":"a","type":"rectangle","x":0,"y":0,"width":160,"height":70,
               "label":{"text":"开始"}},
              {"id":"b","type":"rectangle","x":400,"y":0,"width":160,"height":70,
               "label":{"text":"结束"}},
              {"id":"a2b","type":"arrow","x":160,"y":35,
               "start":{"id":"a"},"end":{"id":"b"}}
            ]}
            """;

    private SceneGate gate;
    private UpdateElementsTool tool;

    @BeforeEach
    void setUp() {
        gate = new SceneGate(new SceneBinder(), new SceneDeduper(),
                new SceneSanitizer(), new ScenePlanner(),
                new GraphValidator(), new GeometryAudit());
        tool = new UpdateElementsTool(gate);
    }

    /** 编辑模式上下文：previousJson = GOOD_SCENE */
    private ToolContext editCtx() {
        return new ToolContext("把结束改成完成", null, GOOD_SCENE);
    }

    private static JsonNode ops(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("新建模式（无基线）调 update_elements 被拒并引导回 submit_scene")
    void rejectsNewMode() throws Exception {
        Observation obs = tool.execute(ops("""
                {"ops":[{"action":"remove","id":"a"}]}
                """), new ToolContext("画一张图", null));
        assertFalse(obs.ok());
        assertTrue(obs.summary().contains("submit_spec"),
                "应引导回 submit_spec: " + obs.summary());
    }

    @Test
    @DisplayName("add：在编辑基线上加一个元素，其余原样保留")
    void addAppendsAndKeepsOthers() throws Exception {
        ToolContext ctx = editCtx();
        Observation obs = tool.execute(ops("""
                {"ops":[{"action":"add","element":
                  {"id":"c","type":"rectangle","x":0,"y":200,"width":160,"height":70,
                   "label":{"text":"新增"}}}]}
                """), ctx);
        assertTrue(obs.ok(), obs.summary());
        assertNotNull(ctx.validScene());
        JsonNode els = MAPPER.readTree(ctx.validScene()).get("elements");
        assertEquals(4, els.size(), "2 框 1 线 + 1 新增 = 4");
        // 未动元素逐字段原样保留（不回退红线：增量不得动别人）
        JsonNode a = byId(els, "a");
        assertEquals("开始", a.path("label").path("text").asText());
        assertEquals(0, a.path("x").asInt(), 1e-9);
        JsonNode arrow = byId(els, "a2b");
        assertEquals("b", arrow.path("end").path("id").asText());
    }

    @Test
    @DisplayName("update：按 id 整元素替换，改的是目标、留的是旁人")
    void updateReplacesWholeElement() throws Exception {
        ToolContext ctx = editCtx();
        Observation obs = tool.execute(ops("""
                {"ops":[{"action":"update","element":
                  {"id":"b","type":"rectangle","x":400,"y":0,"width":160,"height":70,
                   "label":{"text":"完成"}}}]}
                """), ctx);
        assertTrue(obs.ok(), obs.summary());
        JsonNode els = MAPPER.readTree(ctx.validScene()).get("elements");
        assertEquals("完成", byId(els, "b").path("label").path("text").asText());
        assertEquals("开始", byId(els, "a").path("label").path("text").asText());
    }

    @Test
    @DisplayName("update 是整元素替换：残缺替换体（缺尺寸）被校验器拦，糊弄不过去")
    void updateIsReplaceNotMerge() throws Exception {
        ToolContext ctx = editCtx();
        // 故意不给 width/height —— 整元素替换语义下这就是残缺元素，
        // 校验器必须拦（若实现了字段级合并，这里反而会"静默成功"）
        Observation obs = tool.execute(ops("""
                {"ops":[{"action":"update","element":
                  {"id":"b","type":"rectangle","x":400,"y":0,
                   "label":{"text":"完成"}}}]}
                """), ctx);
        assertFalse(obs.ok(),
                "残缺替换体应被校验器拦下（证明 update 是替换不是合并）");
        assertTrue(obs.summary().contains("校验未通过"), obs.summary());
        assertNull(ctx.validScene());
    }

    @Test
    @DisplayName("remove：按 id 删元素")
    void removeDeletes() throws Exception {
        ToolContext ctx = editCtx();
        Observation obs = tool.execute(ops("""
                {"ops":[{"action":"remove","id":"a2b"}]}
                """), ctx);
        assertTrue(obs.ok(), obs.summary());
        JsonNode els = MAPPER.readTree(ctx.validScene()).get("elements");
        assertEquals(2, els.size());
        assertNull(byId(els, "a2b"));
    }

    @Test
    @DisplayName("remove→add 同 id = 替换，不算冲突")
    void removeThenAddSameIdIsLegal() throws Exception {
        ToolContext ctx = editCtx();
        Observation obs = tool.execute(ops("""
                {"ops":[
                  {"action":"remove","id":"b"},
                  {"action":"add","element":
                    {"id":"b","type":"diamond","x":400,"y":0,"width":160,"height":90,
                     "label":{"text":"完成？"}}}
                ]}
                """), ctx);
        assertTrue(obs.ok(), obs.summary());
        JsonNode b = byId(MAPPER.readTree(ctx.validScene())
                .get("elements"), "b");
        assertEquals("diamond", b.path("type").asText());
    }

    @Test
    @DisplayName("id 纪律：update/remove 不存在的 id、add 冲突 id、add 缺 id 全部 fail 并点名")
    void idDiscipline() throws Exception {
        ToolContext ctx = editCtx();
        Observation up = tool.execute(ops("""
                {"ops":[{"action":"update","element":{"id":"ghost","type":"rectangle"}}]}
                """), ctx);
        assertFalse(up.ok());
        assertTrue(up.summary().contains("ghost"), up.summary());

        Observation rm = tool.execute(ops("""
                {"ops":[{"action":"remove","id":"ghost"}]}
                """), ctx);
        assertFalse(rm.ok());
        assertTrue(rm.summary().contains("ghost"), rm.summary());

        Observation clash = tool.execute(ops("""
                {"ops":[{"action":"add","element":
                  {"id":"a","type":"rectangle","x":0,"y":0}}]}
                """), ctx);
        assertFalse(clash.ok());
        assertTrue(clash.summary().contains("a"), clash.summary());

        Observation noId = tool.execute(ops("""
                {"ops":[{"action":"add","element":{"type":"rectangle","x":0,"y":0}}]}
                """), ctx);
        assertFalse(noId.ok());
        assertTrue(noId.summary().contains("id"), noId.summary());
        // 失败不得写 validScene（半成品不能当成品）
        assertNull(ctx.validScene());
    }

    @Test
    @DisplayName("未知 action 与空 ops 被拒")
    void rejectsBadOps() throws Exception {
        ToolContext ctx = editCtx();
        assertFalse(tool.execute(ops(
                "{\"ops\":[{\"action\":\"move\",\"id\":\"a\"}]}"),
                ctx).ok());
        assertFalse(tool.execute(ops("{\"ops\":[]}"), ctx).ok());
        assertFalse(tool.execute(ops("{}"), ctx).ok());
    }

    @Test
    @DisplayName("多轮增量：第二轮基座取 validScene（本轮最新通过态）")
    void secondRoundUsesValidScene() throws Exception {
        ToolContext ctx = editCtx();
        Observation first = tool.execute(ops("""
                {"ops":[{"action":"add","element":
                  {"id":"c","type":"rectangle","x":0,"y":200,"width":160,"height":70,
                   "label":{"text":"新增"}}}]}
                """), ctx);
        assertTrue(first.ok(), first.summary());
        assertNotNull(ctx.validScene(), "第一轮应写入 validScene 作为下一轮基座");

        Observation second = tool.execute(ops("""
                {"ops":[{"action":"update","element":
                  {"id":"c","type":"rectangle","x":0,"y":200,"width":160,"height":70,
                   "label":{"text":"改好了"}}}]}
                """), ctx);
        assertTrue(second.ok(), second.summary());
        JsonNode els = MAPPER.readTree(ctx.validScene()).get("elements");
        assertEquals("改好了", byId(els, "c").path("label").path("text").asText());
        assertEquals(4, els.size());
    }

    @Test
    @DisplayName("增量产物走同一条校验闸门：删掉被箭头引用的元素会被 GraphValidator 拦")
    void incrementGoesThroughValidation() throws Exception {
        ToolContext ctx = editCtx();
        // 删掉 a 但留下 a→b 的箭头 = 悬空引用，校验器必须拦（不回退红线：
        // 增量不降校验标准）
        Observation obs = tool.execute(ops("""
                {"ops":[{"action":"remove","id":"a"}]}
                """), ctx);
        assertFalse(obs.ok(), "删被引用元素应被校验器拦下");
        assertTrue(obs.summary().contains("校验未通过")
                        || obs.issues() != null && !obs.issues().isEmpty(),
                obs.summary());
        assertNull(ctx.validScene());
    }

    @Test
    @DisplayName("编辑保真对增量天然通过：未动元素 id 保留率 100% 不触发 detectRewrite")
    void editFidelityPassesNaturally() throws Exception {
        ToolContext ctx = editCtx();
        Observation obs = tool.execute(ops("""
                {"ops":[{"action":"update","element":
                  {"id":"b","type":"rectangle","x":400,"y":0,"width":160,"height":70,
                   "label":{"text":"完成"}}}]}
                """), ctx);
        assertTrue(obs.ok(), "2/3 id 保留（66%>30%）不应触发重画报警: "
                + obs.summary());
        assertTrue(ctx.rewriteChecked(), "首次提交应标记保真检查已做");
    }

    private static JsonNode byId(JsonNode elements, String id) {
        for (JsonNode el : elements) {
            if (id.equals(el.path("id").asText())) {
                return el;
            }
        }
        return null;
    }

    @Test
    @DisplayName("sanity： GOOD_SCENE 本身能被全量通道通过（基线有效）")
    void baselineSceneIsValid() {
        ToolContext ctx = editCtx();
        SceneGate.Outcome out = gate.process(GOOD_SCENE, ctx);
        assertTrue(out.parseable());
        assertTrue(out.issues().isEmpty(),
                "基线场景必须本身合法，否则测试基座就是错的: "
                        + out.issues());
        assertNotEquals("", out.json());
    }
}
