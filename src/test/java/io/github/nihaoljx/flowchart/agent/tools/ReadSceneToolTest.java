package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * read_scene 单元测试（v2-55）：统计 + 概览有界；focusIds 精确取全量；
 * 缺失 id 进 missingIds（不静默丢）。
 */
class ReadSceneToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCENE = """
            {"elements":[
              {"id":"a","type":"rectangle","x":0,"y":0,"width":160,"height":70,
               "label":{"text":"开始"}},
              {"id":"b","type":"rectangle","x":400,"y":0,"width":160,"height":70,
               "label":{"text":"结束"}},
              {"id":"a2b","type":"arrow","x":160,"y":35,
               "start":{"id":"a"},"end":{"id":"b"}}
            ]}
            """;

    private ReadSceneTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        tool = new ReadSceneTool();
        ctx = new ToolContext("画一张流程图", null);
        ctx.processedScene(SCENE);
    }

    @Test
    @DisplayName("无参 → 统计 + 概览（byType 计数正确）")
    void overviewWithoutFocus() throws Exception {
        Observation obs = tool.execute(MAPPER.readTree("{}"), ctx);

        assertTrue(obs.ok());
        assertEquals(3, obs.data().get("totalElements").asInt());
        assertEquals(2, obs.data().path("byType").path("rectangle").asInt());
        assertEquals(1, obs.data().path("byType").path("arrow").asInt());
        assertEquals(3, obs.data().path("outline").size());
    }

    @Test
    @DisplayName("focusIds → 命中元素全量返回；未命中进 missingIds")
    void focusReturnsFullElements() throws Exception {
        Observation obs = tool.execute(MAPPER.readTree(
                "{\"focusIds\":[\"a\",\"ghost\"]}"), ctx);

        assertTrue(obs.ok());
        assertEquals(1, obs.data().path("focus").size());
        assertEquals("开始",
                obs.data().path("focus").get(0).path("label").path("text").asText());
        assertEquals(1, obs.data().path("missingIds").size());
        assertEquals("ghost", obs.data().path("missingIds").get(0).asText());
    }

    @Test
    @DisplayName("无场景可读 → 明确失败提示（先 submit_scene）")
    void failsWithoutScene() {
        Observation obs = tool.execute(null, new ToolContext(null, null));
        assertFalse(obs.ok());
        assertTrue(obs.summary().contains("submit_scene"));
    }
}
