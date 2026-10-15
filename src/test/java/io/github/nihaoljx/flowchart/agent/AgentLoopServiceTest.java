package io.github.nihaoljx.flowchart.agent;

import io.github.nihaoljx.flowchart.llm.LlmProperties;
import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.SceneDrift;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.service.PromptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentLoopService} 的装配与候选解析测试
 *
 * <p>候选解析是 agent 模式的"选人"逻辑，错了会把请求发给没探针过的
 * 模型或干脆发不出去，必须锁死三条规则：精确匹配 &gt; tier 最小 &gt; null。
 */
class AgentLoopServiceTest {

    private static LlmProperties.Candidate candidate(
            String name, String model, int tier) {
        LlmProperties.Candidate c = new LlmProperties.Candidate();
        c.setName(name);
        c.setModel(model);
        c.setTier(tier);
        c.setBaseUrl("https://example.invalid/v1/chat/completions");
        c.setApiKey("k-test");
        return c;
    }

    @Test
    @DisplayName("候选表为空 → 解析不出，构造 fail-fast 带可读信息")
    void emptyCandidatesFailFast() {
        assertThrows(IllegalStateException.class,
                () -> new AgentLoopService(
                        new SceneBinder(), new SceneDeduper(),
                        new SceneSanitizer(), new ScenePlanner(),
                        new GraphValidator(), new GeometryAudit(),
                        new SceneDrift(), new PromptService(),
                        new ElkLayoutEngine(),
                        new LlmProperties(), ""));
    }

    @Test
    @DisplayName("配置留空 → 取 tier 最小者")
    void blankPreferencePicksLowestTier() {
        LlmProperties props = new LlmProperties();
        props.setCandidates(List.of(
                candidate("b", "model-b", 2),
                candidate("a", "model-a", 1)));
        LlmProperties.Candidate picked = AgentLoopService
                .pickCandidate(props.getCandidates(), "");
        assertNotNull(picked);
        assertEquals("model-a", picked.getModel());
    }

    @Test
    @DisplayName("指定 model id → 精确匹配（同 id 多条时取 tier 小的）")
    void preferredModelMatchesById() {
        LlmProperties props = new LlmProperties();
        props.setCandidates(List.of(
                candidate("fast", "deepseek-v4.1-flash", 2),
                candidate("main", "glm-5.3-flash", 1),
                candidate("fast-alt", "deepseek-v4.1-flash", 3)));
        LlmProperties.Candidate picked = AgentLoopService
                .pickCandidate(props.getCandidates(),
                        "deepseek-v4.1-flash");
        assertNotNull(picked);
        assertEquals("fast", picked.getName());
    }

    @Test
    @DisplayName("指定的 model 不在表里 → null（构造器据此 fail-fast）")
    void unknownModelReturnsNull() {
        LlmProperties props = new LlmProperties();
        props.setCandidates(List.of(
                candidate("a", "model-a", 1)));
        assertNull(AgentLoopService.pickCandidate(
                props.getCandidates(), "no-such-model"));
    }

    @Test
    @DisplayName("缺 baseUrl/apiKey 的残缺候选不参与解析")
    void incompleteCandidateSkipped() {
        LlmProperties.Candidate broken = candidate(
                "broken", "model-broken", 1);
        broken.setBaseUrl(null);
        LlmProperties props = new LlmProperties();
        props.setCandidates(List.of(
                broken,
                candidate("ok", "model-ok", 2)));
        LlmProperties.Candidate picked = AgentLoopService
                .pickCandidate(props.getCandidates(), "");
        assertNotNull(picked);
        assertEquals("model-ok", picked.getModel());
    }
}
