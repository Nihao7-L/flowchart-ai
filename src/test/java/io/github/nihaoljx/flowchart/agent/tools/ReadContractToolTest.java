package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.agent.Observation;
import io.github.nihaoljx.flowchart.service.PromptService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * read_contract 单元测试（v2-55）：片段与整块契约同源渲染；
 * 形状名归一（rectangle → shape）；未知分支明确报错并列出允许值。
 */
class ReadContractToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReadContractTool tool;

    @BeforeEach
    void setUp() {
        tool = new ReadContractTool(new PromptService());
    }

    @Test
    @DisplayName("shape 分支 → 片段含字段与必填键标注")
    void shapeSectionRenders() throws Exception {
        Observation obs = tool.execute(
                MAPPER.readTree("{\"section\":\"shape\"}"), null);

        assertTrue(obs.ok(), "应成功，实际: " + obs.summary());
        String text = obs.data().path("text").asText();
        assertTrue(text.contains("rectangle"));
        assertTrue(text.contains("必填"));
    }

    @Test
    @DisplayName("rectangle → 归一到 shape 分支")
    void shapeAliasNormalized() throws Exception {
        Observation obs = tool.execute(
                MAPPER.readTree("{\"section\":\"rectangle\"}"), null);
        assertTrue(obs.ok());
        assertTrue(obs.summary().contains("shape"));
    }

    @Test
    @DisplayName("未知分支 → 失败并列出允许值")
    void unknownBranchRejected() throws Exception {
        Observation obs = tool.execute(
                MAPPER.readTree("{\"section\":\"bogus\"}"), null);
        assertFalse(obs.ok());
        assertTrue(obs.summary().contains("未知契约分支"));
        assertTrue(obs.summary().contains("arrow"));
    }

    @Test
    @DisplayName("arrow 分支带上绑定二选一的 BRANCH_NOTES 口径")
    void arrowSectionKeepsNotes() throws Exception {
        Observation obs = tool.execute(
                MAPPER.readTree("{\"section\":\"arrow\"}"), null);
        assertTrue(obs.ok());
        String text = obs.data().path("text").asText();
        assertTrue(text.contains("start/end"));
    }
}
