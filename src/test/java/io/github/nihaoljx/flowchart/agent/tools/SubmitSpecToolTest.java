package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;

/**
 * submit_spec 单元测试（S2 语义-坐标分离）
 *
 * <p>验收基线：合法 spec → 布局引擎排版 + 管线零 issue 直通，
 * 节点坐标被引擎重排（不再全在原点）；spec 层错误（缺参数/空节点/
 * 重复 id/悬空引用/自环）→ 机读修复单（field+reason+hint）。
 */
class SubmitSpecToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SubmitSpecTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        SceneGate gate = new SceneGate(new SceneBinder(),
                new SceneDeduper(), new SceneSanitizer(),
                new ScenePlanner(), new GraphValidator(),
                new GeometryAudit());
        tool = new SubmitSpecTool(gate, new ElkLayoutEngine(),
                new ScenePlanner());
        ctx = new ToolContext("画一张流程图", null);
    }

    private Observation submit(String specJson) throws Exception {
        return tool.execute(MAPPER.readTree(
                "{\"spec\":" + specJson + "}"), ctx);
    }

    @Test
    @DisplayName("合法 spec → 直通：布局引擎重排坐标，双槽位落位")
    void goodSpecPasses() throws Exception {
        Observation obs = submit("""
                {"nodes":[
                  {"id":"n1","label":"开始"},
                  {"id":"n2","label":"处理"},
                  {"id":"n3","label":"结束"}],
                 "edges":[
                  {"from":"n1","to":"n2"},
                  {"from":"n2","to":"n3"}]}
                """);

        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        assertNotNull(ctx.validScene());
        assertNotNull(ctx.processedScene());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        assertEquals(5, elements.size(), "3 节点 + 2 箭头");
        // 布局引擎确实重排了：节点坐标不全在原点
        boolean moved = false;
        for (JsonNode el : elements) {
            if ("rectangle".equals(el.path("type").asText())
                    && (el.path("x").asDouble() != 0
                        || el.path("y").asDouble() != 0)) {
                moved = true;
            }
        }
        assertTrue(moved, "节点坐标应被布局引擎重排");
    }

    @Test
    @DisplayName("绑定边一律由引擎写回路由（3 点起），直线边不再留给前端重算")
    void straightEdgesGetEngineRoute() throws Exception {
        Observation obs = submit("""
                {"nodes":[
                  {"id":"a","label":"甲","type":"rectangle"},
                  {"id":"b","label":"乙","type":"ellipse"}],
                 "edges":[
                  {"from":"a","to":"b","label":"流转"}]}
                """);

        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        JsonNode arrow = null;
        for (JsonNode el : elements) {
            if ("arrow".equals(el.path("type").asText())) {
                arrow = el;
            }
        }
        assertNotNull(arrow, "应有箭头");
        assertEquals("a", arrow.path("start").path("id").asText());
        assertEquals("b", arrow.path("end").path("id").asText());
        // 2026-10-15 二次改口径：回归实测前端重算分支 5 条箭头端点
        // 悬空 70~437px（ELK 写回的吸附分支 0 悬空）—— 直线边一律
        // 由引擎接管，补中点成 3 点共线折线（≥3 存活 SceneSanitizer）
        assertTrue(arrow.has("points"),
                "直线绑定边也应由引擎写回 points，实际: " + arrow);
        assertEquals(3, arrow.path("points").size(),
                "直线路由应补中点成 3 点");
        assertTrue(arrow.has("x") && arrow.has("y"),
                "写回时应一并落 x/y（首点绝对坐标）");
        assertEquals("流转", arrow.path("label").path("text").asText());
    }

    @Test
    @DisplayName("菱形参与的直线边 → 强制写回 3 点路由（前端锚点推导对菱形失效）")
    void diamondStraightEdgeGetsRoute() throws Exception {
        Observation obs = submit("""
                {"nodes":[
                  {"id":"a","label":"校验","type":"diamond"},
                  {"id":"b","label":"通过","type":"rectangle"}],
                 "edges":[
                  {"from":"a","to":"b","label":"命中"}]}
                """);

        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        JsonNode arrow = null;
        for (JsonNode el : elements) {
            if ("arrow".equals(el.path("type").asText())) {
                arrow = el;
            }
        }
        assertNotNull(arrow, "应有箭头");
        // 2026-10-15 修复：菱形端点的前端锚点推导会产生穿框对角线
        // （实测 12_er），菱形参与的边一律由引擎接管 —— 直线路由
        // 补中点成 3 点共线折线，恰好存活 SceneSanitizer 的
        // "绑定箭头只留 ≥3 点"摘除口径
        assertTrue(arrow.has("points"),
                "菱形边的直线也应由引擎写回 points，实际: " + arrow);
        assertEquals(3, arrow.path("points").size(),
                "直线路由应补中点成 3 点（≥3 才不被摘）");
        assertTrue(arrow.has("x") && arrow.has("y"),
                "写回时应一并落 x/y（首点绝对坐标）");
    }

    @Test
    @DisplayName("回边（环）→ 正交拐点写回，直线边不穿框")
    void backEdgeGetsBendPoints() throws Exception {
        Observation obs = submit("""
                {"nodes":[
                  {"id":"a","label":"输入账号密码"},
                  {"id":"b","label":"校验"},
                  {"id":"c","label":"提示错误"}],
                 "edges":[
                  {"from":"a","to":"b"},
                  {"from":"b","to":"c"},
                  {"from":"c","to":"a"}]}
                """);

        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        int maxPoints = 0;
        for (JsonNode el : elements) {
            if ("arrow".equals(el.path("type").asText())) {
                maxPoints = Math.max(maxPoints,
                        el.path("points").size());
            }
        }
        assertTrue(maxPoints > 2,
                "回边应有正交拐点（points > 2），实际最多 " + maxPoints);
    }

    @Test
    @DisplayName("edge 引用未知节点 → 机读修复单点名 spec.edges[i]")
    void danglingEdgeRejected() throws Exception {
        Observation obs = submit("""
                {"nodes":[{"id":"n1","label":"唯一"}],
                 "edges":[{"from":"n1","to":"ghost"}]}
                """);

        assertFalse(obs.ok());
        assertNull(ctx.validScene());
        assertEquals("spec.edges[0].to",
                obs.issues().get(0).field());
        assertTrue(obs.issues().get(0).reason()
                .contains("ghost"));
    }

    @Test
    @DisplayName("重复节点 id / 自环 / 非法 id → 逐条点名")
    void specValidationIssues() throws Exception {
        Observation obs = submit("""
                {"nodes":[
                  {"id":"n1","label":"甲"},
                  {"id":"n1","label":"乙"}],
                 "edges":[]}
                """);
        assertFalse(obs.ok());
        assertEquals("spec.nodes[1].id",
                obs.issues().get(0).field());

        obs = submit("""
                {"nodes":[{"id":"n1","label":"甲"}],
                 "edges":[{"from":"n1","to":"n1"}]}
                """);
        assertFalse(obs.ok());
        assertEquals("spec.edges[0]",
                obs.issues().get(0).field());

        obs = submit("""
                {"nodes":[{"id":"坏 id","label":"甲"}], "edges":[]}
                """);
        assertFalse(obs.ok());
        assertEquals("spec.nodes[0].id",
                obs.issues().get(0).field());
    }

    @Test
    @DisplayName("缺 spec 参数 / 空 nodes → 失败且不落槽位")
    void missingArgsRejected() throws Exception {
        Observation obs = tool.execute(
                MAPPER.readTree("{}"), ctx);
        assertFalse(obs.ok());
        assertNull(ctx.processedScene());

        obs = submit("{\"nodes\":[],\"edges\":[]}");
        assertFalse(obs.ok());
        assertEquals("spec.nodes",
                obs.issues().get(0).field());
    }

    @Test
    @DisplayName("只有节点没有连线（edges 省略）→ 允许通过")
    void nodesOnlyAllowed() throws Exception {
        Observation obs = submit(
                "{\"nodes\":[{\"id\":\"n1\",\"label\":\"独立框\"}]}");
        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        assertEquals(1, elements.size());
    }

    @Test
    @DisplayName("group 分组 → 节点自动配色 + 合成同名画框包住成员")
    void groupGetsColorsAndFrames() throws Exception {
        Observation obs = submit("""
                {"nodes":[
                  {"id":"a1","label":"网关","group":"接入层"},
                  {"id":"a2","label":"CDN","group":"接入层"},
                  {"id":"b1","label":"订单服务","group":"服务层"},
                  {"id":"b2","label":"支付服务","group":"服务层"}],
                 "edges":[
                  {"from":"a1","to":"b1","relation":"async"}]}
                """);
        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        JsonNode frameA = null;
        JsonNode frameB = null;
        int colored = 0;
        for (JsonNode el : elements) {
            if ("frame".equals(el.path("type").asText())) {
                if ("接入层".equals(el.path("name").asText())) {
                    frameA = el;
                }
                if ("服务层".equals(el.path("name").asText())) {
                    frameB = el;
                }
            }
            if (el.has("backgroundColor")
                    && !el.path("backgroundColor").asText().isEmpty()) {
                colored++;
            }
        }
        assertNotNull(frameA, "应有「接入层」画框");
        assertNotNull(frameB, "应有「服务层」画框");
        assertEquals(2, frameA.path("children").size(),
                "接入层框应包住 2 个成员");
        assertEquals(4, colored, "两个分组的 4 个节点都应有配色");
        assertTrue(elements.get(0).path("type").asText().equals("frame"),
                "画框应在数组最前（z 序最底）");
    }

    @Test
    @DisplayName("无分组 → 不画框不配色；relation → 编译成线型")
    void noGroupNoFrameRelationCompiles() throws Exception {
        Observation obs = submit("""
                {"nodes":[
                  {"id":"a","label":"父类"},
                  {"id":"b","label":"子类"}],
                 "edges":[
                  {"from":"a","to":"b","relation":"inherit"}]}
                """);
        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        for (JsonNode el : elements) {
            assertFalse("frame".equals(el.path("type").asText()),
                    "无分组不应画框");
            assertFalse(el.has("backgroundColor"),
                    "无分组不应配色");
        }
        // relation=inherit → solid + 空心三角箭头（RelationCatalog）
        JsonNode arrow = null;
        for (JsonNode el : elements) {
            if ("arrow".equals(el.path("type").asText())) {
                arrow = el;
            }
        }
        assertNotNull(arrow);
        assertEquals("solid", arrow.path("strokeStyle").asText(),
                "inherit 应编译为实线");
    }
}
