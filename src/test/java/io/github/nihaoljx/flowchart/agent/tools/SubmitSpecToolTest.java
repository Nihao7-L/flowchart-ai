package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.GenericLayoutFamily;
import io.github.nihaoljx.flowchart.graph.layout.LayoutFamilyRegistry;
import io.github.nihaoljx.flowchart.graph.layout.SequenceLayout;
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
        tool = new SubmitSpecTool(gate, new LayoutFamilyRegistry(java.util.List.of(new GenericLayoutFamily(new GenericLayoutFamily.ScenePlannerHolder(new ScenePlanner(), new ElkLayoutEngine())), new SequenceLayout())));
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
    @DisplayName("单组分组：也合成画框（框由语义决定，不是数量决定）")
    void singleGroupGetsFrame() throws Exception {
        // 2026-10-16 口径修正：此前用「≥2 组才画框」的数量阈值把单组
        // 挡掉，等于拿"有几组"替代"要不要框"的语义判断 —— 明明是
        // 偷懒。现改为：LLM 显式声明了 group，就尊重它。单个模块
        // （如 AWS Cloud / 数据摄入流水线）同样是真实语义容器，必须
        // 画框；模型没声明分组时后端绝不自己造框。
        Observation obs = submit("""
                {"nodes":[
                  {"id":"c1","label":"Cognito","group":"AWS Cloud"},
                  {"id":"c2","label":"Lambda","group":"AWS Cloud"},
                  {"id":"c3","label":"DynamoDB","group":"AWS Cloud"}],
                 "edges":[
                  {"from":"c1","to":"c2"},
                  {"from":"c2","to":"c3"}]}
                """);
        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        JsonNode frame = null;
        int colored = 0;
        for (JsonNode el : elements) {
            if ("frame".equals(el.path("type").asText())) {
                frame = el;
            }
            if (el.has("backgroundColor")
                    && !el.path("backgroundColor").asText().isEmpty()) {
                colored++;
            }
        }
        assertNotNull(frame, "单组也应画框（AWS Cloud 是真实语义容器）");
        assertEquals("AWS Cloud", frame.path("name").asText(),
                "画框名应等于分组名");
        assertEquals(3, frame.path("children").size(),
                "画框应包住 3 个成员");
        assertEquals(3, colored, "该分组的 3 个节点都应有配色");
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

    @Test
    @DisplayName("几何 issue 不打回模型：audit 只报交叉/穿身 → 放行并落 validScene")
    void geometryIssuesDoNotBounceBack() throws Exception {
        // 2026-10-15 regress_arch2 教训：spec 路径坐标全由布局引擎出，
        // 几何 issue（交叉/穿身）打回给模型 = 让它盲改拓扑重抽，
        // 实测 62s 生成 → 27s 修正 → 仍未过 → 下一轮挂死 300s。
        // 几何问题只交布局层，模型只修它真正能修的。
        GeometryAudit geometryOnly = new GeometryAudit() {
            @Override
            public java.util.List<io.github.nihaoljx.flowchart.graph.validate.ValidationIssue> audit(
                    String json) {
                return java.util.List.of(
                        new io.github.nihaoljx.flowchart.graph.validate.ValidationIssue(
                                "geometry.crossing",
                                "连线 e1 与连线 e2 交叉",
                                "请绕开"),
                        new io.github.nihaoljx.flowchart.graph.validate.ValidationIssue(
                                "geometry.through",
                                "连线 e3 穿过了无关图形 n2",
                                "请绕开"));
            }
        };
        SceneGate gate = new SceneGate(new SceneBinder(),
                new SceneDeduper(), new SceneSanitizer(),
                new ScenePlanner(), new GraphValidator(),
                geometryOnly);
        SubmitSpecTool gated = new SubmitSpecTool(gate, new LayoutFamilyRegistry(java.util.List.of(new GenericLayoutFamily(new GenericLayoutFamily.ScenePlannerHolder(new ScenePlanner(), new ElkLayoutEngine())), new SequenceLayout())));
        Observation obs = gated.execute(MAPPER.readTree("""
                {"spec":{"nodes":[
                  {"id":"n1","label":"网关","group":"接入层"},
                  {"id":"n2","label":"用户服务","group":"服务层"},
                  {"id":"n3","label":"数据库","group":"数据层"}],
                 "edges":[
                  {"from":"n1","to":"n2"},
                  {"from":"n2","to":"n3"}]}}
                """), ctx);

        assertTrue(obs.ok(),
                "纯几何 issue 不该 fail，实际: " + obs.summary());
        assertNotNull(ctx.validScene(),
                "放行时必须落 validScene（AgentLoop 终态取自它）");
        assertTrue(obs.summary().contains("布局层自愈"),
                "摘要应说明几何问题交布局层: " + obs.summary());
    }

    @Test
    @DisplayName("几何 + 结构混合 issue → 仍 fail，但只点名模型能修的")
    void mixedIssuesStillFailButOnlyActionable() throws Exception {
        GeometryAudit geometryOnly = new GeometryAudit() {
            @Override
            public java.util.List<io.github.nihaoljx.flowchart.graph.validate.ValidationIssue> audit(
                    String json) {
                return java.util.List.of(
                        new io.github.nihaoljx.flowchart.graph.validate.ValidationIssue(
                                "geometry.crossing",
                                "连线 e1 与连线 e2 交叉",
                                "请绕开"));
            }
        };
        SceneGate gate = new SceneGate(new SceneBinder(),
                new SceneDeduper(), new SceneSanitizer(),
                new ScenePlanner(), new GraphValidator(),
                geometryOnly);
        SubmitSpecTool gated = new SubmitSpecTool(gate, new LayoutFamilyRegistry(java.util.List.of(new GenericLayoutFamily(new GenericLayoutFamily.ScenePlannerHolder(new ScenePlanner(), new ElkLayoutEngine())), new SequenceLayout())));
        // e1 引用不存在的节点 → 结构 issue（模型能修）
        Observation obs = gated.execute(MAPPER.readTree("""
                {"spec":{"nodes":[
                  {"id":"n1","label":"网关"}],
                 "edges":[
                  {"from":"n1","to":"ghost"}]}}
                """), ctx);

        assertFalse(obs.ok(), "结构 issue 仍应 fail");
        String payload = String.valueOf(obs.data());
        assertFalse(payload.contains("geometry.crossing"),
                "几何 issue 不该进修复单: " + payload);
        assertTrue(payload.contains("ghost"),
                "结构 issue 必须点名: " + payload);
    }
    @Test
    @DisplayName("节点 color → 命中具名色表（填充 + 描边），不必再手写坐标")
    void namedColorApplies() throws Exception {
        Observation obs = submit("""
                {"nodes":[
                  {"id":"n1","label":"编码器","color":"purple"},
                  {"id":"n2","label":"注意力","color":"pink"}],
                 "edges":[{"from":"n1","to":"n2"}]}
                """);

        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        JsonNode encoder = null;
        for (JsonNode el : elements) {
            if ("n1".equals(el.path("id").asText())) {
                encoder = el;
            }
        }
        assertNotNull(encoder, "应有 id=n1 的节点");
        assertEquals("#d0bfff",
                encoder.path("backgroundColor").asText(),
                "purple 的填充色由代码定，不由模型填");
        assertEquals("#7048e8",
                encoder.path("strokeColor").asText(),
                "purple 的描边色由代码定");
        assertEquals("solid", encoder.path("fillStyle").asText());
    }

    @Test
    @DisplayName("spec.title → 合成一行落在全图包围盒上沿的标题文字")
    void titleBecomesTextElement() throws Exception {
        Observation obs = submit("""
                {"title":"Transformer 架构",
                 "nodes":[{"id":"n1","label":"编码器"},
                          {"id":"n2","label":"解码器"}],
                 "edges":[{"from":"n1","to":"n2"}]}
                """);

        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        JsonNode elements = MAPPER.readTree(ctx.validScene())
                .get("elements");
        JsonNode title = null;
        for (JsonNode el : elements) {
            if ("text".equals(el.path("type").asText())) {
                title = el;
            }
        }
        assertNotNull(title, "应有一个 text 元素承载标题");
        assertEquals("Transformer 架构",
                title.path("text").asText());
        for (JsonNode el : elements) {
            if (el == title) {
                continue;
            }
            assertTrue(title.path("y").asDouble()
                            <= el.path("y").asDouble(),
                    "标题应在全图最上方");
        }
    }

    @Test
    @DisplayName("非法颜色名 → 机读修复单点名 spec.nodes[i].color")
    void badColorNameRejected() throws Exception {
        Observation obs = submit("""
                {"nodes":[{"id":"n1","label":"开始",
                           "color":"chartreuse"}],
                 "edges":[]}
                """);

        assertFalse(obs.ok());
        assertEquals("spec.nodes[0].color",
                obs.issues().get(0).field());
    }
}
