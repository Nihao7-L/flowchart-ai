package io.github.nihaoljx.flowchart.controller;

import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.model.FlowchartData;
import io.github.nihaoljx.flowchart.model.MindmapData;
import io.github.nihaoljx.flowchart.service.DiagramService;
import io.github.nihaoljx.flowchart.service.ParserService;
import io.github.nihaoljx.flowchart.service.PromptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DiagramController 契约测试
 *
 * 测试范围：HTTP 层契约——路径映射、请求校验、响应结构（Result 包装）、状态码
 *
 * 为什么用 standaloneSetup 而不是 @WebMvcTest：
 * standalone 不启动 Spring 上下文，不受 application.yml 缺失 / 条件装配影响，
 * 跑得快且稳定；本类只关心 controller 自己的契约，不需要真实上下文。
 * 若以后要连真实上下文（验证 springdoc、消息转换器全量配置），
 * 换成 @WebMvcTest(controllers = DiagramController.class) + @MockBean 四个依赖即可。
 *
 * 覆盖的失败模式：参数校验被绕过、错误码漂移、响应字段改名
 */
class DiagramControllerTest {

    private MockMvc mockMvc;
    private PromptService promptService;
    private LlmProvider llmProvider;
    private ParserService parserService;
    private DiagramService diagramService;

    @BeforeEach
    void setUp() {
        promptService = mock(PromptService.class);
        llmProvider = mock(LlmProvider.class);
        parserService = mock(ParserService.class);
        diagramService = mock(DiagramService.class);

        DiagramController controller = new DiagramController();
        ReflectionTestUtils.setField(controller, "promptService", promptService);
        ReflectionTestUtils.setField(controller, "llmProvider", llmProvider);
        ReflectionTestUtils.setField(controller, "parserService", parserService);
        ReflectionTestUtils.setField(controller, "diagramService", diagramService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 造一份最小可用的流程图数据，供正常路径复用 */
    private FlowchartData sampleFlowchart() {
        FlowchartData data = new FlowchartData();
        data.setTitle("登录流程");
        FlowchartData.Node start = new FlowchartData.Node();
        start.setId("1");
        start.setType("start");
        start.setLabel("开始");
        FlowchartData.Node end = new FlowchartData.Node();
        end.setId("2");
        end.setType("end");
        end.setLabel("结束");
        data.setNodes(new ArrayList<>(List.of(start, end)));
        data.setEdges(new ArrayList<>());
        return data;
    }

    /** 把「LLM → 解析 → 渲染」这条链全部打桩成成功，供正常路径复用 */
    private void stubHappyChain() throws Exception {
        when(llmProvider.isConfigured()).thenReturn(true);
        when(promptService.buildPrompt(anyString(), anyString())).thenReturn("PROMPT");
        when(llmProvider.chat(anyString())).thenReturn("LLM_TEXT");
        when(parserService.parse(anyString())).thenReturn(sampleFlowchart());
        when(parserService.parseArchitecture(anyString())).thenReturn(sampleFlowchart());
        when(diagramService.buildPlantUml(any())).thenReturn("@startuml\n@enduml");
        when(diagramService.buildArchitecture(any())).thenReturn("@startuml\n@enduml");
        when(diagramService.renderToSvg(anyString())).thenReturn("<svg/>");
    }

    // ==================== 健康检查 ====================

    @Test
    @DisplayName("健康检查：返回 200 且 Result 结构完整")
    void healthReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("ok"));
    }

    // ==================== /api/generate 参数校验 ====================

    @Test
    @DisplayName("生成：LLM 未配置时返回 503 而不是 500")
    void generateReturns503WhenLlmNotConfigured() throws Exception {
        when(llmProvider.isConfigured()).thenReturn(false);

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"甲到乙\",\"type\":\"flowchart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message", containsString("未配置")));
    }

    @Test
    @DisplayName("生成：文本为空返回 400")
    void generateRejectsBlankText() throws Exception {
        when(llmProvider.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \",\"type\":\"flowchart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请输入流程描述"));
    }

    @Test
    @DisplayName("生成：文本缺失（null）返回 400")
    void generateRejectsNullText() throws Exception {
        when(llmProvider.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"flowchart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请输入流程描述"));
    }

    @Test
    @DisplayName("生成：超过 2000 字返回 400")
    void generateRejectsTooLongText() throws Exception {
        when(llmProvider.isConfigured()).thenReturn(true);
        String tooLong = "字".repeat(2001);

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + tooLong + "\",\"type\":\"flowchart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("2000")));
    }

    @Test
    @DisplayName("生成：恰好 2000 字应放行（边界不误杀）")
    void generateAcceptsExactly2000Chars() throws Exception {
        stubHappyChain();

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "字".repeat(2000) + "\",\"type\":\"flowchart\"}"))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("生成：不支持的图表类型返回 400")
    void generateRejectsUnknownType() throws Exception {
        when(llmProvider.isConfigured()).thenReturn(true);

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"甲到乙\",\"type\":\"sequence\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("不支持的图表类型")));
    }

    // ==================== /api/generate 正常路径 ====================

    @Test
    @DisplayName("生成流程图：响应含 data / svg / plantUml / type 四个字段")
    void generateFlowchartHappyPath() throws Exception {
        stubHappyChain();

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"甲到乙\",\"type\":\"flowchart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.type").value("flowchart"))
                .andExpect(jsonPath("$.data.svg").value("<svg/>"))
                .andExpect(jsonPath("$.data.plantUml").exists())
                .andExpect(jsonPath("$.data.data.title").value("登录流程"));
    }

    @Test
    @DisplayName("生成思维导图：走 parseMindmap + buildMindMap")
    void generateMindmapHappyPath() throws Exception {
        MindmapData mindmap = new MindmapData();
        mindmap.setLabel("根");

        when(llmProvider.isConfigured()).thenReturn(true);
        when(promptService.buildPrompt(anyString(), anyString())).thenReturn("PROMPT");
        when(llmProvider.chat(anyString())).thenReturn("LLM_TEXT");
        when(parserService.parseMindmap(anyString())).thenReturn(mindmap);
        when(diagramService.buildMindMap(any())).thenReturn("@startmindmap\n@endumindmap");
        when(diagramService.renderToSvg(anyString())).thenReturn("<svg/>");

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"电商系统\",\"type\":\"mindmap\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.type").value("mindmap"))
                .andExpect(jsonPath("$.data.data.label").value("根"));
    }

    @Test
    @DisplayName("生成架构图：走 parseArchitecture + buildArchitecture")
    void generateArchitectureHappyPath() throws Exception {
        stubHappyChain();

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"三层架构\",\"type\":\"architecture\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.type").value("architecture"));
    }

    @Test
    @DisplayName("生成：type 缺失时默认按 flowchart 处理")
    void generateDefaultsToFlowchartWhenTypeMissing() throws Exception {
        stubHappyChain();

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"甲到乙\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.type").value("flowchart"));
    }

    @Test
    @DisplayName("生成：LLM 抛异常时统一兜成 500 且不泄漏堆栈")
    void generateWrapsLlmFailureAs500() throws Exception {
        when(llmProvider.isConfigured()).thenReturn(true);
        when(promptService.buildPrompt(anyString(), anyString())).thenReturn("PROMPT");
        when(llmProvider.chat(anyString())).thenThrow(new RuntimeException("LLM API 返回错误码 401"));

        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"甲到乙\",\"type\":\"flowchart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("AI 生成失败，请换一种描述试试"));
    }

    // ==================== /api/download ====================

    @Test
    @DisplayName("下载 SVG：返回附件头且 Content-Type 为 image/svg+xml")
    void downloadSvgReturnsAttachment() throws Exception {
        when(diagramService.renderToSvg(anyString())).thenReturn("<svg>hi</svg>");

        mockMvc.perform(post("/api/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantUml\":\"@startuml\\n@enduml\",\"format\":\"svg\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("image/svg+xml")))
                .andExpect(header().string("Content-Disposition", containsString("flowchart.svg")));
    }

    @Test
    @DisplayName("下载：format 缺失时默认导出 PNG")
    void downloadDefaultsToPng() throws Exception {
        when(diagramService.renderToPng(anyString())).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(post("/api/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantUml\":\"@startuml\\n@enduml\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType("image/png")))
                .andExpect(header().string("Content-Disposition", containsString("flowchart.png")));
    }

    @Test
    @DisplayName("下载：渲染失败时返回 500")
    void downloadReturns500OnRenderFailure() throws Exception {
        when(diagramService.renderToPng(anyString())).thenThrow(new RuntimeException("plantuml 崩了"));

        mockMvc.perform(post("/api/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantUml\":\"bad\",\"format\":\"png\"}"))
                .andExpect(status().is5xxServerError());
    }
}
