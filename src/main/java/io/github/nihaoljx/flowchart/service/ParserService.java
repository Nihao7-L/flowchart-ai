package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.model.FlowchartData;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON 解析服务
 *
 * 职责：
 * 1. 从 Gemini 返回的原始 JSON 中提取文本内容
 * 2. 把文本转成 FlowchartData 对象
 * 3. 校验数据合法性（必须有 start/end、decision 必须有两条出边等）
 */
@Service
public class ParserService {

    /** Jackson 核心对象：负责 JSON ↔ Java 对象互转 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 完整流程：原始 Gemini 响应 → FlowchartData 对象
     *
     * @param rawResponse Gemini API 返回的原始 JSON 字符串
     * @return 解析并校验后的流程图数据
     * @throws Exception 解析失败或校验不通过
     */
    public FlowchartData parse(String rawResponse) throws Exception {
        // 步骤 1：从 Gemini 的嵌套 JSON 里抽出真正的文本
        String text = extractText(rawResponse);

        // 步骤 2：文本 → FlowchartData 对象
        FlowchartData data = objectMapper.readValue(text, FlowchartData.class);

        // 步骤 3：数据合法性校验
        validate(data);

        return data;
    }

    /**
     * 从 Gemini 响应中提取文本内容
     *
     * Gemini 返回格式（一大坨嵌套 JSON）：
     * {
     *   "candidates": [{
     *     "content": {
     *       "parts": [{ "text": "{"title":"...","nodes":[...]}" }]
     *     }
     *   }]
     * }
     *
     * 这个方法用 Jackson 正经解析，不再手动 indexOf 截字符串了
     */
    private String extractText(String rawResponse) throws Exception {
        // 先把原始 JSON 解析成一棵树（Map 结构），不用定义类
        Map<String, Object> root = objectMapper.readValue(rawResponse, Map.class);

        // 往下钻：candidates → [0] → content → parts → [0] → text
        java.util.List<Map<String, Object>> candidates =
                (java.util.List<Map<String, Object>>) root.get("candidates");

        if (candidates == null || candidates.isEmpty()) {
            throw new Exception("Gemini 返回中没有 candidates 字段");
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        java.util.List<Map<String, Object>> parts =
                (java.util.List<Map<String, Object>>) content.get("parts");

        if (parts == null || parts.isEmpty()) {
            throw new Exception("Gemini 返回中没有 parts 字段");
        }

        Object textObj = parts.get(0).get("text");
        if (textObj == null) {
            throw new Exception("Gemini 返回的 text 字段为空");
        }

        return textObj.toString();
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
