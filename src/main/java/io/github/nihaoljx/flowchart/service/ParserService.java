package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.model.FlowchartData;
import io.github.nihaoljx.flowchart.model.MindmapData;
import org.springframework.stereotype.Service;

import io.github.nihaoljx.flowchart.model.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON 解析服务
 *
 * 职责：
 * 1. 把 LLM 返回的纯文本转成 FlowchartData 对象
 * 2. 校验数据合法性（必须有 start/end、decision 必须有两条出边等）
 *
 * 注意：本类不关心 LLM 是哪家（Kimi/DeepSeek/...），
 *       LlmProvider 已经把原始响应提取成纯文本了。
 */
@Service
public class ParserService {

    /**
     * Jackson 核心对象：负责 JSON ↔ Java 对象互转
     *
     * 关键配置：FAIL_ON_UNKNOWN_PROPERTIES=false
     * 为什么？LLM 可能多输出字段（如 "model":"kimi"），默认行为是报错。
     * 设为 false 后，多余字段直接忽略，解析更容错。
     */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 从 LLM 的原始输出中提取纯净的 JSON 文本
     *
     * LLM 常见的三种"不乖"表现：
     * 1. 用 ```json 代码围栏包住 JSON
     * 2. JSON 前后夹带解释文字（"好的，这是结果：{...} 希望有帮助"）
     * 3. 以上两种混合
     *
     * 处理策略：
     *   第一步：如果有 ``` 围栏，先剥离围栏
     *   第二步：截取第一个 { 到最后一个 } 之间的内容（兜底夹带文字）
     *
     * @param raw LLM 返回的原始文本
     * @return 尽可能干净的 JSON 字符串
     */
    private String extractJsonText(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String text = raw.trim();

        // 第一步：剥离 ``` 围栏（```json\n...\n``` 或 ```\n...\n```）
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            // 确保有内容可提取：第一个换行在最后围栏之前
            if (firstNewline != -1 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }

        // 第二步：截取第一个 { 到最后一个 } 之间的内容
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');

        if (start != -1 && end > start) {
            return text.substring(start, end + 1);
        }

        // 找不到 JSON 边界，原样返回（让 Jackson 去报错）
        return text;
    }

    /**
     * 截断文本用于日志展示（避免超长文本刷屏）
     */
    private String truncateForLog(String text, int maxLen) {
        if (text == null) return "null";
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > maxLen
                ? compact.substring(0, maxLen) + "..."
                : compact;
    }

    /**
     * LLM 返回的纯文本 → FlowchartData 对象
     *
     * @param llmText LLM 返回的纯文本（JSON 格式的流程图数据）
     * @return 解析并校验后的流程图数据
     * @throws Exception 解析失败或校验不通过
     */
    public FlowchartData parse(String llmText) throws Exception {
        // 预处理：剥离围栏 + 截取纯净 JSON
        String json = extractJsonText(llmText);

        // 文本 → FlowchartData 对象
        FlowchartData data;
        try {
            data = objectMapper.readValue(json, FlowchartData.class);
        } catch (Exception e) {
            // 解析失败时带上原始文本片段，方便排查 LLM 到底返回了什么
            throw new Exception(
                    "JSON 解析失败：" + e.getMessage()
                            + " | 原始文本前200字：" + truncateForLog(llmText, 200), e);
        }

        // 数据合法性校验
        validate(data);

        return data;
    }

    /**
     * 任务37：从 LLM 输出中提取 Mermaid 代码文本
     *
     * 复用 extractJsonText 剥离 ``` 围栏 / 夹带文字，再用 Jackson 读 `mermaid` 字段。
     * mermaid 字段为空（模型没按要求输出）时抛 ValidationException，
     * 让 Controller 走 400 + 结构化错误列表的统一处理。
     *
     * @param llmText LLM 返回的纯文本（JSON 格式，含 mermaid 字段）
     * @return 干净的 Mermaid 代码文本
     * @throws ValidationException 提取失败或字段为空
     */
    public String extractMermaid(String llmText) throws ValidationException {
        String json = extractJsonText(llmText);
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode mermaid = node.get("mermaid");
            if (mermaid == null || mermaid.asText().isBlank()) {
                throw new ValidationException(List.of(
                        new ValidationIssue("mermaid", "模型未返回 mermaid 字段", "请重试，或换一种描述")
                ));
            }
            return mermaid.asText().trim();
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException(List.of(
                    new ValidationIssue("mermaid", "无法解析 Mermaid：" + e.getMessage(), "请重试")
            ));
        }
    }


    /**
     * LLM 返回的纯文本 → MindmapData 对象（思维导图）
     *
     * 为什么和 parse() 分开？因为两种数据结构完全不同：
     * - parse() 处理平铺结构（nodes + edges），校验严格（start/end/decision）
     * - parseMindmap() 处理嵌套结构（递归 children），校验很轻
     * 校验规则跟着数据结构走，两个方法并存、互不影响。
     *
     * @param llmText LLM 返回的纯文本（JSON 格式的思维导图数据）
     * @return 解析后的思维导图数据
     */
    public MindmapData parseMindmap(String llmText) throws Exception {
        String json = extractJsonText(llmText);
        MindmapData data;
        try {
            data = objectMapper.readValue(json, MindmapData.class);
        } catch (Exception e) {
            throw new Exception(
                    "思维导图 JSON 解析失败：" + e.getMessage()
                            + " | 原始文本前200字：" + truncateForLog(llmText, 200), e);
        }

        // 轻校验：树结构不可能有循环、不可能有悬空引用，只需要根节点有字
        if (data.getLabel() == null || data.getLabel().isBlank()) {
            throw new ValidationException(List.of(new ValidationIssue(
                    "label",
                    "思维导图缺少根节点 label",
                    "请补充 label 字段作为思维导图根节点，例如：\"产品规划\"")));
        }
        return data;
    }

    /**
     * LLM 返回的纯文本 → FlowchartData 对象（架构图）
     *
     * 架构图复用 FlowchartData 类（nodes + edges 表达组件依赖），
     * 但校验必须放宽——架构图没有 start/end/decision，
     * 不能走 parse() 的严格校验（那会要求恰好一个 start 一个 end）。
     *
     * @param llmText LLM 返回的纯文本（JSON 格式的架构图数据）
     * @return 解析后的架构图数据
     */
    public FlowchartData parseArchitecture(String llmText) throws Exception {
        String json = extractJsonText(llmText);
        FlowchartData data;
        try {
            data = objectMapper.readValue(json, FlowchartData.class);
        } catch (Exception e) {
            throw new Exception(
                    "架构图 JSON 解析失败：" + e.getMessage()
                            + " | 原始文本前200字：" + truncateForLog(llmText, 200), e);
        }

        // 轻校验：节点/边非空、边引用存在（架构图没有 start/end/decision 规则）
        List<ValidationIssue> issues = new ArrayList<>();

        if (data.getNodes() == null || data.getNodes().isEmpty()) {
            issues.add(new ValidationIssue(
                    "nodes",
                    "架构图组件列表为空",
                    "请至少提供 1 个组件节点，例如：[{\"id\":\"1\",\"label\":\"API网关\"}]"));
            finish(issues);  // 组件都没有，引用检查无意义
            return null;
        }
        if (data.getEdges() == null || data.getEdges().isEmpty()) {
            issues.add(new ValidationIssue(
                    "edges",
                    "架构图依赖列表为空",
                    "请至少提供 1 条依赖关系，例如：[{\"from\":\"1\",\"to\":\"2\",\"label\":\"HTTP\"}]"));
        }

        Set<String> nodeIds = data.getNodes().stream()
                .map(FlowchartData.Node::getId)
                .collect(Collectors.toSet());
        for (FlowchartData.Edge edge : data.getEdges()) {
            if (!nodeIds.contains(edge.getFrom())) {
                issues.add(new ValidationIssue(
                        "edges[from=" + edge.getFrom() + "]",
                        "依赖起点引用了不存在的组件 ID: " + edge.getFrom(),
                        "请检查 from 字段，确保它指向 nodes 中已定义的 id"));
            }
            if (!nodeIds.contains(edge.getTo())) {
                issues.add(new ValidationIssue(
                        "edges[to=" + edge.getTo() + "]",
                        "依赖终点引用了不存在的组件 ID: " + edge.getTo(),
                        "请检查 to 字段，确保它指向 nodes 中已定义的 id"));
            }
        }

        finish(issues);
        return data;

    }

    /**
     * 数据校验：确保 AI 返回的 JSON 符合我们的规则
     *
     * 旧版发现第一个问题就抛异常（"抛一个就停"），
     * 新版把【所有】问题收集到 List<ValidationIssue>，一次性返回——
     * 用户改一次就能全对，不用改完一个错又撞下一个。
     *
     * 校验项：
     * 1. 必须有 title
     * 2. 必须有且仅有一个 start 和一个 end
     * 3. decision 节点必须有恰好两条出边
     * 4. 所有连线的 from/to 都指向存在的节点
     *
     * 不再声明 throws Exception：校验失败抛的是 ValidationException（运行时异常），
     * 编译器不需要调用方强制处理，但 Controller 依然能 catch 到。
     */
    private void validate(FlowchartData data) {
        List<ValidationIssue> issues = new ArrayList<>();

        // 检查 1: title
        if (data.getTitle() == null || data.getTitle().isBlank()) {
            issues.add(new ValidationIssue(
                    "title",
                    "缺少流程图标题",
                    "请补充 title 字段，例如：\"用户登录流程\""));
        }

        // 检查 2: 节点列表
        if (data.getNodes() == null || data.getNodes().isEmpty()) {
            issues.add(new ValidationIssue(
                    "nodes",
                    "节点列表为空",
                    "请至少提供 1 个节点，且必须包含一个 start 节点和一个 end 节点"));
            // 节点都没有，后面的 start/end/边检查全是无意义的"0 个"，提前结束
            finish(issues);
            return;
        }

        // edges 可能为 null：记一条 issue，但用空列表兜底继续检查（避免 NPE）
        List<FlowchartData.Edge> edges = data.getEdges() == null ? List.of() : data.getEdges();
        if (data.getEdges() == null) {
            issues.add(new ValidationIssue(
                    "edges",
                    "连线列表为 null",
                    "请提供 edges 数组；没有连线时写 []"));
        }

        // 收集所有节点 ID，方便后续检查连线引用
        Set<String> nodeIds = data.getNodes().stream()
                .map(FlowchartData.Node::getId)
                .collect(Collectors.toSet());

        // 检查 3: 有且仅有一个 start
        long startCount = data.getNodes().stream()
                .filter(n -> "start".equals(n.getType())).count();
        if (startCount != 1) {
            issues.add(new ValidationIssue(
                    "nodes[type=start]",
                    "start 节点数量必须是 1，实际是 " + startCount,
                    startCount == 0
                            ? "请补充一个 start 节点表示流程起点"
                            : "请删除多余的 start 节点，只保留 1 个"));
        }

        // 检查 4: 有且仅有一个 end
        long endCount = data.getNodes().stream()
                .filter(n -> "end".equals(n.getType())).count();
        if (endCount != 1) {
            issues.add(new ValidationIssue(
                    "nodes[type=end]",
                    "end 节点数量必须是 1，实际是 " + endCount,
                    endCount == 0
                            ? "请补充一个 end 节点表示流程终点"
                            : "请删除多余的 end 节点，只保留 1 个"));
        }

        // 检查 5: 每个 decision 节点必须有恰好两条出边
        for (FlowchartData.Node node : data.getNodes()) {
            if ("decision".equals(node.getType())) {
                long outEdgeCount = edges.stream()
                        .filter(e -> node.getId().equals(e.getFrom())).count();
                if (outEdgeCount != 2) {
                    issues.add(new ValidationIssue(
                            "edges[from=" + node.getId() + "]",
                            "decision 节点 [" + node.getLabel() + "] 需要有 2 条出边，实际有 " + outEdgeCount,
                            "请为该 decision 节点补充/删除出边，使其恰好有 2 条（label 分别为\"是\"和\"否\"）"));
                }
            }
        }

        // 检查 6: 所有边引用的节点 ID 必须存在
        for (FlowchartData.Edge edge : edges) {
            if (!nodeIds.contains(edge.getFrom())) {
                issues.add(new ValidationIssue(
                        "edges[from=" + edge.getFrom() + "]",
                        "连线起点引用了不存在的节点 ID: " + edge.getFrom(),
                        "请检查 from 字段，确保它指向 nodes 中已定义的 id"));
            }
            if (!nodeIds.contains(edge.getTo())) {
                issues.add(new ValidationIssue(
                        "edges[to=" + edge.getTo() + "]",
                        "连线终点引用了不存在的节点 ID: " + edge.getTo(),
                        "请检查 to 字段，确保它指向 nodes 中已定义的 id"));
            }
        }

        // 收尾：有任何问题就一次性抛出去
        finish(issues);
    }

    /**
     * 校验收尾：有问题就抛 ValidationException，没问题就静默通过
     */
    private void finish(List<ValidationIssue> issues) {
        if (!issues.isEmpty()) {
            throw new ValidationException(issues);
        }
    }

}
