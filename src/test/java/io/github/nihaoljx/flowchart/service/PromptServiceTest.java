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

    // ===== 任务33新增：JSON Schema 加载测试 =====

    @Test
    @DisplayName("任务33：加载流程图 Schema，应包含枚举/必填/禁止额外字段契约")
    void testLoadSchemaFlowchart() throws IOException {
        // When
        String schema = promptService.loadSchema("flowchart");

        // Then：Schema 是"契约"——字段、类型、枚举、必填、额外属性都要写死
        assertTrue(schema.contains("\"enum\""), "节点 type 应有枚举约束");
        assertTrue(schema.contains("\"required\""), "应有必填字段声明");
        assertTrue(schema.contains("\"additionalProperties\": false"), "应禁止未声明字段");
        assertTrue(schema.contains("decision"), "枚举应包含 decision");
        assertFalse(schema.contains("{userText}"), "Schema 里不应有占位符");
    }

    @Test
    @DisplayName("任务33：加载思维导图 Schema，递归嵌套应使用 $ref")
    void testLoadSchemaMindmap() throws IOException {
        // When
        String schema = promptService.loadSchema("mindmap");

        // Then：树的递归结构靠 $ref 表达（引用自身定义）
        assertTrue(schema.contains("$ref"), "递归结构应使用 $ref");
        assertTrue(schema.contains("\"children\""), "应包含 children 字段");
        assertTrue(schema.contains("$defs"), "递归类型应定义在 $defs 中");
    }

    @Test
    @DisplayName("任务33：加载架构图 Schema，type 枚举只允许 component")
    void testLoadSchemaArchitecture() throws IOException {
        // When
        String schema = promptService.loadSchema("architecture");

        // Then
        assertTrue(schema.contains("\"enum\": [\"component\"]"), "type 应只允许 component");
        assertTrue(schema.contains("\"required\""), "应有必填字段声明");
    }

    @Test
    @DisplayName("任务33：未知类型应回退到流程图 Schema")
    void testLoadSchemaUnknownTypeFallback() throws IOException {
        // When：传一个不存在的类型
        String schema = promptService.loadSchema("nonexistent");

        // Then：回退到流程图 Schema（能读到枚举约束）
        assertTrue(schema.contains("\"enum\""), "应回退到流程图 Schema");
    }

    @Test
    @DisplayName("任务33：Schema 缓存：同一类型第二次调用应返回相同结果")
    void testSchemaCache() throws IOException {
        // When：同一类型加载两次
        String schema1 = promptService.loadSchema("flowchart");
        String schema2 = promptService.loadSchema("flowchart");

        // Then：两次结果应一致（缓存生效）
        assertEquals(schema1, schema2, "同一类型两次加载应一致");
    }

    // ===== 任务37新增：Mermaid 提示词 / Schema 测试 =====

    @Test
    @DisplayName("任务37：buildMermaidPrompt 替换 userText 与 type 占位符")
    void testBuildMermaidPrompt() throws IOException {
        String prompt = promptService.buildMermaidPrompt("用户登录验证", "flowchart");
        assertTrue(prompt.contains("用户登录验证"), "应包含用户原始文本");
        assertTrue(prompt.contains("flowchart"), "应包含图表类型");
        assertFalse(prompt.contains("{userText}"), "userText 占位符应被替换");
        assertFalse(prompt.contains("{type}"), "type 占位符应被替换");
    }

    @Test
    @DisplayName("任务37：loadMermaidSchema 返回非空且含 mermaid 字段契约")
    void testLoadMermaidSchema() throws IOException {
        String schema = promptService.loadMermaidSchema();
        assertFalse(schema.isBlank(), "Schema 不应为空");
        assertTrue(schema.contains("\"mermaid\""), "应约束只输出 mermaid 字段");
        assertTrue(schema.contains("\"required\""), "应有必填字段声明");
    }
}
