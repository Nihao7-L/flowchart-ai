package io.github.nihaoljx.flowchart.model;

import java.util.List;

/**
 * AI 返回的流程图结构化数据
 *
 * 这个类的字段名必须和 prompt 模板里要求的 JSON 字段名一致，
 * 因为 Jackson 是按字段名自动匹配的。
 *
 * 对应 JSON 格式：
 * {
 *   "title": "流程图标题",
 *   "nodes": [ { "id": "1", "type": "start", "label": "开始" } ],
 *   "edges": [ { "from": "1", "to": "2", "label": "是" } ]
 * }
 */
public class FlowchartData {

    private String title;                     // 流程图标题
    private List<Node> nodes;                 // 节点列表
    private List<Edge> edges;                 // 连线列表

    // ===== Getter / Setter：Jackson 必需，用于 JSON ↔ Java 对象互转 =====
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<Node> getNodes() { return nodes; }
    public void setNodes(List<Node> nodes) { this.nodes = nodes; }

    public List<Edge> getEdges() { return edges; }
    public void setEdges(List<Edge> edges) { this.edges = edges; }

    // ==================== 内部类：节点 ====================
    public static class Node {
        private String id;     // 唯一标识，如 "1", "2", "3"
        private String type;   // start | process | decision | end
        private String label;  // 显示文字，如 "用户输入"

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    // ==================== 内部类：连线 ====================
    public static class Edge {
        private String from;   // 起始节点 ID
        private String to;     // 目标节点 ID
        private String label;  // 连线标签，如 "是"、"否"，可以为空

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }
}
