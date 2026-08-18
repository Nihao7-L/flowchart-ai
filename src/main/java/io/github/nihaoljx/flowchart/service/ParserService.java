package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.model.FlowchartData;
import io.github.nihaoljx.flowchart.model.MindmapData;
import org.springframework.stereotype.Service;

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

    /** Jackson 核心对象：负责 JSON ↔ Java 对象互转 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * LLM 返回的纯文本 → FlowchartData 对象
     *
     * @param llmText LLM 返回的纯文本（JSON 格式的流程图数据）
     * @return 解析并校验后的流程图数据
     * @throws Exception 解析失败或校验不通过
     */
    public FlowchartData parse(String llmText) throws Exception {
        // 文本 → FlowchartData 对象
        FlowchartData data = objectMapper.readValue(llmText, FlowchartData.class);

        // 数据合法性校验
        validate(data);

        return data;
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
        MindmapData data = objectMapper.readValue(llmText, MindmapData.class);

        // 轻校验：树结构不可能有循环、不可能有悬空引用，只需要根节点有字
        if (data.getLabel() == null || data.getLabel().isBlank()) {
            throw new Exception("校验失败：思维导图缺少根节点 label");
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
        FlowchartData data = objectMapper.readValue(llmText, FlowchartData.class);

        // 轻校验：节点和边必须非空，边的引用必须存在
        if (data.getNodes() == null || data.getNodes().isEmpty()) {
            throw new Exception("校验失败：架构图组件列表为空");
        }
        if (data.getEdges() == null || data.getEdges().isEmpty()) {
            throw new Exception("校验失败：架构图依赖列表为空");
        }

        Set<String> nodeIds = data.getNodes().stream()
                .map(FlowchartData.Node::getId)
                .collect(Collectors.toSet());
        for (FlowchartData.Edge edge : data.getEdges()) {
            if (!nodeIds.contains(edge.getFrom())) {
                throw new Exception("校验失败：边引用了不存在的组件 ID: " + edge.getFrom());
            }
            if (!nodeIds.contains(edge.getTo())) {
                throw new Exception("校验失败：边引用了不存在的组件 ID: " + edge.getTo());
            }
        }
        return data;
    }

    /**
     * 数据校验：确保 AI 返回的 JSON 符合我们的规则
     *
     * 校验项：
     * 1. 必须有 title
     * 2. 必须有且仅有一个 start 和一个 end
     * 3. decision 节点必须有恰好两条出边
     * 4. 所有连线的 from/to 都指向存在的节点
     */
    private void validate(FlowchartData data) throws Exception {
        if (data.getTitle() == null || data.getTitle().isBlank()) {
            throw new Exception("校验失败：缺少 title");
        }
        if (data.getNodes() == null || data.getNodes().isEmpty()) {
            throw new Exception("校验失败：节点列表为空");
        }
        if (data.getEdges() == null) {
            throw new Exception("校验失败：连线列表为 null");
        }

        // 收集所有节点 ID，方便后续检查连线引用
        Set<String> nodeIds = data.getNodes().stream()
                .map(FlowchartData.Node::getId)
                .collect(Collectors.toSet());

        // 检查 1: 有且仅有一个 start
        long startCount = data.getNodes().stream()
                .filter(n -> "start".equals(n.getType())).count();
        if (startCount != 1) {
            throw new Exception("校验失败：start 节点数量必须是 1，实际是 " + startCount);
        }

        // 检查 2: 有且仅有一个 end
        long endCount = data.getNodes().stream()
                .filter(n -> "end".equals(n.getType())).count();
        if (endCount != 1) {
            throw new Exception("校验失败：end 节点数量必须是 1，实际是 " + endCount);
        }

        // 检查 3: 每个 decision 节点必须有恰好两条出边
        for (FlowchartData.Node node : data.getNodes()) {
            if ("decision".equals(node.getType())) {
                long outEdgeCount = data.getEdges().stream()
                        .filter(e -> node.getId().equals(e.getFrom())).count();
                if (outEdgeCount != 2) {
                    throw new Exception(
                            "校验失败：decision 节点 [" + node.getLabel()
                                    + "] 需要有 2 条出边，实际有 " + outEdgeCount);
                }
            }
        }

        // 检查 4: 所有边引用的节点 ID 必须存在
        for (FlowchartData.Edge edge : data.getEdges()) {
            if (!nodeIds.contains(edge.getFrom())) {
                throw new Exception("校验失败：边引用了不存在的节点 ID: " + edge.getFrom());
            }
            if (!nodeIds.contains(edge.getTo())) {
                throw new Exception("校验失败：边引用了不存在的节点 ID: " + edge.getTo());
            }
        }
    }
}
