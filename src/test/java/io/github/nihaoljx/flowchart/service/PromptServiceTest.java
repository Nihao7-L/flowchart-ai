package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.graph.RelationCatalog;
import io.github.nihaoljx.flowchart.graph.ScenePlanner;
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
    @DisplayName("buildPrompt 应列出全部元素 type（含 freedraw / image / embeddable / magicframe）")
    void testListsElementTypes() throws IOException {
        // When
        String prompt = promptService.buildPrompt("画个架构示意", null);

        // Then
        String[] types = {
                "rectangle", "ellipse", "diamond", "arrow", "line", "text", "frame",
                "freedraw", "image", "embeddable", "magicframe"};
        for (String type : types) {
            assertTrue(prompt.contains(type), "prompt 应提及元素类型: " + type);
        }
        // md v5.8.0 约束8：freedraw 默认不开（仅用户明确要手绘/涂鸦时才用）
        assertTrue(prompt.contains("默认不开"),
                "prompt 应写明 freedraw 默认不开");
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

    /**
     * 分组 / 画框口径（md v5.8.0）：提示词正文已改由 {@code system-prompt.md} 提供，
     * 这里验证 md 覆盖了分组与画框的关键约束，且阈值数字与后端
     * {@link ScenePlanner#MODULE_THRESHOLD} 一致（同一处数字，写死两份迟早漂移）。
     */
    @Test
    @DisplayName("md 提示词应含分组 / 画框约束，阈值与 ScenePlanner.MODULE_THRESHOLD 一致")
    void testModuleGroupingGuidance() throws IOException {
        // When
        String prompt = promptService
                .buildPrompt("画一个电商微服务架构图", null);

        // Then
        assertTrue(prompt.contains("group"),
                "应要求 LLM 写 group 字段");
        assertTrue(prompt.contains(String.valueOf(
                        ScenePlanner.MODULE_THRESHOLD)),
                "阈值仍应与 ScenePlanner.MODULE_THRESHOLD 一致（用于 frame 认领判定）");
        assertTrue(prompt.contains("逐字相同"),
                "框的 name 必须与模块 group 逐字相同（否则认领不上）");
        assertTrue(prompt.contains("children"),
                "应说明 children 由后端自动填（在「不要写进输出」清单里）");
    }

    /**
     * 单一来源守卫：md 正文里写死的 relation 值集合必须覆盖
     * {@link RelationCatalog#ALL_RELATIONS} —— md 现在是提示词的唯一正文，
     * 若 RelationCatalog 扩了值而 md 没跟上，模型就不知道新值，会漏用。
     */
    @Test
    @DisplayName("md 提示词必须列出 RelationCatalog 的全部 relation（单一来源不漂移）")
    void testAllRelationsListed() throws IOException {
        String prompt = promptService.buildPrompt("画个图", null);
        for (String rel : RelationCatalog.ALL_RELATIONS) {
            assertTrue(prompt.contains(rel), "md 应提及 relation: " + rel);
        }
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

    /**
     * v2-42：编辑模式元素数 **超过** {@link PromptService#EDIT_INLINE_MAX_ELEMENTS}
     * 时，当前场景不再整份贴出，改为「结构摘要 + 被改元素全文 + 引用骨架」。
     *
     * <p>这一条守的是"大图别把 47 个元素整份灌进去"——
     * 骨架里**仍带 id / type / 几何 / label**，否则输出侧
     * 「未点名元素原样保留」就没有依据，图会散。
     */
    @Test
    @DisplayName("v2-42: >阈值走结构摘要，骨架全量列出 id/type/几何")
    void testCompactModeForLargeScene() throws IOException {
        int n = PromptService.EDIT_INLINE_MAX_ELEMENTS + 1;
        String prompt = promptService.buildPrompt(
                "把订单服务改成支付服务", bigScene(n));

        assertTrue(prompt.contains("结构摘要"),
                "大图应改走结构摘要");
        assertTrue(prompt.contains("共 " + n + " 个元素"),
                "摘要应标出元素总数");
        assertFalse(prompt.contains("## 当前场景（用户眼下看到的图"),
                "大图不应再整份贴出当前场景");
        for (int i = 1; i <= n; i++) {
            assertTrue(prompt.contains("n" + i + " rectangle"),
                    "骨架必须全量含元素 n" + i);
        }
        assertTrue(prompt.contains("(200,0,160x60)"),
                "骨架必须带几何，否则未点名元素无法原样保留");
    }

    @Test
    @DisplayName("v2-42: 阈值边界 —— 恰好等于阈值仍整份贴出（小图行为零漂移）")
    void testFullDumpAtThreshold() throws IOException {
        String prompt = promptService.buildPrompt(
                "删掉 n1",
                bigScene(PromptService.EDIT_INLINE_MAX_ELEMENTS));

        assertTrue(prompt.contains("## 当前场景（用户眼下看到的图"),
                "恰好等于阈值应仍整份贴出");
        assertFalse(prompt.contains("结构摘要"),
                "未超过阈值不得走摘要");
    }

    @Test
    @DisplayName("v2-42: 被点名元素给完整 JSON，引用骨架列出箭头端点")
    void testCompactMentionedAndRefs() throws IOException {
        StringBuilder sb = new StringBuilder("{\"elements\":[");
        for (int i = 1; i <= 31; i++) {
            sb.append("{\"id\":\"n").append(i)
                    .append("\",\"type\":\"rectangle\",\"x\":").append(i * 200)
                    .append(",\"y\":0,\"width\":160,\"height\":60,\"label\":{\"text\":\"")
                    .append(i == 7 ? "订单服务" : "节点" + i)
                    .append("\"}},");
        }
        sb.append("{\"id\":\"e1\",\"type\":\"arrow\",\"start\":{\"id\":\"n1\"},"
                + "\"end\":{\"id\":\"n2\"},\"label\":{\"text\":\"调用\"}}]}");

        String prompt = promptService.buildPrompt("把「订单服务」改名", sb.toString());

        assertTrue(prompt.contains("你可能要改的元素"),
                "点名元素应有完整定义段");
        assertTrue(prompt.contains("\"id\":\"n7\""),
                "被点名的 n7 应给完整 JSON");
        assertTrue(prompt.contains("引用骨架"),
                "应给出引用骨架");
        assertTrue(prompt.contains("e1: n1 -> n2"),
                "引用骨架应列出箭头端点");
    }

    /**
     * 造一个含 {@code count} 个矩形的大场景（v2-42 测试用）。
     *
     * @param count 元素个数
     * @return 场景 JSON
     */
    private String bigScene(int count) {
        StringBuilder sb = new StringBuilder("{\"elements\":[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"id\":\"n").append(i)
                    .append("\",\"type\":\"rectangle\",\"x\":").append(i * 200)
                    .append(",\"y\":0,\"width\":160,\"height\":60,\"label\":{\"text\":\"节点")
                    .append(i).append("\"}}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
