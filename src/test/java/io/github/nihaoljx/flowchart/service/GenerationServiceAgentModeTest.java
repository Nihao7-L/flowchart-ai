package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.llm.AgentResult;
import io.github.nihaoljx.flowchart.agent.AgentLoopService;
import io.github.nihaoljx.flowchart.llm.LlmProvider;
import io.github.nihaoljx.flowchart.session.ContextAssembler;
import io.github.nihaoljx.flowchart.session.ContextBudget;
import io.github.nihaoljx.flowchart.session.ConversationReplay;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.SceneDrift;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.model.ChatEvent;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GenerationService} 的 agent 分支测试（v2-55 步骤 3）
 *
 * <p>三条必须锁死的行为：
 * <ol>
 *   <li>COMPLETED → 场景盖章落库、result 终态事件；</li>
 *   <li>PARTIAL 带可解析进度 → 该进度作为兜底图下发（不丢进度）；</li>
 *   <li>PARTIAL 无进度且无原图 → 合成占位框下发（不给空画布）。</li>
 * </ol>
 * 另锁一条：flag 关（provider 返回 null）→ legacy 路径接管。
 */
class GenerationServiceAgentModeTest {

    private static final String VALID_SCENE = """
            {"elements":[{"id":"n1","type":"rectangle",
            "x":0,"y":0,"width":100,"height":60,
            "label":{"text":"A"}}]}""";

    private LlmProvider llmProvider;
    private SessionStore sessionStore;
    private AgentLoopService agentService;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentLoopService> agentProvider =
            mock(ObjectProvider.class);

    private GenerationService service;

    @BeforeEach
    void setUp() {
        llmProvider = mock(LlmProvider.class);
        sessionStore = mock(SessionStore.class);
        agentService = mock(AgentLoopService.class);
        when(agentProvider.getIfAvailable())
                .thenReturn(agentService);

        ConversationReplay replay = new ConversationReplay();
        org.springframework.test.util.ReflectionTestUtils
                .setField(replay, "enabled", false);
        ContextAssembler contextAssembler = new ContextAssembler(
                new PromptService(), new ContextBudget(), replay);

        service = new GenerationService(
                llmProvider, agentProvider,
                new GraphValidator(), new SceneBinder(),
                new SceneDeduper(), new SceneSanitizer(),
                new SceneDrift(), new ScenePlanner(),
                sessionStore, contextAssembler,
                new GeometryAudit(), new PromptService());
    }

    private List<ChatEvent> events(SseEmitter emitter) throws Exception {
        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("agent COMPLETED → 场景盖章落库 + result 终态")
    void agentCompletedDeliversScene() throws Exception {
        when(agentService.run(any(), any(), anyList(),
                any(), anyLong(), any())).thenReturn(
                new AgentResult(AgentResult.Status.COMPLETED,
                        VALID_SCENE, 1, "校验通过"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "画个框", emitter);

        List<ChatEvent> all = events(emitter);
        ChatEvent terminal = all.get(all.size() - 1);
        assertEquals("result", terminal.type());
        assertTrue(terminal.data().contains("\"n1\""));
        assertTrue(terminal.data().contains("schemaVersion"),
                "下发场景必须盖 IR 版本号");
        // 落库：saveModel 写回会话
        verify(sessionStore).setCurrentModel(
                org.mockito.ArgumentMatchers.eq("s1"),
                any());
    }

    @Test
    @DisplayName("agent PARTIAL 带可解析进度 → 进度图下发（不丢进度）")
    void agentPartialDeliversProgress() throws Exception {
        when(agentService.run(any(), any(), anyList(),
                any(), anyLong(), any())).thenReturn(
                new AgentResult(AgentResult.Status.PARTIAL,
                        VALID_SCENE, 8,
                        "步数上限 8 轮未通过校验"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画个框", emitter);

        List<ChatEvent> all = events(emitter);
        ChatEvent terminal = all.get(all.size() - 1);
        assertEquals("result", terminal.type());
        assertTrue(terminal.data().contains("\"n1\""),
                "PARTIAL 的最后一份可解析进度必须下发");
    }

    @Test
    @DisplayName("agent PARTIAL 无进度无原图 → 占位框兜底（不给空画布）")
    void agentPartialFallsBackToPlaceholder() throws Exception {
        when(agentService.run(any(), any(), anyList(),
                any(), anyLong(), any())).thenReturn(
                new AgentResult(AgentResult.Status.PARTIAL,
                        null, 3, "总预算耗尽"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画个框", emitter);

        List<ChatEvent> all = events(emitter);
        ChatEvent terminal = all.get(all.size() - 1);
        assertEquals("result", terminal.type());
        assertTrue(terminal.data().contains(
                "chartflow-fallback-note"),
                "无进度时必须合成占位框");
        // 占位兜底不落库：不污染下一次编辑的起点
        verify(sessionStore, never())
                .setCurrentModel(any(), any());
    }

    @Test
    @DisplayName("flag 关（provider 拿不到 bean）→ legacy 路径接管")
    void flagOffFallsBackToLegacy() throws Exception {
        when(agentProvider.getIfAvailable()).thenReturn(null);
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(VALID_SCENE);

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画个框", emitter);

        List<ChatEvent> all = events(emitter);
        ChatEvent terminal = all.get(all.size() - 1);
        assertEquals("result", terminal.type());
        // legacy 特征：llmProvider（网关接口）被调过
        verify(llmProvider).chat(anyList(), anyInt());
        verify(agentService, never())
                .run(any(), any(), anyList(), any(),
                        anyLong(), any());
    }
}
