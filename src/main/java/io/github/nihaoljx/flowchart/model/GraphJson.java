package io.github.nihaoljx.flowchart.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Graph JSON —— 全局唯一的图数据模型（任务43 方案乙：整体换血）
 *
 * 一处定义，三处复用：
 * 1. LLM 输出契约：prompt + Schema 约束的就是这个格式，LLM 直出、无中间层
 * 2. 内部权威表示：PlantUML / Mermaid / SVG 全部从它单向导出
 * 3. 前端契约：字段名 source/target/position 直接对齐 React Flow（任务44的画布库），
 *    前端拿到可以零映射直接用
 *
 * 为什么边用 source/target 而不是原来的 from/to？
 * React Flow 的原生字段名就是 source/target，用它可以省掉前后端一层字段映射。
 *
 * 为什么边要有 id？
 * 画布上"选中某条线、删除某条线"需要有东西可寻址，React Flow 要求每条边有唯一 id。
 * 但 id 不让 LLM 生成（省 token、避免重复/错乱），由后端 GraphJsonService.assignEdgeIds 统一补。
 *
 * @JsonInclude(NON_NULL)：序列化时 null 字段直接省略——
 * position 没给就不输出 position 键，边没 label 就不输出 label 键，契约更干净。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphJson {

    private String title;                 // 图标题（思维导图取根节点 label）
    private List<GraphNode> nodes;        // 节点列表（平铺结构）
    private List<GraphEdge> edges;        // 连线列表（平铺结构）

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<GraphNode> getNodes() { return nodes; }
    public void setNodes(List<GraphNode> nodes) { this.nodes = nodes; }

    public List<GraphEdge> getEdges() { return edges; }
    public void setEdges(List<GraphEdge> edges) { this.edges = edges; }

    // ==================== 内部类：节点 ====================
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GraphNode {
        private String id;          // 唯一标识
        private String type;        // start | process | decision | end | component | mindmap
        private String label;       // 显示文字
        private Position position;  // 可选坐标；null 时序列化省略，前端自动布局

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public Position getPosition() { return position; }
        public void setPosition(Position position) { this.position = position; }
    }

    // ==================== 内部类：连线 ====================
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class GraphEdge {
        private String id;       // 唯一标识，后端统一生成（"e1","e2"...），React Flow 必需
        private String source;   // 起始节点 ID（原 FlowchartData.Edge.from）
        private String target;   // 目标节点 ID（原 FlowchartData.Edge.to）
        private String label;    // 连线标签，如 "是"/"否"；为空时序列化省略

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }
}
