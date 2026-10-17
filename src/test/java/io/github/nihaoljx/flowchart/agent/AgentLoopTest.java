package io.github.nihaoljx.flowchart.agent;

import io.github.nihaoljx.flowchart.agent.tools.AgentTools;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.SceneDrift;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.service.PromptService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.function.BooleanSupplier;
import io.github.nihaoljx.flowchart.llm.AgentLlmClient;
import io.github.nihaoljx.flowchart.llm.AgentMessage;
import io.github.nihaoljx.flowchart.llm.AgentResult;
import io.github.nihaoljx.flowchart.llm.AgentTurn;
import io.github.nihaoljx.flowchart.llm.ToolCall;
import io.github.nihaoljx.flowchart.graph.layout.LayoutFamilyFixtures;

/**
 * agent 主循环单元测试（v2-55，桩客户端驱动）
 *
 * <p>覆盖四条关键路径：①一次提交直通；②提交被拒 → 修正指令回喂 →
 * 二次提交通过；③纯文本轮 → nudge 回喂；④步数超限 → PARTIAL
 * （不伪装成功）。同时验证"一批工具全部执行完再回喂"与
 * "修正指令走 buildFixBlock"。
 */
class AgentLoopTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GOOD_SPEC = """
            {"nodes":[
              {"id":"a","label":"开始"},
              {"id":"b","label":"结束"}
            ],"edges":[{"from":"a","to":"b"}]}
            """;

    private static final String EMPTY_SPEC = """
            {"nodes":[],"edges":[]}
            """;

    private ToolRegistry registry;
    private PromptService promptService;

    /** 按脚本逐轮返回的桩客户端；记下每次收到的消息历史供断言 */
    private static final class StubClient implements AgentLlmClient {
        private final List<AgentTurn> script;
        private int index = 0;
        private final List<List<AgentMessage>> requests = new java.util.ArrayList<>();

        StubClient(AgentTurn... turns) {
            script = List.of(turns);
        }

        @Override
        public AgentTurn respond(List<AgentMessage> messages,
                                 List<Map<String, Object>> tools,
                                 int timeoutSeconds) {
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

    private static AgentTurn submit(String specJson) throws Exception {
        ToolCall call = new ToolCall("c" + System.nanoTime(),
                "submit_spec",
                MAPPER.readTree("{\"spec\":" + specJson + "}"));
        return new AgentTurn("", List.of(call));
    }

    @BeforeEach
    void setUp() {
        promptService = new PromptService();
        registry = AgentTools.defaultRegistry(
                new SceneBinder(), new SceneDeduper(), new SceneSanitizer(),
                new ScenePlanner(), new GraphValidator(),
                new GeometryAudit(), new SceneDrift(), promptService,
                LayoutFamilyFixtures.defaultRegistry(),
                LayoutFamilyFixtures.defaultPromptRegistry());
    }

    @Test
    @DisplayName("一次提交直通 → COMPLETED，步数 1")
    void completesOnFirstSubmit() throws Exception {
        StubClient client = new StubClient(submit(GOOD_SPEC));
        AgentLoop loop = new AgentLoop(client, registry, promptService);

        AgentResult result = loop.run("SYS", "画流程图",
                null, Long.MAX_VALUE);

        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(1, result.steps());
        assertNotNull(result.sceneJson());
        assertTrue(result.sceneJson().contains("elements"));
    }

    @Test
    @DisplayName("空场景被拒 → 修正指令（buildFixBlock）回喂 → 二次提交通过")
    void fixLoopReruns() throws Exception {
        StubClient client = new StubClient(
                submit(EMPTY_SPEC), submit(GOOD_SPEC));
        AgentLoop loop = new AgentLoop(client, registry, promptService);

        AgentResult result = loop.run("SYS", "画流程图",
                null, Long.MAX_VALUE);

        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(2, result.steps());
        assertTrue(client.joinedUserText(1).contains("上一轮校验未通过"),
                "第二次请求应携带修正指令");
    }

    @Test
    @DisplayName("纯文本轮 → nudge 回喂，循环继续不退出")
    void nudgeOnTextOnlyTurn() throws Exception {
        StubClient client = new StubClient(
                new AgentTurn("好的，我来画。", null),
                submit(GOOD_SPEC));
        AgentLoop loop = new AgentLoop(client, registry, promptService);

        AgentResult result = loop.run("SYS", "画流程图",
                null, Long.MAX_VALUE);

        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(2, result.steps());
        assertTrue(client.joinedUserText(1).contains("你没有调用任何工具"));
    }

    @Test
    @DisplayName("客户端断开（取消标志置 true）→ CANCELLED，且不发动任何 LLM 调用")
    void cancelledStopsLoopBeforeAnyCall() throws Exception {
        StubClient client = new StubClient(submit(GOOD_SPEC));
        AgentLoop loop = new AgentLoop(client, registry, promptService);

        BooleanSupplier cancelled = () -> true;
        AgentResult result = loop.run("SYS", "画流程图",
                null, null, Long.MAX_VALUE, null, cancelled);

        assertEquals(AgentResult.Status.CANCELLED, result.status());
        assertEquals(0, result.steps());
        assertTrue(client.requests.isEmpty(),
                "取消后不应调用 LLM（不发下一次 respond）");
    }

    @Test
    @DisplayName("步数超限 → PARTIAL 且 sceneJson 为 null（无有效进度），不伪装成功")
    void exhaustsToPartial() throws Exception {
        StubClient client = new StubClient(
                new AgentTurn("...", null));
        AgentLoop loop = new AgentLoop(client, registry, promptService);

        AgentResult result = loop.run("SYS", "画流程图",
                null, Long.MAX_VALUE);

        assertEquals(AgentResult.Status.PARTIAL, result.status());
        assertEquals(AgentLoop.MAX_STEPS, result.steps());
        assertNull(result.sceneJson());
        assertTrue(result.reason().contains("步数上限"));
    }

    @Test
    @DisplayName("一批多工具调用全部执行（未知工具也不打断）")
    void batchCallsAllExecuted() throws Exception {
        ToolCall unknown = new ToolCall("c9", "fly_to_moon",
                MAPPER.readTree("{}"));
        StubClient client = new StubClient(new AgentTurn("", List.of(
                unknown,
                new ToolCall("c10", "read_contract",
                        MAPPER.readTree("{\"section\":\"arrow\"}")))),
                submit(GOOD_SPEC));
        AgentLoop loop = new AgentLoop(client, registry, promptService);

        AgentResult result = loop.run("SYS", "画流程图",
                null, Long.MAX_VALUE);

        assertEquals(AgentResult.Status.COMPLETED, result.status());
        assertEquals(2, result.steps());
    }
}
