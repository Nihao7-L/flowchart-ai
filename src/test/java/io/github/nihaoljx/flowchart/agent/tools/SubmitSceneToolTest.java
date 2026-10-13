package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.agent.Observation;
import io.github.nihaoljx.flowchart.agent.ToolContext;
import io.github.nihaoljx.flowchart.graph.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.GraphValidator;
import io.github.nihaoljx.flowchart.graph.SceneBinder;
import io.github.nihaoljx.flowchart.graph.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.SceneSanitizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * submit_scene / validate_scene 单元测试（v2-55）
 *
 * <p>验收基线：合法小图零 issue 直通；空场景/缺参数被打回且带上
 * 点名化 issue；两个上下文槽位（processedScene / validScene）
 * 严格按"parseGate 过 / 零 issue"分级落位。
 */
class SubmitSceneToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 两框一线的最小合法场景（与 SceneBinderTest 的形态同源） */
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

    private SubmitSceneTool tool;
    private ValidateSceneTool validateTool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        SceneGate gate = new SceneGate(new SceneBinder(), new SceneDeduper(),
                new SceneSanitizer(), new ScenePlanner(),
                new GraphValidator(), new GeometryAudit());
        tool = new SubmitSceneTool(gate);
        validateTool = new ValidateSceneTool(gate);
        ctx = new ToolContext("画一张流程图", null);
    }

    @Test
    @DisplayName("合法小图 → 直通（零 issue），两个槽位都落位")
    void goodScenePasses() throws Exception {
        Observation obs = tool.execute(MAPPER.readTree(
                "{\"scene\":" + GOOD_SCENE + "}"), ctx);

        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        assertNotNull(ctx.validScene());
        assertNotNull(ctx.processedScene());
        assertTrue(obs.data().get("issueCount").asInt() == 0);
    }

    @Test
    @DisplayName("空场景 → 打回并带点名 issue；processedScene 落位、validScene 不落")
    void emptySceneRejected() throws Exception {
        Observation obs = tool.execute(MAPPER.readTree(
                "{\"scene\":{\"elements\":[]}}"), ctx);

        assertFalse(obs.ok());
        assertFalse(obs.issues().isEmpty());
        assertNotNull(ctx.processedScene());
        assertNull(ctx.validScene());
        assertTrue(obs.issues().get(0).reason().contains("场景为空"));
    }

    @Test
    @DisplayName("缺 scene 参数 → 失败 observation，上下文不落任何槽位")
    void missingSceneArgRejected() throws Exception {
        Observation obs = tool.execute(
                MAPPER.readTree("{}"), ctx);

        assertFalse(obs.ok());
        assertNull(ctx.processedScene());
        assertNull(ctx.validScene());
    }

    @Test
    @DisplayName("validate_scene：有场景时重跑校验；无场景时明确报'先提交'")
    void validateWithoutSubmitFailsFirst() {
        Observation obs = validateTool.execute(null, ctx);
        assertFalse(obs.ok());
        assertTrue(obs.summary().contains("先"));
    }

    @Test
    @DisplayName("validate_scene：提交合法场景后校验通过（0 issue）")
    void validateAfterGoodSubmit() throws Exception {
        tool.execute(MAPPER.readTree("{\"scene\":" + GOOD_SCENE + "}"), ctx);
        Observation obs = validateTool.execute(null, ctx);
        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        assertEquals(0, obs.data().get("issueCount").asInt());
    }
}
