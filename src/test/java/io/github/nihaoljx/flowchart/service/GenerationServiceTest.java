package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.client.Message;
import io.github.nihaoljx.flowchart.context.ContextAssembler;
import io.github.nihaoljx.flowchart.context.ContextBudget;
import io.github.nihaoljx.flowchart.context.ContextReplay;
import io.github.nihaoljx.flowchart.graph.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.GraphValidator;
import io.github.nihaoljx.flowchart.graph.SceneBinder;
import io.github.nihaoljx.flowchart.graph.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.SceneSanitizer;
import io.github.nihaoljx.flowchart.graph.SceneDrift;
import io.github.nihaoljx.flowchart.graph.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.ValidationIssue;
import io.github.nihaoljx.flowchart.model.ChatEvent;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private LlmProvider llmProvider;
    private GraphValidator graphValidator;
    private SessionStore sessionStore;
    private PromptService promptService;
    private GenerationService service;

    @BeforeEach
    void setUp() {
        llmProvider = mock(LlmProvider.class);
        graphValidator =
                mock(GraphValidator.class);
        sessionStore = mock(SessionStore.class);
        promptService = new PromptService();

        // v2-41：组装器用**真实对象**（而不是 mock）——
        // 这样"发出去的消息长什么样"是被真正组装出来的，
        // 断言才覆盖到静态层 / 动态层的真实内容，而不是桩返回值。
        // 契约由真实 PromptService 从 classpath 读取（与 PromptServiceTest 同法）。
        ContextReplay replay = new ContextReplay();
        // 测试不落盘：回放是观测手段，不该在跑用例时写文件
        ReflectionTestUtils.setField(replay, "enabled", false);
        ContextAssembler contextAssembler = new ContextAssembler(
                new PromptService(), new ContextBudget(), replay);

        service = new GenerationService(
                llmProvider, graphValidator, new SceneBinder(),
                new SceneDeduper(), new SceneSanitizer(),
                new SceneDrift(), new ScenePlanner(),
                sessionStore, contextAssembler, new GeometryAudit(),
                promptService);
    }

    /** 捕获"真实发给模型的消息列表"，供内容级断言复用 */
    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Message>> messageCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    /** 把一组消息拼成单串文本，便于做包含性断言 */
    private static String joined(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            sb.append("<<").append(message.role()).append(">>")
                    .append(message.content())
                    .append("\n");
        }
        return sb.toString();
    }

    @Test
    void firstRoundSuccess() throws Exception {
        String validJson = """
                {"elements":[{"id":"n1",
                "type":"rectangle","x":0,"y":0,
                "width":100,"height":60}]}
                """;
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(validJson);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画圆", emitter);

        // 首轮通过即发（2026-09-17）：只调 1 次 LLM 就落库并下发。
        // 旧实现在这里是 times(3) —— 用**同一个 prompt** 空跑两轮，
        // 把用户等待时间直接 ×3（简单图 25 秒变 75 秒）。
        verify(llmProvider, times(1))
                .chat(anyList(), anyInt());
        // emitter.complete() 被调用
        verify(emitter).complete();
        // 无会话（sessionId 为 null）= 无状态生成：绝不落库，
        // 否则会把匿名请求的场景写到别人的会话里
        verify(sessionStore, never())
                .setCurrentModel(any(), anyMap());
    }

    /**
     * v2-41：首轮发出的必须是 system + user 两条消息，且契约在 system。
     *
     * <p>这条用例是"上下文分层真的生效了"的最小证据 —— 旧实现只有一条
     * user 消息，模型分不清哪些是规则、哪些是本次内容。
     */
    @Test
    @DisplayName("v2-41: 首轮发出 system + user 两条消息，契约落在 system")
    void firstRoundSendsSystemAndUserMessages()
            throws Exception {
        // 必须是**非空**场景：2026-09-26 起空场景不再"校验通过即发"，
        // 而是要进修正轮才收尾 —— 那样这里会变成 3 次 LLM 调用，
        // 而这条用例要测的是"首轮发出两条消息"，不是空场景兜底。
        // 空场景的回归见 emptySceneGoesToCorrectionAndFallsBackToPlaceholder。
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(TWO_NODES);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画个登录流程", emitter);

        ArgumentCaptor<List<Message>> captor = messageCaptor();
        verify(llmProvider).chat(captor.capture(), anyInt());
        List<Message> sent = captor.getValue();

        assertEquals(2, sent.size(), "本轮应发出 2 条消息");
        assertEquals("system", sent.get(0).role(), "首条必须是 system");
        assertEquals("user", sent.get(1).role(), "次条必须是 user");
        assertTrue(sent.get(0).content().contains("scene.schema.json"),
                "契约必须落在 system（静态层）");
        // 锚点从 v5.8 的「# 约束」换成 v6 的静态层 H1；v10.0.0 用户把 H1 改成
        // 中性的「# System Prompt」（不再带产品名），但「system 块里必须带
        // system-prompt.md 的正文」这条性质没变。
        assertTrue(sent.get(0).content().contains("# System Prompt"),
                "静态层正文必须落在 system（读自 system-prompt.md）");
        assertTrue(sent.get(1).content().contains("画个登录流程"),
                "用户需求必须落在 user（动态层）");
        assertFalse(sent.get(1).content().contains("scene.schema.json"),
                "契约不得混进 user —— 否则静态层就不稳定、缓存必失效");
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
        // 首轮返回缺 type 的 JSON；修正轮返回合法 JSON
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(badJson)
                .thenReturn(fixedJson);
        // 首轮校验失败
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of(
                    new ValidationIssue(
                        "elements[0].type",
                        "缺少 type",
                        "请补充 type 字段")))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画圆", emitter);

        // 首轮失败 → 修正轮（round2）通过即发，共 2 次 LLM。
        // 旧实现在 round2 通过后还要再空跑一轮（times(3)）。
        ArgumentCaptor<List<Message>> captor = messageCaptor();
        verify(llmProvider, times(2))
                .chat(captor.capture(), anyInt());
        // 第 2 组消息必须带上修正块（issues 不得丢）
        assertTrue(joined(captor.getAllValues().get(1))
                        .contains("上一轮校验未通过"),
                "修正轮消息必须带上上一轮的 issues");
        verify(emitter).complete();
    }

    @Test
    void givesUpAfterMaxRounds()
            throws Exception {
        String badJson = """
                {"elements":[]}
                """;
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(badJson);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of(
                    new ValidationIssue(
                        "elements",
                        "数组为空",
                        "至少需要一个元素")));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画圆", emitter);

        // 调了 3 次 LLM
        verify(llmProvider, times(3))
                .chat(anyList(), anyInt());
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
        when(llmProvider.chat(anyList(), anyInt()))
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
        when(llmProvider.chat(anyList(), anyInt()))
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
     * 就必须把**当前场景**塞进发给模型的消息里，否则 LLM 看不到眼下的图，
     * 只能凭空造一张新的。
     */
    @Test
    @DisplayName("编辑语境：消息里必须带上当前场景，且结果要落回会话")
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
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(TWO_NODES);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "删掉 n2", emitter);

        // 当前场景必须出现在**真实发出的消息**里
        ArgumentCaptor<List<Message>> captor = messageCaptor();
        verify(llmProvider).chat(captor.capture(), anyInt());
        List<Message> sent = captor.getValue();
        assertEquals("system", sent.get(0).role(),
                "首条必须是 system（静态层）");
        assertTrue(joined(sent).contains("\"id\":\"n1\""),
                "当前场景必须出现在消息里");
        assertTrue(joined(sent).contains("## 当前场景"),
                "编辑模式必须带「当前场景」段");
        assertTrue(joined(sent).contains("## 用户需求"),
                "用户需求必须出现");
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
        // 两轮都返回"全新 id 的重画结果"，且校验层本身放行
        String rewritten = "{\"elements\":["
                + "{\"id\":\"box_a\",\"type\":\"rectangle\","
                + "\"x\":0,\"y\":0,\"width\":100,\"height\":60}]}";
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(rewritten);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "删掉 n2", emitter);

        // 第 1 轮被"重画检测"挡下 → 第 2 轮放行即发，共 2 次 LLM
        // （旧实现第 2 轮通过后还要空跑第 3 轮）
        verify(llmProvider, times(2))
                .chat(anyList(), anyInt());
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
        // 两轮都返回"同一批 id、坐标全换"的结果，且字段校验本身放行
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(FOUR_NODES_RELAYOUTED);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "把所有框改成蓝色", emitter);

        // 第 1 轮被几何保真挡下 → 第 2 轮放行即发，共 2 次 LLM
        ArgumentCaptor<List<Message>> captor = messageCaptor();
        verify(llmProvider, times(2))
                .chat(captor.capture(), anyInt());
        assertTrue(joined(captor.getAllValues().get(1))
                        .contains("上一轮校验未通过"),
                "几何保真挡下后，第 2 组消息必须带上 issues 修正块");
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
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(FOUR_NODES_SHIFTED);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "整张图往下挪一点", emitter);

        // 首轮通过即发：只有 1 组消息，且其中不含修正块
        ArgumentCaptor<List<Message>> captor = messageCaptor();
        verify(llmProvider, times(1))
                .chat(captor.capture(), anyInt());
        assertFalse(joined(captor.getValue())
                        .contains("上一轮校验未通过"),
                "没有 issues 时不得出现修正块");
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
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(TWO_NODES);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "画一张架构图", emitter);

        // 首轮通过即落库并下发，只调 1 次 LLM
        ArgumentCaptor<List<Message>> captor = messageCaptor();
        verify(llmProvider, times(1))
                .chat(captor.capture(), anyInt());
        // 新建图：消息里不该出现「当前场景」段（那是编辑模式才有的）
        assertFalse(joined(captor.getValue()).contains("## 当前场景"),
                "新建图时不得出现「当前场景」段");
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
        when(llmProvider.chat(anyList(), anyInt()))
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
        // 若实现仍空跑后续轮次，第 2 次调用会拿到 round2 —— 内容含 "b"，
        // 下面的断言就能同时抓到"多调了 LLM"与"发了后一轮的结果"
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(round1).thenReturn(round2);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画", emitter);

        verify(llmProvider, times(1))
                .chat(anyList(), anyInt());
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
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(good);
        // 第 1 轮就通过；把后续桩设成"带 issue"，用来证明
        // **根本没有第 2 轮** —— 若有，就会带上修正块
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of())
                .thenReturn(List.of(new ValidationIssue(
                        "test", "test", "test")));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画", emitter);

        ArgumentCaptor<List<Message>> msgCaptor = messageCaptor();
        verify(llmProvider, times(1))
                .chat(msgCaptor.capture(), anyInt());
        assertFalse(joined(msgCaptor.getValue())
                        .contains("上一轮校验未通过"),
                "首轮通过后不该出现修正块");
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
    @DisplayName("三轮全部失败：图非空但校验过不去 → 兜底下发，不是纯 error")
    void allRoundsFailStillDegradesToFallback() throws Exception {
        // 这个 gate 盯的是 f12 的"图非空"那一半：模型给了 30 个元素的
        // 大图，但那几条校验三轮都改不掉。旧实现在这里下发纯 error，
        // 用户整句输入毫无回响 —— 与空场景是同一类事故，只是触发条件
        // 换成"图非空"（f05 / f30 那批 frame / group / 绑 frame 的箭头）。
        //
        // 注意本条**不再**断言"三轮失败要报错"：那种语义由
        // trueBackendFailureStillErrors 单独钉着（后端自己坏了才报 error）。
        String bad = """
                {"elements":[{"id":"x","type":"arrow"}]}
                """;
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(bad);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of(new ValidationIssue(
                        "test", "test", "test")));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画", emitter);

        verify(llmProvider, times(3))
                .chat(anyList(), anyInt());
        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        List<ChatEvent> events = captor.getAllValues();
        ChatEvent terminal = terminalEvent(events);
        assertNotNull(terminal, "必须给出终态事件");
        assertEquals("result", terminal.type(),
                "三轮修不动也该给出合法场景，而不是 error");
        assertTrue(elementCount(terminal.data()) >= 1,
                "兜底场景至少要有一个能画出来的元素: "
                        + terminal.data());
        // 原因必须带上 issue 摘要，用户才知道卡在哪一条上
        String all = events.stream()
                .map(ChatEvent::data)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        assertTrue(all.contains("仍未通过校验"),
                "必须告诉用户是哪几条没通过: " + all);
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

    /**
     * 兜底说明的措辞：编辑模式下必须说「图保持原样」。
     *
     * <p>一次编辑失败的实际结果是"图原样没动"，但早先的措辞是
     * 「已把原来的图还给你」—— 那会被读成"我刚画的图被你退回来了"，
     * 把"没改动成功"误读成"出了错"（2026-09-26 team-lead 指出）。
     * 这条用例就是防措辞倒退的。
     */
    @Test
    @DisplayName("兜底说明用中性措辞：编辑模式说「图保持原样」，不说「还给你」")
    void fallbackNoteUsesNeutralWordingInEditMode()
            throws Exception {
        Map<String, Object> stored =
                new ObjectMapper().readValue(
                        TWO_NODES,
                        new TypeReference<
                                Map<String, Object>>() {
                        });
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(stored);
        when(llmProvider.chat(anyList(), anyInt()))
                .thenThrow(new Exception("连接被重置"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "把 n1 改成红色", emitter);

        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        String all = captor.getAllValues().stream()
                .map(ChatEvent::data)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        assertTrue(all.contains("图保持原样"),
                "编辑模式要说清「图没动」: " + all);
        assertFalse(all.contains("还给你"),
                "「还给你」会把没改动成功误读成出了错: " + all);
    }

    /**
     * 兜底说明里的 issue 摘要必须截断。
     *
     * <p>一轮 30 条 issue 全量拼出来约两千字符，那条说明会随 SSE 一并
     * 推给前端进消息列表 —— 撑爆它比少写点原因糟得多。
     */
    @Test
    @DisplayName("兜底说明里的 issue 摘要会被截断，不把 SSE 消息撑爆")
    void fallbackNoteTruncatesLongIssueSummary() throws Exception {
        // 2026-10-07：摘要从"原样罗列"改成"按原因归类"后，普通长短的
        // issue 已经压不出超长文本了（这正是改动的目的）。所以这里
        // 刻意造一个**病态**样本：单条原因本身就几百字 —— 截断逻辑
        // 只有在"连一条原因都塞不下"时才需要生效，测试就该打这个点。
        String longReason = "这是一条特别特别长的原因说明".repeat(20);
        List<ValidationIssue> many =
                new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new ValidationIssue(
                    "elements[" + i + "].type",
                    "第" + i + "个元素：" + longReason,
                    "修"));
        }
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(TWO_NODES);
        when(graphValidator.validate(anyString()))
                .thenReturn(many);

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画", emitter);

        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        // 只量那一条说明本身。
        //
        // ⚠️ 必须按 **type == "validation"** 过滤，不能按内容字符串过滤：
        // 兜底场景的 JSON 里，那条说明会被原样嵌进占位文字
        // （label.text）再作为 result 事件推一次 —— 用
        // contains("仍未通过校验") 会把两条事件都捞出来、reduce 求和，
        // 量出「620 字」这种虚高值，看起来像摘要没截断（2026-09-26）。
        String note = captor.getAllValues().stream()
                .filter(e -> "validation".equals(e.type()))
                .map(ChatEvent::data)
                .filter(d -> d.contains("仍未通过校验"))
                .reduce((a, b) -> a + b)
                .orElse("");
        assertTrue(note.length() <= 320,
                "说明不该有两三千字: 共 " + note.length()
                        + " 字 -> " + note);
        assertTrue(note.contains("…"),
                "超长的摘要必须被截断并留下省略号: " + note);
        verify(emitter).complete();
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

        String result = "{\"elements\":["
                + "{\"id\":\"ue1\",\"type\":\"rectangle\","
                + "\"x\":10,\"y\":10,\"width\":80,\"height\":40,"
                + "\"backgroundColor\":\"#ff0000\"},"
                + "{\"id\":\"ue2\",\"type\":\"rectangle\","
                + "\"x\":200,\"y\":10,\"width\":80,\"height\":40,"
                + "\"label\":{\"text\":\"B\"}}]}";
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(result);
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "改成红色", emitter);

        // 1) 消息里的"当前场景"必须带上用户手绘（种子场景），
        //    而不是一张空图 —— 否则 LLM 会另画一张
        ArgumentCaptor<List<Message>> msgCaptor = messageCaptor();
        verify(llmProvider).chat(msgCaptor.capture(), anyInt());
        String scene = joined(msgCaptor.getValue());
        assertTrue(scene.contains("\"ue1\""),
                "用户手绘应作为当前场景被塞进消息: " + scene);
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

    // ===== 空场景兜底 + 收尾路径降级（2026-09-26）=====

    /**
     * 取下最后一条终态事件（result / error）。
     *
     * <p>判据刻意不看"最后一个事件"：收尾路径会先推一条说明原因的
     * validation 事件再推 result，直接取末位会拿到那条说明。
     */
    private static ChatEvent terminalEvent(
            List<ChatEvent> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            String type = events.get(i).type();
            if (type.equals("result") || type.equals("error")) {
                return events.get(i);
            }
        }
        return null;
    }

    /** 数一份场景里有多少个元素（解析不出返回 -1） */
    private static int elementCount(String sceneJson) {
        try {
            return new ObjectMapper().readTree(sceneJson)
                    .path("elements").size();
        } catch (Exception e) {
            return -1;
        }
    }

    @Test
    @DisplayName("空场景不落库不下发：进修正轮，兜底合成一条 text 元素")
    void emptySceneGoesToCorrectionAndFallsBackToPlaceholder()
            throws Exception {
        // 校验器一笔不挑 —— 模拟它漏报空数组。旧实现在这里会把空场景
        // 当成合格结果落库下发，前端收到 0 元素、画布全白还提示"已生成"。
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn("{\"elements\":[]}");
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of());

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画一张鱼骨图", emitter);

        // 1) 被拦下 → 走满三轮修正，而不是"首轮通过即发"
        verify(llmProvider, times(3))
                .chat(anyList(), anyInt());

        // 2) 修正 prompt 里必须写明不许交空场景，否则修正轮是白跑
        ArgumentCaptor<List<Message>> msgCaptor =
                messageCaptor();
        verify(llmProvider, times(3))
                .chat(msgCaptor.capture(), anyInt());
        assertTrue(joined(msgCaptor.getAllValues().get(1))
                        .contains("不要返回"),
                "修正轮必须把「不许交空场景」写进 prompt: "
                        + joined(msgCaptor.getAllValues().get(1)));

        // 3) 收尾必须是**至少一个元素**的合法场景，绝不是空画布
        ArgumentCaptor<ChatEvent> eventCaptor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(eventCaptor.capture());
        ChatEvent terminal =
                terminalEvent(eventCaptor.getAllValues());
        assertNotNull(terminal, "必须给出终态事件");
        assertEquals("result", terminal.type());
        assertTrue(
                terminal.data().contains(
                        "chartflow-fallback-note"),
                "兜底场景应含合成出来的元素: "
                        + terminal.data());

        // 4) 空场景绝不能落库：下一次编辑不该以空模型为起点
        verify(sessionStore, never())
                .setCurrentModel(any(), anyMap());
    }

    @Test
    @DisplayName("LLM 调用失败：编辑模式把原图还回去，而不是下发 error")
    void llmFailureKeepsOriginalSceneInEditMode()
            throws Exception {
        Map<String, Object> stored =
                new ObjectMapper().readValue(
                        TWO_NODES,
                        new TypeReference<
                                Map<String, Object>>() {
                        });
        when(sessionStore.getCurrentModel("s1"))
                .thenReturn(stored);
        // LLM 是外部依赖：它失败时后端本身是好的，用户该看到的是
        // "图还在、稍后重试"，而不是一句 error + 一张空画布
        when(llmProvider.chat(anyList(), anyInt()))
                .thenThrow(new Exception("LLM 调用超时"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate("s1", "把 n1 改成红色", emitter);

        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        ChatEvent terminal =
                terminalEvent(captor.getAllValues());
        assertNotNull(terminal);
        assertEquals("result", terminal.type(),
                "外部依赖故障不该以 error 收尾");
        assertTrue(terminal.data().contains("n1"),
                "原图元素必须还在: " + terminal.data());
        assertTrue(terminal.data().contains("n2"),
                "原图不该被当成新图覆盖: " + terminal.data());
        // 但用户必须知道发生了什么，否则会以为"改成功但没生效"
        String all = captor.getAllValues().stream()
                .map(ChatEvent::data)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        assertTrue(all.contains("LLM 调用失败"),
                "必须把原因告诉用户: " + all);
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    @DisplayName("LLM 调用失败：新建模式兜底合成一条可删除的占位 text")
    void llmFailureSynthesizesPlaceholderInNewMode()
            throws Exception {
        when(llmProvider.chat(anyList(), anyInt()))
                .thenThrow(new Exception("连接被重置"));

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画一张架构图", emitter);

        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        ChatEvent terminal =
                terminalEvent(captor.getAllValues());
        assertNotNull(terminal);
        assertEquals("result", terminal.type());
        assertTrue(elementCount(terminal.data()) >= 1,
                "兜底场景至少要有一个能画出来的元素: "
                        + terminal.data());
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    @DisplayName("模型返回空白：同样下发合法场景，而不是 error")
    void blankModelOutputStillDeliversScene() throws Exception {
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn("   ");

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画", emitter);

        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        ChatEvent terminal =
                terminalEvent(captor.getAllValues());
        assertNotNull(terminal);
        assertEquals("result", terminal.type());
        assertTrue(elementCount(terminal.data()) >= 1,
                "空白返回也要给出可画的场景: "
                        + terminal.data());
        verify(emitter).complete();
    }

    /**
     * 反例闸门：不是每条失败路径都能降级。
     *
     * <p>组装修正消息失败是**后端自己坏了**（写盘 / 读 schema 出错），
     * 这时给一张假图等于把事故盖住，必须保持 error 语义。
     */
    @Test
    @DisplayName("真后端故障（组装修正失败）仍保持 error 语义，不降级")
    void trueBackendFailureStillErrors() throws Exception {
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn("{\"elements\":[{\"id\":\"x\","
                        + "\"type\":\"rectangle\"}]}");
        when(graphValidator.validate(anyString()))
                .thenReturn(List.of(
                        new ValidationIssue("test", "test", "test")));
        ContextAssembler broken = mock(ContextAssembler.class);
        when(broken.assemble(any()))
                .thenReturn(List.of())
                .thenThrow(new IOException("修正消息写盘失败"));
        GenerationService failing =
                new GenerationService(
                        llmProvider, graphValidator,
                        new SceneBinder(), new SceneDeduper(),
                        new SceneSanitizer(), new SceneDrift(),
                        new ScenePlanner(), sessionStore, broken,
                        new GeometryAudit(), promptService);

        SseEmitter emitter = mock(SseEmitter.class);
        failing.generate(null, "画", emitter);

        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        ChatEvent terminal =
                terminalEvent(captor.getAllValues());
        assertNotNull(terminal);
        assertEquals("error", terminal.type(),
                "后端自身故障必须报 error，不能拿兜底图糊过去");
        assertTrue(terminal.data().contains("组装修正消息失败"),
                "错误要说明是哪一环坏的: " + terminal.data());
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    @DisplayName("校验明细摘要：按原因归类，主因在前，且绝不超长")
    void issueDigestGroupsByReason() {
        // Given：35 个 issue，但真实原因只有两类（大图那个真实场景的形态）
        List<ValidationIssue> issues = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            issues.add(new ValidationIssue(
                    "elements[" + i + "].start",
                    "箭头路径同时给了 start/end 与 points",
                    "二选一"));
        }
        for (int i = 0; i < 15; i++) {
            issues.add(new ValidationIssue(
                    "elements[" + (20 + i) + "].group",
                    "group 只允许出现在图形上",
                    "去掉"));
        }

        // When
        String digest = service.issueDigest(issues);

        // Then：报出总数；主因排在前且带计数
        assertTrue(digest.contains("35"),
                "应报出总问题数: " + digest);
        assertTrue(digest.contains("x20"),
                "主因应带出现次数: " + digest);
        int first = digest.indexOf("箭头");
        int second = digest.indexOf("group");
        assertTrue(first >= 0 && second > first,
                "出现 20 次的原因要排在 15 次之前: " + digest);
        assertTrue(digest.length() <= 321,
                "摘要必须截断，不能撑爆 SSE: " + digest.length());
    }

    @Test
    @DisplayName("校验明细摘要：没有问题时给 0，不抛异常")
    void issueDigestHandlesEmpty() {
        assertEquals("0 个问题", service.issueDigest(null));
        assertEquals("0 个问题", service.issueDigest(List.of()));
    }

    // ================= 解析闸门（2026-10-10，v10.4.0） =================
    //
    // 这组用例守的是"空白画布"那条通路：模型在 JSON 后面补一句收尾话时，
    // Jackson 默认宽容（FAIL_ON_TRAILING_TOKENS=false）判它合法，
    // 而前端 JSON.parse / Python json.loads 都是严格口径、直接报错。
    // 前端解析一失败就渲染不出场景 = 用户看到空白画布。

    /** 一段合法场景：闸门必须放行 */
    private static final String GOOD_SCENE =
            "{\"elements\":[{\"id\":\"n1\",\"type\":\"rectangle\","
            + "\"x\":0,\"y\":0,\"width\":100,\"height\":60}]}";

    @Test
    @DisplayName("解析闸门：合法场景放行")
    void parseGateAcceptsGoodScene() {
        assertNull(service.parseGate(GOOD_SCENE),
                "合法 JSON 不该被闸门拦下");
    }

    @Test
    @DisplayName("解析闸门：JSON 后面跟一句收尾话必须拦下（后端宽容、前端不宽容）")
    void parseGateRejectsTrailingProse() {
        // 这就是模型最常见的收尾习惯。先证明默认解析器会放过它 ——
        // 否则本用例就失去了意义（闸门拦的是一个后端本来就会拦的东西）。
        assertDoesNotThrow(
                () -> new ObjectMapper().readTree(
                        GOOD_SCENE + "\n希望对你有帮助"),
                "前提：默认 ObjectMapper 对尾随内容是宽容的");

        ValidationIssue issue = service.parseGate(
                GOOD_SCENE + "\n希望对你有帮助");

        assertNotNull(issue, "尾随散文必须被闸门拦下");
        assertEquals("$", issue.field(),
                "应报在根节点上");
        assertTrue(issue.reason().contains("不是合法 JSON"),
                "原因要说清是 JSON 读不通: " + issue.reason());
    }

    @Test
    @DisplayName("解析闸门：代码块包裹 / 空内容 / 非对象顶层都要拦")
    void parseGateRejectsOtherMalformed() {
        assertNotNull(service.parseGate(null), "null 必须拦下");
        assertNotNull(service.parseGate("   "), "空白必须拦下");
        assertNotNull(service.parseGate("[1,2,3]"),
                "顶层是数组必须拦下（契约要求对象）");
        assertNotNull(service.parseGate(
                        "```json\n" + GOOD_SCENE + "\n```"),
                "``` 包裹必须拦下（extractJson 只剥以 ``` 开头的整体包裹，"
                        + "这里刻意留一层没剥掉的）");
    }

    @Test
    @DisplayName("解析闸门：读不通的内容绝不能当成 result 下发给前端")
    void malformedPayloadNeverReachesFrontend() throws Exception {
        // 模型三轮都吐「JSON + 收尾话」：旧实现在这里会把这段谁都读不出来
        // 的文本当"可解析场景"记进 lastParseable，兜底时原样下发。
        String garbage = GOOD_SCENE + "\n希望对你有帮助";
        when(llmProvider.chat(anyList(), anyInt()))
                .thenReturn(garbage);

        SseEmitter emitter = mock(SseEmitter.class);
        service.generate(null, "画个方块", emitter);

        String result = resultPayload(emitter);
        assertNotNull(result, "无论如何都要给前端一份结果");
        // 关键断言：下发的那一份必须能被严格解析器读出来
        assertDoesNotThrow(
                () -> new ObjectMapper()
                        .enable(DeserializationFeature
                                .FAIL_ON_TRAILING_TOKENS)
                        .readTree(result),
                "下发的 result 必须是严格合法的 JSON: " + result);
        assertFalse(result.contains("希望对你有帮助"),
                "收尾散文绝不能混进 result: " + result);
        verify(emitter).complete();
    }

    /** 取出最后一个 result 事件的载荷；没有则返回 null */
    private static String resultPayload(SseEmitter emitter)
            throws Exception {
        ArgumentCaptor<ChatEvent> captor =
                ArgumentCaptor.forClass(ChatEvent.class);
        verify(emitter, atLeastOnce())
                .send(captor.capture());
        String payload = null;
        for (ChatEvent event : captor.getAllValues()) {
            if ("result".equals(event.type())) {
                payload = event.data();
            }
        }
        return payload;
    }
}
