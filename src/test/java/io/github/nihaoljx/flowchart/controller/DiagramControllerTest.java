package io.github.nihaoljx.flowchart.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DiagramController 契约测试
 *
 * 测试范围：HTTP 层契约——路径映射与响应外壳（Result 包装）
 *
 * 现状：M1 前置代码清算删除了 /api/generate 与 /api/download（后端不再渲染图片，见 ADR-4），
 * 本类随之收缩为健康检查 + 「旧端点确实不再被映射」的防回归断言。
 * M1 v2-11 补入 POST /api/chat（SSE）后，在此扩测事件流契约。
 *
 * 为什么用 standaloneSetup 而不是 @WebMvcTest：
 * standalone 不启动 Spring 上下文，不受 application.yml 缺失 / 条件装配影响，
 * 跑得快且稳定；本类只关心 controller 自己的契约，不需要真实上下文。
 * 若以后要连真实上下文（验证 springdoc、消息转换器全量配置），
 * 换成 @WebMvcTest(controllers = DiagramController.class) 即可。
 *
 * 覆盖的失败模式：接口路径被静默改掉、响应外壳字段改名、已下线的旧端点被误加回来
 */
class DiagramControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DiagramController()).build();
    }

    @Test
    @DisplayName("健康检查：返回 200，Result 外壳 code=200 / message=ok")
    void healthReturnsOk() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("ok"));
    }

    @Test
    @DisplayName("防回归：已清算的 /api/generate 不再被映射（404）")
    void legacyGenerateEndpointIsGone() throws Exception {
        mockMvc.perform(post("/api/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"甲到乙\",\"type\":\"flowchart\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("防回归：已清算的 /api/download 不再被映射（404）")
    void legacyDownloadEndpointIsGone() throws Exception {
        mockMvc.perform(post("/api/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plantUml\":\"@startuml\\n@enduml\",\"format\":\"png\"}"))
                .andExpect(status().isNotFound());
    }
}
