package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.agent.AgentLoopListener;
import io.github.nihaoljx.flowchart.agent.AgentLoopService;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;
import io.github.nihaoljx.flowchart.llm.AgentResult;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolCall;
import io.github.nihaoljx.flowchart.model.ChatEvent;
import io.github.nihaoljx.flowchart.session.MemoryStore;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GenerationService} 编排层测试（单一 agent 路径）
 *
 * <p>覆盖范围锁定在"编排"本身：会话状态怎么读、怎么透传给
 * agent run、终态怎么处置。模型循环内部的修正语义归
 * {@code AgentLoopTest}，闸门判定归 SceneGate 各测试，
 * 终态三态（COMPLETED / PARTIAL 进度 / PARTIAL 占位）归
 * {@code GenerationServiceAgentModeTest}。
 *
 * <p>这里用 mock 的 {@code AgentLoopService}：run 的入参
 * （system / user 块、手绘旁路、编辑基线、listener）就是
 * 编排层的全部输出面，逐项断言即覆盖。
 */
class GenerationServiceTest {

    /** 两个节点的最小场景：编辑类用例的"原图" */
    private static final String TWO_NODES =
            "{\"elements\":["
            + "{\"id\":\"n1\",\"type\":\"rectangle\","
            + "\"x\":0,\"y\":0,\"width\":100,\"height\":60},"
            + "{\"id\":\"n2\",\"type\":\"rectangle\","
            + "\"x\":200,\"y\":0,\"width\":100,\"height\":60}]}";

    /** 只有手绘旁路、没有 elements：take-over（Bug A）用例的输入 */
    private static final String HAND_DRAWN_ONLY =
            "{\"_userElements\":["
            + "{\"id\":\"u1\",\"type\":\"rectangle\","
            + "\"x\":0,\"y\":0,\"width\":100,\"height\":60}]}";

    /** 有 AI 元素也有手绘旁路：旁路透传用例的输入 */
    private static final String AI_PLUS_HAND_DRAWN =
            "{\"elements\":["
            + "{\"id\":\"n1\",\"type\":\"rectangle\","
            + "\"x\":0,\"y\":0,\"width\":100,\"height\":60}],"
            + "\"_userElements\":["
            + "{\"id\":\"u1\",\"type\":\"rectangle\","
            + "\"x\":300,\"y\":0,\"width\":100,\"height\":60}]}";

    private static final String VALID_SCENE =
            "{\"elements\":[{\"id\":\"n1\",\"type\":\"rectangle\","
            + "\"x\":0,\"y\":0,\"width\":100,\"height\":60}]}";

    private AgentLoopService agent;
    private SessionStore sessionStore;
    private GenerationService service;

    @BeforeEach
    void setUp() {
        agent = mock(AgentLoopService.class);
        sessionStore = mock(SessionStore.class);
        service = new GenerationService(
                agent, sessionStore, new PromptService());
    }

    private List<ChatEvent> events(SseEmitter emitter)
            throws Exception {
        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, org.mockito.Mockito.atLeastOnce())
                .send(captor.capture());
        return captor.getAllValues();
    }

    /** 把一个 JSON 文本读成会话模型 map（sessionStore 的返回形态） */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> modelOf(String json)
            throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, Map.class);
    }

    private static AgentResult completed() {
        return new AgentResult(AgentResult.Status.COMPLETED,
                VALID_SCENE, 1, "校验通过");
    }

    @Test
    @DisplayName("编辑模式：编辑基线与当前场景透传给 agent run")
    void editModePassesBaselineAndSceneToAgent() throws Exception {
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(modelOf(TWO_NODES));
        when(agent.run(any(), any(), anyList(), any(),
                anyLong(), any(), any())).thenReturn(completed());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "把 n1 改个名", emitter);

        ArgumentCaptor<String> previous =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userBlock =
                ArgumentCaptor.forClass(String.class);
        verify(agent).run(any(), userBlock.capture(),
                anyList(), previous.capture(), anyLong(), any(), any());
        assertTrue(previous.getValue().contains("n1"),
                "编辑基线必须是会话当前模型");
        assertTrue(userBlock.getValue().contains("n1"),
                "user 块必须带上当前场景");
        // 无手绘旁路：userElements 传空表
        ArgumentCaptor<List<Map<String, Object>>> userEls =
                ArgumentCaptor.forClass(List.class);
        verify(agent).run(any(), any(), userEls.capture(),
                any(), anyLong(), any(), any());
        assertTrue(userEls.getValue().isEmpty());
    }

    @Test
    @DisplayName("BugA: 只有手绘时提升为种子场景，旁路清空、落库不带旁路")
    void handDrawnOnlySessionPromotesToSeedAndClearsBypass()
            throws Exception {
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(modelOf(HAND_DRAWN_ONLY));
        when(agent.run(any(), any(), anyList(), any(),
                anyLong(), any(), any())).thenReturn(completed());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "在框旁边加个说明", emitter);

        ArgumentCaptor<List<Map<String, Object>>> userEls =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> userBlock =
                ArgumentCaptor.forClass(String.class);
        verify(agent).run(any(), userBlock.capture(),
                userEls.capture(), any(), anyLong(), any(), any());
        assertTrue(userEls.getValue().isEmpty(),
                "take-over 后手绘旁路必须清空（已被吸收）");
        assertTrue(userBlock.getValue().contains("u1"),
                "种子场景必须含被提升的手绘元素");
        // 落库不带 _userElements：旁路已被吸收，不能留给下一轮
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> saved =
                ArgumentCaptor.forClass(Map.class);
        verify(sessionStore).setCurrentModel(eq("s1"),
                saved.capture());
        assertFalse(saved.getValue()
                .containsKey(SessionStore.USER_ELEMENTS_KEY));
    }

    @Test
    @DisplayName("有 AI 元素 + 手绘旁路：旁路原样透传给 agent（不清空）")
    void userDrawnElementsForwardedToAgent() throws Exception {
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(modelOf(AI_PLUS_HAND_DRAWN));
        when(agent.run(any(), any(), anyList(), any(),
                anyLong(), any(), any())).thenReturn(completed());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "把两个框连起来", emitter);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> userEls =
                ArgumentCaptor.forClass(List.class);
        verify(agent).run(any(), any(), userEls.capture(),
                any(), anyLong(), any(), any());
        assertEquals(1, userEls.getValue().size(),
                "手绘旁路要交给去重器用");
    }

    @Test
    @DisplayName("agent run 抛异常：编辑模式把原图还回去，不是纯 error")
    void runFailureReturnsOriginalSceneInEditMode()
            throws Exception {
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(modelOf(TWO_NODES));
        when(agent.run(any(), any(), anyList(), any(),
                anyLong(), any(), any()))
                .thenThrow(new RuntimeException("502  upstream"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "加个节点", emitter);

        List<ChatEvent> all = events(emitter);
        ChatEvent terminal = all.get(all.size() - 1);
        assertEquals("result", terminal.type());
        assertTrue(terminal.data().contains("n1"),
                "编辑模式必须把原图还回去");
        assertTrue(all.stream().anyMatch(e ->
                        "validation".equals(e.type())
                                && e.data().contains("图保持原样")),
                "兜底说明必须用中性措辞");
        // 兜底不落库：用户重试时起点与今天看到的完全一致
        verify(sessionStore, never())
                .setCurrentModel(any(), any());
    }

    @Test
    @DisplayName("emitter 已被容器关闭：不打断 generate 主流程")
    void emitterAlreadyCompletedDoesNotCrash() throws Exception {
        when(agent.run(any(), any(), anyList(), any(),
                anyLong(), any(), any())).thenReturn(completed());
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException("already completed"))
                .when(emitter).complete();

        service.generate(null, "画个框", emitter);

        List<ChatEvent> all = events(emitter);
        assertEquals("result",
                all.get(all.size() - 1).type(),
                "结果仍要发出去，收尾异常自己吞掉");
    }

    @Test
    @DisplayName("跨轮记忆：提交未过的 issue 当场记入 MemoryStore")
    void recordsFailedSubmissionIssuesIntoMemory()
            throws Exception {
        when(agent.run(any(), any(), anyList(), any(),
                anyLong(), any(), any())).thenAnswer(invocation -> {
            // 模拟 AgentLoop 的行为：提交未过 → 回喂观察 → 继续
            AgentLoopListener listener =
                    invocation.getArgument(5);
            listener.onToolResult(
                    new ToolCall("c1", "submit_spec", null),
                    Observation.fail("校验未过", null,
                            List.of(new ValidationIssue(
                                    "arrow", "箭头路径不合法",
                                    "只给 start/end 或 points"))));
            return completed();
        });

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "画个图", emitter);

        MemoryStore memory = (MemoryStore) ReflectionTestUtils
                .getField(service, "memoryStore");
        assertEquals(1, memory.recent("s1", 10).size(),
                "提交未过的 issue 必须记录");
    }

    @Test
    @DisplayName("无状态生成：不读会话、不落库，基线为 null")
    void statelessGenerateSkipsSessionContext() throws Exception {
        when(agent.run(any(), any(), anyList(), any(),
                anyLong(), any(), any())).thenReturn(completed());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画个框", emitter);

        ArgumentCaptor<String> previous =
                ArgumentCaptor.forClass(String.class);
        verify(agent).run(any(), any(), anyList(),
                previous.capture(), anyLong(), any(), any());
        assertNull(previous.getValue());
        verify(sessionStore, never())
                .setCurrentModel(any(), any());
    }
}
