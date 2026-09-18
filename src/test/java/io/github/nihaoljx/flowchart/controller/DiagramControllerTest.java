package io.github.nihaoljx.flowchart.controller;

import io.github.nihaoljx.flowchart.service.GenerationService;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet
        .request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet
        .request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet
        .request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet
        .request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet
        .result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet
        .result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet
        .result.MockMvcResultMatchers.status;

/**
 * DiagramController 契约测试
 *
 * <p>M1 新增 POST /api/chat 与 PATCH
 * /api/model/positions，构造器需要依赖注入，
 * 用 mock 代替真实实现。
 *
 * <p><b>2026-09-16 补测：</b>这两个端点在 M1 落盘时漏了用例
 * （此前只覆盖 /api/health 与两条 404 防回归），
 * 导致 PATCH /api/model/positions 因
 * 「@RequestParam 未写显式名字 + 编译未开 -parameters」
 * 全量 500 时，32 个测试全绿、门禁毫无察觉。
 * 下面两条画布同步用例即为该缺陷的回归护栏。
 *
 * <p><b>2026-09-16（v2-34）改造：</b>PATCH /api/model/positions
 * 升级为 PUT /api/model/canvas —— 只回写坐标时，用户的删除与手绘
 * 对后端是隐形的，被删元素下一轮复活、手绘的线被重复画一条。
 */
class DiagramControllerTest {

    private MockMvc mockMvc;
    private GenerationService genService;
    private SessionStore sessionStore;

    @BeforeEach
    void setUp() {
        genService = mock(GenerationService.class);
        sessionStore = mock(SessionStore.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new DiagramController(
                                genService,
                                sessionStore))
                .build();
    }

    @Test
    @DisplayName("健康检查：返回 200")
    void healthReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code")
                        .value(200))
                .andExpect(jsonPath("$.message")
                        .value("ok"));
    }

    @Test
    @DisplayName("生成图表：POST /api/chat 以 SSE 异步开始，并透传 sessionId + message")
    void chatStartsSseStreamAndForwardsMessage()
            throws Exception {
        mockMvc.perform(post("/api/chat")
                .contentType(
                        MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"s-1\","
                        + "\"message\":\"画一个登录流程\"}"))
                .andExpect(request().asyncStarted());

        // generate 跑在控制器新建的线程里，用 timeout 等它被调用。
        // sessionId 必须一起透传：生成服务靠它取"当前图模型"当编辑上下文，
        // 并在成功后把新模型写回（2026-09-16 起）
        verify(genService, timeout(2000))
                .generate(eq("s-1"),
                        eq("画一个登录流程"),
                        any());
    }

    /**
     * 回归护栏：PUT /api/model/canvas 必须能解析 sessionId 与请求体，
     * 并把三类信息（坐标 / 被删元素 / 用户手绘）分别落到 SessionStore。
     *
     * <p>若去掉 @RequestParam("sessionId") 的显式名字、
     * 同时关闭编译器 -parameters，本用例会以 500 失败，
     * 而不会像 2026-09-16 那次一样被悄悄放过。
     */
    @Test
    @DisplayName("画布同步：解析 sessionId，四类信息分别写入 store")
    @SuppressWarnings("unchecked")
    void syncCanvasParsesSessionIdAndWrites()
            throws Exception {
        mockMvc.perform(put("/api/model/canvas")
                .param("sessionId", "s-1")
                .contentType(
                        MediaType.APPLICATION_JSON)
                .content("{\"positions\":"
                        + "{\"n1\":{\"x\":10,\"y\":20}},"
                        + "\"removedIds\":[\"n9\"],"
                        + "\"userElements\":["
                        + "{\"id\":\"u1\","
                        + "\"type\":\"arrow\","
                        + "\"startId\":\"n1\","
                        + "\"endId\":\"n2\"}],"
                        + "\"unboundArrows\":["
                        + "{\"id\":\"a5\",\"x\":100,\"y\":250,"
                        + "\"points\":[[0,0],[300,0]]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code")
                        .value(200));

        ArgumentCaptor<Map<String,
                Map<String, Double>>> posCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(sessionStore)
                .updatePositions(eq("s-1"),
                        posCaptor.capture());

        // 实测（2026-09-16）：该端点到达 SessionStore 的数值是 Integer，
        // 而不是 Map<String, Double> 声明所暗示的 Double。
        // 对照实验：直接用 Jackson 反序列化到 Map<String, Map<String, Double>>
        // 得到的是 Double，裸 Map<String, Object> 才是 Integer ——
        // 说明本端点的泛型未参与反序列化决策（类型契约瑕疵，非崩溃）。
        // InMemorySessionStore 按 Object 存取、不做数值假设，故功能无碍；
        // 这里按 Number 断言，如实反映现状（收窄见 activeLog 2026-09-16）。
        Map<?, ?> n1 = posCaptor.getValue().get("n1");
        assertEquals(10.0,
                ((Number) n1.get("x")).doubleValue());
        assertEquals(20.0,
                ((Number) n1.get("y")).doubleValue());

        // 用户删掉的元素必须真的到达 store —— 这是 v2-34 修的核心：
        // 在这之前删除对后端是隐形的，被删元素下一轮会"复活"
        ArgumentCaptor<Collection<String>> removedCaptor =
                ArgumentCaptor.forClass(
                        Collection.class);
        verify(sessionStore)
                .removeElements(eq("s-1"),
                        removedCaptor.capture());
        assertTrue(
                removedCaptor.getValue()
                        .contains("n9"),
                "被删元素 id 必须到达 store: "
                        + removedCaptor.getValue());

        // 用户手绘元素同理：后端要知道"这里已经有线了"，
        // 否则 LLM 会再画一条（同一处两条线）
        ArgumentCaptor<List<Map<String, Object>>>
                userCaptor = ArgumentCaptor.forClass(
                        List.class);
        verify(sessionStore)
                .setUserElements(eq("s-1"),
                        userCaptor.capture());
        assertEquals(1, userCaptor.getValue().size());
        assertEquals("u1", userCaptor.getValue()
                .get(0).get("id"));

        // 拖动一条线会让 Excalidraw 解除它的端点绑定。这件事必须到达
        // store，否则后端模型里那条线还是绑定的，下一轮渲染又把它
        // 绑回两端图形之间 —— 用户拖的那一下白拖（2026-09-16 实测）
        ArgumentCaptor<List<Map<String, Object>>>
                freeCaptor = ArgumentCaptor.forClass(
                        List.class);
        verify(sessionStore)
                .releaseBindings(eq("s-1"),
                        freeCaptor.capture());
        assertEquals(1, freeCaptor.getValue().size());
        Map<String, Object> free =
                freeCaptor.getValue().get(0);
        assertEquals("a5", free.get("id"));
        assertTrue(free.containsKey("points"),
                "解绑的线必须带上自己的路径点，"
                        + "否则模型里是条算不出长度的坏箭头: "
                        + free);
    }

    @Test
    @DisplayName("画布同步：缺 sessionId 返回 400（不得是 500）")
    void syncCanvasWithoutSessionIdIs400()
            throws Exception {
        mockMvc.perform(put("/api/model/canvas")
                .contentType(
                        MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(
                        status().isBadRequest());
    }

    @Test
    @DisplayName("防回归：旧的 PATCH /api/model/positions 已下线")
    void legacyPositionsEndpointIsGone()
            throws Exception {
        mockMvc.perform(patch("/api/model/positions")
                .param("sessionId", "s-1")
                .contentType(
                        MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status()
                        .is4xxClientError());
    }

    @Test
    @DisplayName("防回归：/api/generate 已下线")
    void legacyGenerateIsGone() throws Exception {
        mockMvc.perform(post("/api/generate")
                .contentType(
                        MediaType.APPLICATION_JSON)
                .content("{\"text\":\"甲到乙\"}"))
                .andExpect(
                        status().isNotFound());
    }

    @Test
    @DisplayName("防回归：/api/download 已下线")
    void legacyDownloadIsGone() throws Exception {
        mockMvc.perform(post("/api/download")
                .contentType(
                        MediaType.APPLICATION_JSON)
                .content("{\"plantUml\":\"x\"}"))
                .andExpect(
                        status().isNotFound());
    }
}
