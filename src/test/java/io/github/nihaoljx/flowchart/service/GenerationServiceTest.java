package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.graph.GraphValidator;
import io.github.nihaoljx.flowchart.graph.SceneBinder;
import io.github.nihaoljx.flowchart.graph.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.SceneDrift;
import io.github.nihaoljx.flowchart.graph.SceneLayout;
import io.github.nihaoljx.flowchart.graph.ValidationIssue;
import io.github.nihaoljx.flowchart.model.ChatEvent;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class GenerationServiceTest {

    /** 两个节点的最小场景：编辑类用例的"原图" */
    private static final String TWO_NODES =
            "{\"elements\":["
            + "{\"id\":\"n1\",\"type\":\"rectangle\","
            + "\"x\":0,\"y\":0,\"width\":100,\"height\":60},"
            + "{\"id\":\"n2\",\"type\":\"rectangle\","
            + "\"x\":200,\"y\":0,\"width\":100,\"height\":60}]}";

    /**
     * 四个节点的场景：重画检测用例需要它。
     *
     * <p>因为检测要求"id 全换 + 规模明显缩水（新 &lt; 原的一半）"，
     * 两个节点时 1 &lt; 1 不成立，触发不了。
     */
    /**
     * 两个节点 + 一条绑定式连线。
     *
     * <p>判重用例需要"可比较的连线"：端点以 id 对形式表达，
     * 与用户手绘箭头（Excalidraw 的 startBinding/endBinding）能对上。
     */
    private static final String BOUND_PAIR =
            "{\"elements\":["
            + "{\"id\":\"n1\",\"type\":\"rectangle\","
            + "\"x\":0,\"y\":0,\"width\":100,\"height\":60},"
            + "{\"id\":\"n2\",\"type\":\"rectangle\","
            + "\"x\":200,\"y\":0,\"width\":100,\"height\":60},"
            + "{\"id\":\"a1\",\"type\":\"arrow\","
            + "\"start\":{\"id\":\"n1\"},"
            + "\"end\":{\"id\":\"n2\"}}]}";

    private static final String FOUR_NODES =
            "{\"elements\":["
            + "{\"id\":\"n1\",\"type\":\"rectangle\","
            + "\"x\":0,\"y\":0,\"width\":100,\"height\":60},"
            + "{\"id\":\"n2\",\"type\":\"rectangle\","
            + "\"x\":200,\"y\":0,\"width\":100,\"height\":60},"
            + "{\"id\":\"n3\",\"type\":\"rectangle\","
            + "\"x\":400,\"y\":0,\"width\":100,\"height\":60},"
            + "{\"id\":\"n4\",\"type\":\"rectangle\","
            + "\"x\":600,\"y\":0,\"width\":100,\"height\":60}]}";

    private PromptService promptService;
    private LlmProvider llmProvider;
    private GraphValidator graphValidator;
    private SessionStore sessionStore;
    private GenerationService service;

    @BeforeEach
    void setUp() {
        promptService = mock(PromptService.class);
        llmProvider = mock(LlmProvider.class);
        graphValidator =
                mock(GraphValidator.class);
        sessionStore = mock(SessionStore.class);
        service = new GenerationService(
                promptService, llmProvider,
                graphValidator, new SceneBinder(),
                new SceneDeduper(), new SceneDrift(),
                new SceneLayout(), sessionStore);
    }

    @Test
    void firstRoundSuccess() throws Exception {
        String validJson = """
                {"elements":[{"id":"n1",
                "type":"rectangle","x":0,"y":0,
                "width":100,"height":60}]}
                """;
        when(promptService.buildPrompt(
                eq("画圆"), any()))
                .thenReturn("prompt");
        when(llmProvider.chat(eq("prompt"), anyInt()))
                .thenReturn(validJson);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画圆", emitter);

        // 首轮通过即发（2026-09-17）：只调 1 次 LLM 就落库并下发。
        // 旧实现在这里是 times(3) —— 用**同一个 prompt** 空跑两轮，
        // 把用户等待时间直接 ×3（简单图 25 秒变 75 秒）。
        verify(llmProvider, times(1))
                .chat(anyString(), anyInt());
        // emitter.complete() 被调用
        verify(emitter).complete();
        // 无会话（sessionId 为 null）= 无状态生成：绝不落库，
        // 否则会把匿名请求的场景写到别人的会话里
        verify(sessionStore, never())
                .setCurrentModel(any(), anyMap());
    }

    @Test
    void retryOnValidationFailure()
            throws Exception {
        String badJson = """
                {"elements":[{"id":"n1"}]}
                """;
        String fixedJson = """
                {"elements":[{"id":"n1",
                "type":"rectangle","x":0,"y":0,
                "width":100,"height":60}]}
                """;
        when(promptService.buildPrompt(
                eq("画圆"), any()))
                .thenReturn("p1");
        // 首轮返回缺 type 的 JSON
        when(llmProvider.chat(eq("p1"), anyInt()))
                .thenReturn(badJson);
        // 首轮校验失败
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of(
                    new ValidationIssue(
                        "elements[0].type",
                        "缺少 type",
                        "请补充 type 字段")))
                .thenReturn(List.of());
        when(promptService.buildFixPrompt(
                eq("画圆"), anyList(), any()))
                .thenReturn("p2");
        // 修正轮返回合法 JSON
        when(llmProvider.chat(eq("p2"), anyInt()))
                .thenReturn(fixedJson);

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画圆", emitter);

        // 首轮失败 → 修正轮（round2）通过即发，共 2 次 LLM。
        // 旧实现在 round2 通过后还要再空跑一轮（times(3)）。
        verify(llmProvider, times(2))
                .chat(anyString(), anyInt());
        verify(emitter).complete();
    }

    @Test
    void givesUpAfterMaxRounds()
            throws Exception {
        String badJson = """
                {"elements":[]}
                """;
        when(promptService.buildPrompt(
                anyString(), any()))
                .thenReturn("p");
        when(llmProvider.chat(anyString(), anyInt()))
                .thenReturn(badJson);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of(
                    new ValidationIssue(
                        "elements",
                        "数组为空",
                        "至少需要一个元素")));
        when(promptService.buildFixPrompt(
                anyString(), anyList(), any()))
                .thenReturn("fix-prompt");

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画圆", emitter);

        // 调了 3 次 LLM
        verify(llmProvider, times(3))
                .chat(anyString(), anyInt());
        // 最后发 error + 正常收尾
        verify(emitter).complete();
        // 关键：绝不能走 completeWithError —— 错误内容已经作为 error 事件
        // 推给客户端了；通道层再"报错"只会让容器去走 ERROR dispatch，而
        // 响应头此时是 Content-Type: text/event-stream，容器找不到能把错误体
        // 写成 SSE 的消息转换器（实测抛 HttpMessageNotWritableException），
        // 结果是响应既不收尾也不断开，前端 reader.read() 永远等不到 done、
        // isLoading 永远为 true，输入框被禁用、整个界面卡死（2026-09-16 实测）。
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void survivesEmitterAlreadyCompleted()
            throws Exception {
        // 复现真实故障：LLM 跑完时 SSE 通道已被容器按超时掐断，
        // 此时任何 send 都抛 IllegalStateException。
        // 修复前该异常会打穿生成线程（生产日志里的
        // "Exception in thread Thread-2" 就是它），
        // 修复后必须被安静吞掉、不向外抛。
        when(promptService.buildPrompt(
                anyString(), any()))
                .thenReturn("p");
        when(llmProvider.chat(anyString(), anyInt()))
                .thenReturn("{\"elements\":[]}");
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IllegalStateException(
                "ResponseBodyEmitter has already completed"))
                .when(emitter).send(any(ChatEvent.class));

        assertDoesNotThrow(
                () -> service.generate(
                        null, "画圆", emitter));
    }

    @Test
    void reportsErrorWhenLlmFails()
            throws Exception {
        when(promptService.buildPrompt(
                anyString(), any()))
                .thenReturn("p");
        when(llmProvider.chat(anyString(), anyInt()))
                .thenThrow(new Exception(
                        "LLM 调用超时：整次调用超过 300 秒"));

        SseEmitter emitter = mock(SseEmitter.class);
        assertDoesNotThrow(
                () -> service.generate(
                        null, "画圆", emitter));

        // 错误要以 error 事件的形式推给客户端，并正常收尾通道；
        // 不能用 completeWithError（见 givesUpAfterMaxRounds 里的说明）
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

    /**
     * 会话里已有模型 → 本次是"改这张图"。
     *
     * <p>这是 2026-09-16 用户反馈的核心缺陷（"第二次输入不是在之前的图形上
     * 修改，而是清空画布重画一个"）的回归防线：只要会话里有模型，
     * 就必须把**当前场景**塞进 prompt，否则 LLM 看不到眼下的图，
     * 只能凭空造一张新的。
     */
    @Test
    @DisplayName("编辑语境：prompt 里必须带上当前场景，且结果要落回会话")
    void editModeSeedsPromptWithCurrentScene()
            throws Exception {
        Map<String, Object> stored =
                new ObjectMapper().readValue(
                        TWO_NODES,
                        new TypeReference<
                                Map<String, Object>>() {
                        });
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(stored);
        when(promptService.buildPrompt(
                eq("删掉 n2"), any()))
                .thenReturn("p-edit");
        when(llmProvider.chat(eq("p-edit"), anyInt()))
                .thenReturn(TWO_NODES);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "删掉 n2", emitter);

        // 当前场景必须出现在 prompt 里（第二个参数不能是 null）
        verify(promptService).buildPrompt(
                eq("删掉 n2"),
                contains("\"id\":\"n1\""));
        // 通过校验的场景要落回会话，下一轮才有"真相源"可用
        verify(sessionStore)
                .setCurrentModel(eq("s1"), anyMap());
        verify(emitter).complete();
    }

    /**
     * LLM 忽略上下文、把原图换成一批全新 id 的新图时，
     * 第一轮必须被挡下并触发修正轮（否则用户的图会被整体替换掉）。
     */
    @Test
    @DisplayName("编辑语境：id 全被换掉时要触发一轮修正")
    void flagsFullRewriteDuringEdit()
            throws Exception {
        Map<String, Object> stored =
                new ObjectMapper().readValue(
                        FOUR_NODES,
                        new TypeReference<
                                Map<String, Object>>() {
                        });
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(stored);
        when(promptService.buildPrompt(
                anyString(), any()))
                .thenReturn("p1");
        when(promptService.buildFixPrompt(
                anyString(), anyList(), any()))
                .thenReturn("p2");
        // 两轮都返回"全新 id 的重画结果"，且校验层本身放行
        String rewritten = "{\"elements\":["
                + "{\"id\":\"box_a\",\"type\":\"rectangle\","
                + "\"x\":0,\"y\":0,\"width\":100,\"height\":60}]}";
        when(llmProvider.chat(anyString(), anyInt()))
                .thenReturn(rewritten);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "删掉 n2", emitter);

        // 第 1 轮被"重画检测"挡下 → 第 2 轮放行即发，共 2 次 LLM
        // （旧实现第 2 轮通过后还要空跑第 3 轮）
        verify(llmProvider, times(2))
                .chat(anyString(), anyInt());
        verify(emitter).complete();
    }

    /** 保留 id 但把整张图重新排一遍 —— v2-37 的几何保真校验必须挡下 */
    private static final String FOUR_NODES_RELAYOUTED =
            "{\"elements\":["
            + "{\"id\":\"n1\",\"type\":\"rectangle\","
            + "\"x\":40,\"y\":320,\"width\":100,\"height\":60},"
            + "{\"id\":\"n2\",\"type\":\"rectangle\","
            + "\"x\":420,\"y\":120,\"width\":100,\"height\":60},"
            + "{\"id\":\"n3\",\"type\":\"rectangle\","
            + "\"x\":60,\"y\":20,\"width\":100,\"height\":60},"
            + "{\"id\":\"n4\",\"type\":\"rectangle\","
            + "\"x\":520,\"y\":600,\"width\":100,\"height\":60}]}";

    /** 整体平移（所有元素位移一致）—— 合法请求，不该被拦 */
    private static final String FOUR_NODES_SHIFTED =
            "{\"elements\":["
            + "{\"id\":\"n1\",\"type\":\"rectangle\","
            + "\"x\":200,\"y\":80,\"width\":100,\"height\":60},"
            + "{\"id\":\"n2\",\"type\":\"rectangle\","
            + "\"x\":400,\"y\":80,\"width\":100,\"height\":60},"
            + "{\"id\":\"n3\",\"type\":\"rectangle\","
            + "\"x\":600,\"y\":80,\"width\":100,\"height\":60},"
            + "{\"id\":\"n4\",\"type\":\"rectangle\","
            + "\"x\":800,\"y\":80,\"width\":100,\"height\":60}]}";

    /**
     * v2-37：编辑模式下 LLM 保留全部 id、却把坐标整体重排（没有换图、
     * 只换了排布）时，第一轮必须被挡下并触发修正轮。
     *
     * <p>这条缺口此前完全没人管：`detectRewrite` 只看"id 是不是全被换掉"，
     * 保留了 id 就一路放行 —— 用户说"改个颜色"，图的排布却被悄悄换掉。
     */
    @Test
    @DisplayName("编辑语境：保留 id 但整图重排坐标时要触发一轮修正")
    void flagsGeometryRelayoutDuringEdit()
            throws Exception {
        Map<String, Object> stored =
                new ObjectMapper().readValue(
                        FOUR_NODES,
                        new TypeReference<
                                Map<String, Object>>() {
                        });
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(stored);
        when(promptService.buildPrompt(
                anyString(), any()))
                .thenReturn("p1");
        when(promptService.buildFixPrompt(
                anyString(), anyList(), any()))
                .thenReturn("p2");
        // 两轮都返回"同一批 id、坐标全换"的结果，且字段校验本身放行
        when(llmProvider.chat(anyString(), anyInt()))
                .thenReturn(FOUR_NODES_RELAYOUTED);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "把所有框改成蓝色", emitter);

        // 第 1 轮被几何保真挡下 → 第 2 轮放行即发，共 2 次 LLM
        verify(llmProvider, times(2))
                .chat(anyString(), anyInt());
        verify(promptService)
                .buildFixPrompt(anyString(), anyList(), any());
        verify(emitter).complete();
    }

    /**
     * v2-37 的反面：用户说"把整张图往下挪一点"，LLM 把所有元素
     * 平移同一个向量 —— 这是**合法请求**，不能被判成"整图重排"，
     * 否则用户要白等一轮修正（30~100 秒）甚至拿不到结果。
     */
    @Test
    @DisplayName("编辑语境：整体平移（位移一致）不触发修正轮")
    void allowsUniformTranslationDuringEdit()
            throws Exception {
        Map<String, Object> stored =
                new ObjectMapper().readValue(
                        FOUR_NODES,
                        new TypeReference<
                                Map<String, Object>>() {
                        });
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(stored);
        when(promptService.buildPrompt(
                anyString(), any()))
                .thenReturn("p");
        when(llmProvider.chat(anyString(), anyInt()))
                .thenReturn(FOUR_NODES_SHIFTED);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "整张图往下挪一点", emitter);

        // 首轮通过即发：没有修正轮
        verify(llmProvider, times(1))
                .chat(anyString(), anyInt());
        verify(promptService, never())
                .buildFixPrompt(anyString(), anyList(), any());
        verify(emitter).complete();
    }

    /**
     * 新建图（会话里没有模型）时不能触发重画检测。
     *
     * <p>否则用户第一次说"画一张架构图"就会因为
     * "元素 id 全新"被判为"重画"而白跑一轮修正。
     */
    @Test
    @DisplayName("新建图：无历史模型时不触发重画检测")
    void noRewriteCheckWithoutPreviousModel()
            throws Exception {
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(null);
        when(promptService.buildPrompt(
                eq("画一张架构图"), isNull()))
                .thenReturn("p");
        when(llmProvider.chat(eq("p"), anyInt()))
                .thenReturn(TWO_NODES);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "画一张架构图", emitter);

        // 首轮通过即落库并下发，只调 1 次 LLM
        verify(llmProvider, times(1))
                .chat(anyString(), anyInt());
        // 首次生成的场景也要落库：下一句"删掉某个节点"才有原图可依
        verify(sessionStore)
                .setCurrentModel(eq("s1"), anyMap());
    }

    /**
     * 去重接线（v2-34）：用户手绘的那条线已经表达了 n1→n2，
     * LLM 又画了一条同样的 —— 下发给前端之前必须把重复的那条去掉。
     *
     * <p>同时验证"用户手绘旁路"在落库时被保留：否则一轮生成之后，
     * prompt 又看不见用户画的东西了。
     */
    @Test
    @DisplayName("去重接线：与用户手绘线重复的连线被丢弃，且旁路随模型落库")
    @SuppressWarnings("unchecked")
    void dropsLineDuplicatingUserDrawnOne()
            throws Exception {
        Map<String, Object> stored =
                new ObjectMapper().readValue(
                        BOUND_PAIR,
                        new TypeReference<
                                Map<String, Object>>() {
                        });
        stored.put(SessionStore.USER_ELEMENTS_KEY,
                List.of(Map.of(
                        "type", "arrow",
                        "startId", "n1",
                        "endId", "n2")));
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(stored);
        when(promptService.buildPrompt(
                anyString(), any()))
                .thenReturn("p");
        when(llmProvider.chat(anyString(), anyInt()))
                .thenReturn(BOUND_PAIR);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "把这条线删掉", emitter);

        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass(Map.class);
        verify(sessionStore).setCurrentModel(
                eq("s1"), captor.capture());
        Map<String, Object> saved = captor.getValue();
        assertFalse(
                saved.get("elements").toString()
                        .contains("\"a1\""),
                "与用户手绘线重复的 a1 不该出现在落库模型里: "
                        + saved.get("elements"));
        assertTrue(saved.containsKey(
                        SessionStore.USER_ELEMENTS_KEY),
                "用户手绘旁路必须随模型一起保留");
    }

    @Test
    void extractJsonStripsMarkdownFence() {
        String input =
                "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}",
                service.extractJson(input));
    }

    // ===== 轮次语义：通过即发、失败才继续（2026-09-17 取代原 D4 先存后发）=====

    @Test
    @DisplayName("首轮通过即发：不会再空跑后两轮（旧 D4 的 ×3 等待）")
    void firstPassIsSentImmediately() throws Exception {
        String round1 = """
                {"elements":[{"id":"a","type":"rectangle",
                "x":0,"y":0,"width":100,"height":60}]}
                """;
        String round2 = """
                {"elements":[{"id":"b","type":"rectangle",
                "x":0,"y":0,"width":100,"height":60}]}
                """;
        when(promptService.buildPrompt(eq("画"), any()))
                .thenReturn("p");
        // 若实现仍空跑后续轮次，第 2 次调用会拿到 round2 —— 内容含 "b"，
        // 下面的断言就能同时抓到"多调了 LLM"与"发了后一轮的结果"
        when(llmProvider.chat(eq("p"), anyInt()))
                .thenReturn(round1).thenReturn(round2);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画", emitter);

        verify(llmProvider, times(1))
                .chat(anyString(), anyInt());
        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce()).send(captor.capture());
        List<ChatEvent> events = captor.getAllValues();
        ChatEvent last = events.get(events.size() - 1);
        assertEquals("result", last.type(),
                "首轮通过就应发 result");
        assertTrue(last.data().contains("\"a\""),
                "应立即发首轮结果，而不是空跑后发第二轮");
    }

    /**
     * 「合格结果永不丢失」在新语义下由构造保证：一轮通过就发出去，
     * 之后根本不存在会超时 / 会失败的后继轮次。旧 D4 的"先存着、
     * 万一后面整批崩了再拿存档兜底"因此不再需要 —— 它补偿的正是
     * "通过之后还空跑两轮"这个自己制造出来的风险。
     */
    @Test
    @DisplayName("首轮通过后不存在后继轮次：后续校验失败也影响不到已发出的结果")
    void noLaterRoundAfterFirstPass() throws Exception {
        String good = """
                {"elements":[{"id":"g","type":"rectangle",
                "x":0,"y":0,"width":100,"height":60}]}
                """;
        when(promptService.buildPrompt(eq("画"), any()))
                .thenReturn("p");
        when(llmProvider.chat(eq("p"), anyInt()))
                .thenReturn(good);
        // 第 1 轮就通过；把后续桩设成"带 issue"，用来证明
        // **根本没有第 2 轮** —— 若有，就会走修正 prompt 而不是同一个 p
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of())
                .thenReturn(List.of(new ValidationIssue(
                        "test", "test", "test")));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画", emitter);

        verify(llmProvider, times(1))
                .chat(anyString(), anyInt());
        verify(promptService, never())
                .buildFixPrompt(anyString(), anyList(), any());
        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce()).send(captor.capture());
        List<ChatEvent> events = captor.getAllValues();
        ChatEvent last = events.get(events.size() - 1);
        assertEquals("result", last.type(),
                "首轮通过就应发 result");
        assertTrue(last.data().contains("\"g\""));
    }

    @Test
    @DisplayName("三轮全部失败 → 报错（没有可发的合格结果）")
    void allRoundsFailStillErrors() throws Exception {
        String bad = """
                {"elements":[{"id":"x","type":"arrow"}]}
                """;
        when(promptService.buildPrompt(eq("画"), any()))
                .thenReturn("p");
        when(llmProvider.chat(eq("p"), anyInt()))
                .thenReturn(bad);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of(new ValidationIssue(
                        "test", "test", "test")));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画", emitter);

        // 最后事件是 error
        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        List<ChatEvent> events = captor.getAllValues();
        assertEquals("error",
                events.get(events.size() - 1).type(),
                "全部失败无存档应报 error");
    }

    /**
     * Bug A（2026-09-17）：会话里只有用户手绘、还没有任何 AI 元素时
     * （elements 为空、手绘在 _userElements 旁路），后端必须把用户手绘
     * 提升为"当前场景"让 LLM 就地修改，否则 LLM 看到空图会另画一张
     * （用户实测：手绘在右、AI 在左各来一张）。同时落库要清空旧旁路
     * （手绘已被 AI 吸收）。
     */
    @Test
    @DisplayName("BugA: 只有用户手绘时，把用户手绘当当前场景就地修改并清空旧旁路")
    @SuppressWarnings("unchecked")
    void takeOverHandDrawnScenePromotesToEditable()
            throws Exception {
        Map<String, Object> stored = new HashMap<>();
        stored.put("elements", List.of());
        stored.put(SessionStore.USER_ELEMENTS_KEY, List.of(
                Map.of("id", "ue1", "type", "rectangle",
                        "x", 10, "y", 10, "width", 80, "height", 40),
                Map.of("id", "ue2", "type", "rectangle",
                        "x", 200, "y", 10, "width", 80, "height", 40,
                        "text", "B")));
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(stored);

        ArgumentCaptor<String> sceneCaptor =
                ArgumentCaptor.forClass(String.class);
        when(promptService.buildPrompt(
                eq("改成红色"), sceneCaptor.capture()))
                .thenReturn("p");

        String result = "{\"elements\":["
                + "{\"id\":\"ue1\",\"type\":\"rectangle\","
                + "\"x\":10,\"y\":10,\"width\":80,\"height\":40,"
                + "\"backgroundColor\":\"#ff0000\"},"
                + "{\"id\":\"ue2\",\"type\":\"rectangle\","
                + "\"x\":200,\"y\":10,\"width\":80,\"height\":40,"
                + "\"label\":{\"text\":\"B\"}}]}";
        when(llmProvider.chat(eq("p"), anyInt()))
                .thenReturn(result);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "改成红色", emitter);

        // 1) prompt 里的"当前场景"必须带上用户手绘（种子场景），
        //    而不是一张空图 —— 否则 LLM 会另画一张
        String scene = sceneCaptor.getValue();
        assertTrue(scene.contains("\"ue1\""),
                "用户手绘应作为当前场景被塞进 prompt: " + scene);
        // 2) 种子场景里不应再带 _userElements（避免"别复制"误导）
        assertFalse(scene.contains(
                SessionStore.USER_ELEMENTS_KEY),
                "种子场景不应含 _userElements 旁路: " + scene);
        // 3) 落库模型应吸收手绘并清空旧旁路
        ArgumentCaptor<Map<String, Object>> savedCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(sessionStore).setCurrentModel(
                eq("s1"), savedCaptor.capture());
        Map<String, Object> saved = savedCaptor.getValue();
        assertFalse(saved.containsKey(
                SessionStore.USER_ELEMENTS_KEY),
                "take-over 后不应保留旧 _userElements: " + saved);
        assertTrue(saved.get("elements").toString()
                        .contains("ue1"),
                "落库模型应含 AI 修改后的手绘元素");
        verify(emitter).complete();
    }
}
