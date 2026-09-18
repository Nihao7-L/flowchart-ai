package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nihaoljx.flowchart.graph.ValidationIssue;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 组装服务（v2-8 / 路线2 重写；v2-33 补"编辑模式"）
 *
 * <p>职责：读 {@code schemas/scene.schema.json}（出图契约唯一真相源）→ 派生 LLM 的图表生成 prompt。
 *
 * <p>旧方案按"图类型"各配一份 txt 模板（flowchart / mindmap / architecture），路线2 取消图类型分类后，
 * 模板不再适用。改为由 schema 直接派生 prompt，保证「LLM 输出」与「后端解析契约」永不漂移——
 * 契约改一个字段，prompt 自动跟着变，不需要人去同步两份文件。
 *
 * <p><b>v2-33 新增两种模式：</b>
 * <ul>
 *   <li><b>新建</b>（{@code currentSceneJson == null}）：只有用户需求，行为与改造前一致。</li>
 *   <li><b>编辑</b>（会话里已有模型）：把当前场景整份塞进 prompt，并附加"修改规则"。
 *       2026-09-16 用户实测：此前 prompt 里只有用户这一句，LLM 看不到眼下的图，
 *       于是"删掉某个节点"被理解成"画一张新图"，前端整体替换画布 →
 *       **之前画的全没了**（实测 23 个元素 → 2 个元素）。</li>
 * </ul>
 */
@Service
public class PromptService {

    /** 契约文件在 classpath 上的位置 */
    private static final String SCHEMA_PATH = "schemas/scene.schema.json";

    /**
     * schema 内容缓存：只在首次加载时读一次文件。
     * 用 ConcurrentHashMap —— Spring MVC 请求处理多线程，懒加载的 put 必须线程安全
     * （HashMap 并发 put 可能链表成环导致后续 get 死循环，且仅在并发下暴露）。
     */
    private final ConcurrentHashMap<String, String> schemaCache = new ConcurrentHashMap<>();

    /**
     * 会话模型的解析器：把「场景 elements」与「用户手绘旁路」
     * 拆成两段分别渲染（v2-34）。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Logger LOG =
            LoggerFactory.getLogger(PromptService.class);

    /**
     * 核心方法：用户输入 → 完整 prompt
     *
     * <p>prompt 结构 = 角色说明 + 契约（scene.schema.json 原样嵌入）+ 作图规则
     * + [当前场景 + 修改规则] + 用户需求。
     * 契约部分直接读取文件，不手写 JSON 例子，杜绝与契约失同步。
     *
     * @param userText         用户在输入框里的原始需求
     * @param currentSceneJson 会话当前模型的 JSON（新建图时传 null）
     * @return 可直接发给 LLM 的完整 prompt
     */
    public String buildPrompt(String userText, String currentSceneJson) throws IOException {
        String schema = loadSchema();
        StringBuilder sb = new StringBuilder();
        sb.append("你是图表生成助手。请严格按下面的 JSON Schema 契约，把用户的需求转成一份元素场景（scene）。\n\n");
        sb.append("## 契约（scene.schema.json）\n");
        sb.append("```json\n").append(schema).append("\n```\n\n");
        sb.append("## 作图规则\n");
        sb.append("- 只输出一个 JSON 对象，顶层字段为 `elements`（有序数组，数组顺序即图层 z 序：靠后 = 在上层）。\n");
        sb.append("- `type` 取值范围：rectangle / ellipse / diamond / arrow / line / text / frame。\n");
        sb.append("- **不要输出 `freedraw`（手绘路径）**：那是人类手绘的表达形式，无法参与自动布局，"
                + "渲染器也算不出它的取景边界（会让整张图都看不见）。\n");
        sb.append("- 图形（rectangle / ellipse / diamond）需有 `id`、`label`（图形内文字）、几何（x / y / width / height）。\n");
        sb.append("- **凡是连接两个图形的连线，一律用 `arrow` 并写 `start.id` / `end.id` 绑定到图形 id**："
                + "渲染器里只有 arrow 的端点绑定会让连线跟着图形一起动。"
                + "`line` 与「只给 points 的箭头」画上去位置就固定了，用户一拖动图形连线就脱开。"
                + "`line` 仅用于装饰性分隔线（不连接图形）。\n");
        sb.append("- 箭头仅在连到非图形（空白 / frame 边框）时才用 `points` 自定义路径；"
                + "两条线交叉需要绕行时，可以把箭头拆成两条。\n");
        sb.append("- 坐标 x / y 只是**种子值**（单位 px，给近似值即可）："
                + "后端会按图的拓扑重新排布，你不需要精确对齐、也不必操心元素重叠，"
                + "把精力放在结构与文字上。\n");
        sb.append("- **单张图的元素总数控制在 40 个以内**（图形 + 连线 + 文字）："
                + "元素越多出图越慢。需求规模更大时，先给出分步纲要并请用户确认，"
                + "再按指示分批生成，不要一次硬吐几十个元素。\n");
        sb.append("- 不要输出 JSON 之外的解释文字，只返回 JSON。\n\n");

        boolean editMode = currentSceneJson != null && !currentSceneJson.isBlank();
        if (editMode) {
            sb.append("## 当前场景（用户眼下看到的图，请在它的基础上修改）\n");
            sb.append("```json\n").append(sceneOnly(currentSceneJson)).append("\n```\n\n");
            sb.append(userElementsBlock(currentSceneJson));
            sb.append("## 修改规则（必须遵守）\n");
            sb.append("- **先判断用户这一句的意图，两种走法完全不同**：\n");
            sb.append("  - **改这张图**：用户在谈论当前场景里的东西（提到某个节点 / 某段文字 / 某种颜色，"
                    + "或用「删掉」「改成」「加上」「连起来」这类说法）——"
                    + "必须严格按下面各条保真规则执行。\n");
            sb.append("  - **另画一张**：用户要的是一个**全新主题**的图，与当前场景明显无关"
                    + "（例：当前是登录流程，这一句要「订单处理流程」）——"
                    + "丢弃旧元素，只输出新图。\n");
            sb.append("- 你输出的是**修改后的完整场景**（全量 elements），不是差异片段、不是新增部分。\n");
            sb.append("- **没有要求改动的元素必须原样保留**：`id` / `type` / 几何（x / y / width / height）"
                    + "/ `label` / 样式全部照抄，不要重新编号、不要重排坐标、不要精简掉你认为多余的元素。\n");
            sb.append("- 「删除某元素」= 结果里不包含它（同时删掉连到它的箭头）。\n");
            sb.append("- 「新增元素」= 起一个不与现有 id 重复的新 id（沿用现有命名风格）。\n");
            sb.append("- 「改文字 / 改颜色」= 只动那一个字段，其余照抄。\n");
            sb.append("- 只有「另画一张」的情况，或用户明确要求「重新画 / 换一张 / 全部重做」，"
                    + "才允许丢弃原有元素。\n\n");
        }

        sb.append("## 用户需求\n");
        sb.append(userText).append("\n");
        return sb.toString();
    }

    /**
     * 修正 prompt：把上一轮的 issues 塞进去，
     * 让 LLM 知道哪里要改（v2-10）
     *
     * @param userText         原始用户需求
     * @param issues           上一轮校验失败的 issue 列表
     * @param currentSceneJson 会话当前模型的 JSON（新建图时传 null）
     * @return 修正 prompt
     */
    public String buildFixPrompt(
            String userText,
            List<ValidationIssue> issues,
            String currentSceneJson)
            throws IOException {

        String base = buildPrompt(userText, currentSceneJson);
        StringBuilder fix = new StringBuilder(base);
        fix.append("\n\n## ⚠️ 上一轮校验未通过\n");
        fix.append("请只修正以下问题，"
                + "不要重画整张图：\n");
        for (ValidationIssue issue : issues) {
            fix.append("- 字段 `")
                    .append(issue.field())
                    .append("`：")
                    .append(issue.reason())
                    .append(" → ")
                    .append(issue.hint())
                    .append("\n");
        }
        return fix.toString();
    }

    /**
     * 场景段：把会话模型里的"用户手绘"旁路摘掉之后的 JSON。
     *
     * <p>为什么要摘：{@code _userElements} 是后端内部字段，
     * 混在"当前场景"里会让 LLM 以为它是场景的一部分，
     * 进而原样抄进输出 —— 那反而成了新的重复源（v2-34）。
     *
     * @param modelJson 会话模型 JSON
     * @return 只含 elements 的 JSON；解析失败时原样返回
     */
    private String sceneOnly(String modelJson) {
        try {
            JsonNode root = objectMapper.readTree(modelJson);
            if (root.has(SessionStore.USER_ELEMENTS_KEY)) {
                ((ObjectNode) root).remove(
                        SessionStore.USER_ELEMENTS_KEY);
                return objectMapper.writeValueAsString(root);
            }
            return modelJson;
        } catch (Exception e) {
            LOG.warn("当前场景解析失败，按原样嵌入: {}",
                    e.getMessage());
            return modelJson;
        }
    }

    /**
     * 用户手绘元素段（v2-34）
     *
     * <p>用户手绘的线**不在 elements 里**（后端不持有它们的几何），
     * 于是 LLM 完全不知道用户已经在那里连了一条线，会再画一条 ——
     * 用户看到的就是"同一处两条线"（2026-09-16 实测：
     * 「多添加线的地方是我删除一次然后又添加上去的」）。
     * 这里把用户画的东西单列一段告诉它，并明确禁止抄进输出。
     *
     * @param modelJson 会话模型 JSON
     * @return 提示段落；没有用户手绘时返回空串
     */
    private String userElementsBlock(String modelJson) {
        try {
            JsonNode userElements = objectMapper
                    .readTree(modelJson)
                    .get(SessionStore.USER_ELEMENTS_KEY);
            if (userElements == null
                    || !userElements.isArray()
                    || userElements.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("## 用户手工添加的内容（不是你画的）\n");
            sb.append("用户在画布上**手工**画了下面这些元素，"
                    + "它们不在上面的 elements 里：\n");
            sb.append("```json\n")
                    .append(objectMapper
                            .writeValueAsString(userElements))
                    .append("\n```\n");
            sb.append("- 它们是用户自己画的、**会一直被保留**，"
                    + "你既不需要、也不要把它们复制进 elements。\n");
            sb.append("- 如果它们已经表达了某条连线或标注，"
                    + "**不要重复画一条**。\n\n");
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("用户手绘段解析失败，跳过: {}",
                    e.getMessage());
            return "";
        }
    }

    private String loadSchema() throws IOException {
        try {
            return schemaCache.computeIfAbsent(SCHEMA_PATH, path -> {
                try {
                    ClassPathResource resource = new ClassPathResource(path);
                    try (var in = resource.getInputStream()) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw e;
        }
    }
}
