package io.github.nihaoljx.flowchart.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptService 单元测试
 *
 * 测试范围：模板加载 + 占位符替换
 *
 * 注意：PromptService 用 ClassPathResource 读模板文件，
 *       测试时模板在 target/classes/templates/ 下（mvn compile 后自动复制），
 *       所以不用启动 Spring，直接 new PromptService() 就能读到模板。
 */
class PromptServiceTest {

    private PromptService promptService;

    @BeforeEach
    void setUp() {
        promptService = new PromptService();
    }

    @Test
    @DisplayName("流程图模板：占位符 {userText} 应被替换为用户输入")
    void testBuildPromptFlowchart() throws IOException {
        // Given
        String userText = "用户输入账号密码 → 系统验证 → 成功进入首页";

        // When
        String prompt = promptService.buildPrompt(userText, "flowchart");

        // Then
        assertFalse(prompt.contains("{userText}"), "占位符应被替换掉");
        assertTrue(prompt.contains(userText), "用户输入应出现在结果中");
    }

    @Test
    @DisplayName("思维导图模板：占位符 {userText} 应被替换")
    void testBuildPromptMindmap() throws IOException {
        // Given
        String userText = "电商系统";

        // When
        String prompt = promptService.buildPrompt(userText, "mindmap");

        // Then
        assertFalse(prompt.contains("{userText}"), "占位符应被替换掉");
        assertTrue(prompt.contains(userText), "用户输入应出现在结果中");
    }

    @Test
    @DisplayName("架构图模板：占位符 {userText} 应被替换")
    void testBuildPromptArchitecture() throws IOException {
        // Given
        String userText = "在线教育平台架构";

        // When
        String prompt = promptService.buildPrompt(userText, "architecture");

        // Then
        assertFalse(prompt.contains("{userText}"), "占位符应被替换掉");
        assertTrue(prompt.contains(userText), "用户输入应出现在结果中");
    }

    @Test
    @DisplayName("未知类型应回退到流程图模板")
    void testBuildPromptUnknownTypeFallback() throws IOException {
        // Given：传一个不存在的类型
        String userText = "测试内容";
        String unknownType = "nonexistent";

        // When
        String prompt = promptService.buildPrompt(userText, unknownType);

        // Then：不应该包含占位符（说明走了流程图模板，替换成功了）
        assertFalse(prompt.contains("{userText}"), "回退到流程图模板后占位符也应被替换");
        assertTrue(prompt.contains(userText), "用户输入应出现在结果中");
    }

    @Test
    @DisplayName("模板缓存：同一类型第二次调用应返回相同结果")
    void testTemplateCache() throws IOException {
        // Given
        String userText = "缓存测试";

        // When：同一类型调用两次
        String prompt1 = promptService.buildPrompt(userText, "flowchart");
        String prompt2 = promptService.buildPrompt(userText, "flowchart");

        // Then：两次结果应该一样（缓存生效）
        assertEquals(prompt1, prompt2, "同一类型两次调用结果应一致");
    }
}
