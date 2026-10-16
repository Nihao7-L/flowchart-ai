package io.github.nihaoljx.flowchart.agent;

import io.github.nihaoljx.flowchart.agent.tools.AgentTools;
import io.github.nihaoljx.flowchart.critique.CritiqueIssue;
import io.github.nihaoljx.flowchart.critique.DiagramCritic;
import io.github.nihaoljx.flowchart.critique.NoopVisionCritic;
import io.github.nihaoljx.flowchart.critique.VisualFeedbackConfig;
import io.github.nihaoljx.flowchart.critique.VisualFeedbackService;
import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.GenericLayoutFamily;
import io.github.nihaoljx.flowchart.graph.layout.LayoutFamilyRegistry;
import io.github.nihaoljx.flowchart.graph.layout.SequenceLayout;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.SceneDrift;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.service.PromptService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.nihaoljx.flowchart.llm.AgentLlmClient;
import io.github.nihaoljx.flowchart.llm.AgentMessage;
import io.github.nihaoljx.flowchart.llm.AgentResult;
import io.github.nihaoljx.flowchart.llm.AgentTurn;
import io.github.nihaoljx.flowchart.llm.ToolCall;

/**
 * D2 视觉自纠闭环在 AgentLoop 上的集成测试：校验通过后若批判器发现问题，
 * 不立即 COMPLETED，而是回写**结构化**引导再来一轮；关闭时行为与旧版一致。
 */
class AgentLoopVisualFeedbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GOOD_SPEC = """
            {"nodes":[{"id":"a","label":"开始"},{"id":"b","label":"结束"}],
             "edges":[{"from":"a","to":"b"}]}
            """;

    /** 计数型假批判器：第 1 次返回 1 个问题，第 2 次起返回空（模拟修正收敛） */
    static final class FakeCritic implements DiagramCritic {
        int calls = 0;

        @Override
        public List<CritiqueIssue> critique(List<Map<String, Object>> e, String intent) {
            calls++;
            if (calls == 1) {
                return List.of(CritiqueIssue.of("a", CritiqueIssue.Category.OVERLAP,
                        "节点 a 与 b 重叠", "拉开间距", 2));
            }
            return List.of();
        }
    }

    /** 复用 AgentLoopTest 的桩客户端模式（同包私有，这里复制一份） */
    static final class StubClient implements AgentLlmClient {
        private final List<AgentTurn> script;
        private int index = 0;
        private final List<List<AgentMessage>> requests = new java.util.ArrayList<>();

        StubClient(AgentTurn... turns) {
            script = List.of(turns);
        }

        @Override
        public AgentTurn respond(List<AgentMessage> messages,
                                List<Map<String, Object>> tools, int timeoutSeconds) {
            requests.add(List.copyOf(messages));
            return script.get(Math.min(index++, script.size() - 1));
        }

        String joinedUserText(int requestIndex) {
            StringBuilder sb = new StringBuilder();
            for (AgentMessage m : requests.get(requestIndex)) {
                if ("user".equals(m.role())) {
                    sb.append(m.content()).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static AgentTurn submit(String spec) throws Exception {
        ToolCall call = new ToolCall("c" + System.nanoTime(), "submit_spec",
                MAPPER.readTree("{\"spec\":" + spec + "}"));
        return new AgentTurn("", List.of(call));
    }

    private static ToolRegistry registry() {
        PromptService ps = new PromptService();
        return AgentTools.defaultRegistry(new SceneBinder(), new SceneDeduper(),
                new SceneSanitizer(), new ScenePlanner(), new GraphValidator(),
                new GeometryAudit(), new SceneDrift(), ps, new LayoutFamilyRegistry(java.util.List.of(new GenericLayoutFamily(new GenericLayoutFamily.ScenePlannerHolder(new ScenePlanner(), new ElkLayoutEngine())), new SequenceLayout())));
    }

    @Test
    @DisplayName("D2: 校验通过后视觉自纠发现问题 → 不立即 COMPLETED，回写结构化引导再来一轮")
    void visualFeedbackDrivesOneMoreRound() throws Exception {
        FakeCritic critic = new FakeCritic();
        VisualFeedbackService vf = new VisualFeedbackService(
                new VisualFeedbackConfig(true, true, false),
                critic, new NoopVisionCritic());
        StubClient client = new StubClient(submit(GOOD_SPEC), submit(GOOD_SPEC));
        AgentLoop loop = new AgentLoop(client, registry(), new PromptService(), vf);

        AgentResult result = loop.run("SYS", "画流程图", null, Long.MAX_VALUE);

        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(2, result.steps());
        assertEquals(2, critic.calls); // 被调用两次：第一次有问题→再来一轮
        assertTrue(client.joinedUserText(1).contains("视觉自检"),
                "第二轮应携带视觉自纠引导");
    }

    @Test
    @DisplayName("D2: 视觉反馈关闭（null 服务）→ 一次提交即 COMPLETED，零额外轮")
    void noFeedbackWhenDisabled() throws Exception {
        StubClient client = new StubClient(submit(GOOD_SPEC));
        AgentLoop loop = new AgentLoop(client, registry(), new PromptService(), null);

        AgentResult result = loop.run("SYS", "画流程图", null, Long.MAX_VALUE);

        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(1, result.steps());
    }
}
