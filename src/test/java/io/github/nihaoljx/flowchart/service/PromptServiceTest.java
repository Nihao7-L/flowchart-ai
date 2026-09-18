package io.github.nihaoljx.flowchart.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptService 单元测试（v2-8 / 路线2）
 *
 * <p>测试范围：prompt 由 scene.schema.json 派生——验证产物包含用户需求、内嵌契约、列出全部元素类型、并指导箭头绑定。
 * 不启动 Spring，直接 new PromptService()；schema 经 classpath 在测试期由 process-resources 复制到 target/classes。
 */
class PromptServiceTest {

    private PromptService promptService;

    @BeforeEach
    void setUp() {
        promptService = new PromptService();
    }

    @Test
    @DisplayName("buildPrompt 产物应包含用户需求文本")
    void testContainsUserText() throws IOException {
        // Given
        String userText = "用户输入账号密码 → 系统验证 → 成功进入首页";

        // When
        String prompt = promptService.buildPrompt(userText, null);

        // Then
        assertTrue(prompt.contains(userText), "用户输入应出现在 prompt 中");
    }

    @Test
    @DisplayName("buildPrompt 产物应内嵌 scene.schema.json 契约")
    void testEmbedsSchema() throws IOException {
        // When
        String prompt = promptService.buildPrompt("随便画点东西", null);

        // Then
        assertTrue(prompt.contains("\"elements\""), "prompt 应内嵌 schema 的 elements 字段");
        assertTrue(prompt.contains("scene.schema.json"), "prompt 应标注契约来源");
    }

    @Test
    @DisplayName("buildPrompt 应列出 7 种可生成 type，并明确禁用 freedraw")
    void testListsElementTypes() throws IOException {
        // When
        String prompt = promptService.buildPrompt("画个架构示意", null);

        // Then
        String[] types = {
                "rectangle", "ellipse", "diamond", "arrow", "line", "text", "frame"};
        for (String type : types) {
            assertTrue(prompt.contains(type), "prompt 应提及元素类型: " + type);
        }
        // freedraw 是唯一被禁用的类型：它的取景边界算不出来，
        // 2026-09-16 实测会让整张图都看不见（详见 sceneAdapter 注释）
        assertTrue(prompt.contains("不要输出 `freedraw`"),
                "prompt 应明确禁止 LLM 输出 freedraw");
    }

    @Test
    @DisplayName("buildPrompt 应指导箭头优先用 start/end 绑定")
    void testArrowBindingGuidance() throws IOException {
        // When
        String prompt = promptService.buildPrompt("把 A 连到 B", null);

        // Then：应同时提到 start 与 end 两种绑定键
        assertTrue(prompt.contains("start"), "应指导 arrow 用 start 绑定");
        assertTrue(prompt.contains("end"), "应指导 arrow 用 end 绑定");
    }

    @Test
    @DisplayName("schema 缓存：同一输入两次调用结果一致")
    void testSchemaCache() throws IOException {
        // When：同一输入调用两次
        String prompt1 = promptService.buildPrompt("缓存测试", null);
        String prompt2 = promptService.buildPrompt("缓存测试", null);

        // Then：结果应一致（schema 缓存生效，不产生额外随机性）
        assertEquals(prompt1, prompt2, "同一输入两次调用结果应一致");
    }

    /**
     * 编辑模式（v2-33）：会话里已有模型时，当前场景必须整份进 prompt。
     *
     * <p>这是"第二次输入要在原图上改"这条需求的**治本**手段：
     * 2026-09-16 之前 prompt 里只有用户这一句话，LLM 看不到眼下的图，
     * 于是"删掉某个节点"被理解成"画一张新图"，前端整体替换画布，
     * 之前画的全没了（实测 23 个元素 → 2 个元素）。
     */
    @Test
    @DisplayName("编辑模式：当前场景进 prompt，并附「没让改的原样保留」规则")
    void testEditModeEmbedsCurrentScene()
            throws IOException {
        // Given
        String current = "{\"elements\":[{\"id\":\"n1\","
                + "\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":60}]}";

        // When
        String prompt = promptService
                .buildPrompt("删掉 n1", current);

        // Then
        assertTrue(prompt.contains(current),
                "当前场景 JSON 必须原样进 prompt");
        assertTrue(prompt.contains("修改规则"),
                "编辑模式应带「修改规则」段");
        assertTrue(prompt.contains("原样保留"),
                "必须要求未涉及元素原样保留");
        assertTrue(prompt.contains("删除某元素"),
                "应说明删除语义 = 结果里不含它");
        assertTrue(prompt.contains("另画一张"),
                "必须给出「新主题 = 另画一张」的出口，"
                        + "否则用户换主题时会被强行保留旧元素");
    }

    /**
     * 用户手绘旁路（v2-34）必须**单列一段**，不能混在"当前场景"里。
     *
     * <p>混进去的后果：LLM 会把它当成场景的一部分原样抄进输出 ——
     * 那反而制造了新的重复源。这里既验证"该出现的信息出现了"，
     * 也验证"内部字段名没漏进场景段"。
     */
    @Test
    @DisplayName("编辑模式：用户手绘单列成段，且内部字段不混进当前场景")
    void testUserElementsRenderedSeparately()
            throws IOException {
        // Given：模型里既有 elements，也有用户手绘旁路
        String model = "{\"elements\":[{\"id\":\"n1\","
                + "\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":60}],"
                + "\"_userElements\":[{\"id\":\"u1\","
                + "\"type\":\"arrow\",\"startId\":\"n1\","
                + "\"endId\":\"n2\"}]}";

        // When
        String prompt = promptService
                .buildPrompt("删掉 n2", model);

        // Then：用户手绘要出现，且明确要求"别重复画"
        assertTrue(prompt.contains("用户手工添加的内容"),
                "用户手绘必须单列成段");
        assertTrue(prompt.contains("不要重复画一条"),
                "必须明说不要重复画，否则 LLM 会再补一条");

        // 场景段里不许出现内部字段名 —— 出现即意味着 LLM 会把它
        // 当成 elements 之外还要照抄的东西
        String sceneBlock = prompt.substring(
                prompt.indexOf("## 当前场景"),
                prompt.indexOf("## 用户手工添加的内容"));
        assertFalse(sceneBlock.contains("_userElements"),
                "内部旁路字段不得混进场景段: " + sceneBlock);
        assertTrue(sceneBlock.contains("\"id\":\"n1\""),
                "场景段必须保留 elements");
    }

    @Test
    @DisplayName("编辑模式：没有用户手绘时不出该段")
    void testNoUserElementsBlockWhenAbsent()
            throws IOException {
        String current = "{\"elements\":[{\"id\":\"n1\","
                + "\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":60}]}";

        assertFalse(
                promptService.buildPrompt("删掉 n1", current)
                        .contains("用户手工添加的内容"));
    }

    @Test
    @DisplayName("编辑模式：null / 空串都退化为新建，不出「修改规则」段")
    void testBlankSceneFallsBackToCreateMode()
            throws IOException {
        assertFalse(
                promptService.buildPrompt("画个图", null)
                        .contains("修改规则"));
        assertFalse(
                promptService.buildPrompt("画个图", "")
                        .contains("修改规则"));
    }
}
