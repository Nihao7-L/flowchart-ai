package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.GraphJson;
import io.github.nihaoljx.flowchart.model.MindmapData;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Graph JSON 转换中心（任务43）
 *
 * 三个职责：
 * 1. fromMindmap：思维导图的嵌套树 → 平铺 GraphJson（树转图）
 * 2. assignEdgeIds：给 LLM 直出的图补边 id（LLM 不生成 id，后端统一编 e1/e2...）
 * 3. toMermaid：GraphJson → Mermaid 文本（后端直接转换，不依赖 LLM）
 *
 * 为什么没有 fromFlowchart / toFlowchartData 了？
 * 方案乙已删掉 FlowchartData，LLM 直接输出 GraphJson，
 * "两种格式互转"这层需求本身消失了。
 */
@Service
public class GraphJsonService {

    /**
     * 思维导图（递归树）→ GraphJson（平铺 nodes/edges）
     *
     * 为什么思维导图不也让 LLM 直接输出平铺格式？
     * 嵌套 JSON 里"父子关系"由缩进天然表达，LLM 几乎不会写错；
     * 平铺格式要靠 id 互相引用，是 LLM 的高频出错点（引用不存在的 id、漏连一条边），
     * 而且更费 token。所以：树用树表示，展平交给后端。
     */
    public GraphJson fromMindmap(MindmapData root) {
        Flattener f = new Flattener();
        f.out.setTitle(root.getLabel());
        flatten(f, root, null);   // 根节点没有父节点
        return f.out;
    }

    /** 展平过程的中间状态：产物 + 两个自增计数器（单线程，无需并发保护） */
    private static class Flattener {
        final GraphJson out = new GraphJson();
        final List<GraphJson.GraphNode> nodes = new ArrayList<>();
        final List<GraphJson.GraphEdge> edges = new ArrayList<>();
        int nodeSeq = 0;
        int edgeSeq = 0;
    }

    /** 递归展平：先登记当前节点，再补一条"父 → 当前"的边，最后递归子节点 */
    private void flatten(Flattener f, MindmapData node, String parentId) {
        String id = "n" + (++f.nodeSeq);

        GraphJson.GraphNode gn = new GraphJson.GraphNode();
        gn.setId(id);
        gn.setType("mindmap");
        gn.setLabel(node.getLabel());
        f.nodes.add(gn);

        if (parentId != null) {
            GraphJson.GraphEdge ge = new GraphJson.GraphEdge();
            ge.setId("e" + (++f.edgeSeq));
            ge.setSource(parentId);
            ge.setTarget(id);
            f.edges.add(ge);   // 思维导图的边没有标签
        }

        List<MindmapData> children = node.getChildren();
        if (children != null) {
            for (MindmapData child : children) {
                flatten(f, child, id);
            }
        }

        // 根节点递归结束时一次性装配（避免每层都判空）
        if (parentId == null) {
            f.out.setNodes(f.nodes);
            f.out.setEdges(f.edges);
        }
    }

    /**
     * 给 LLM 直出的图补边 id（只补空的，不覆盖已有的）
     *
     * 为什么是 static？
     * ParserService 的单测是 new ParserService() 直接实例化，不走 Spring 注入，
     * 若用 @Autowired 注入本类会拿到 null → 空指针。静态方法零依赖，单测和生产都能用。
     *
     * 为什么不让 LLM 生成 edge id？
     * id 对渲染毫无用处（PlantUML 不认），纯粹是画布寻址用；
     * 让 LLM 生成只会多耗 token，还可能编出重复或断号的 id。
     */
    public static void assignEdgeIds(GraphJson graph) {
        if (graph == null || graph.getEdges() == null) return;
        int seq = 0;
        for (GraphJson.GraphEdge e : graph.getEdges()) {
            if (e.getId() == null || e.getId().isBlank()) {
                e.setId("e" + (++seq));
            }
        }
    }

    /**
     * GraphJson → Mermaid flowchart 文本
     *
     * 节点形状映射：start/end → (["x"]) 圆角；decision → {"x"} 菱形；其它 → ["x"] 矩形
     * 方向：思维导图（树形）用 LR（更像脑图），其余用 TD
     * 注意：mermaid 语法没有"标题"概念，title 只保留在 graphJson 里
     */
    public String toMermaid(GraphJson graph) {
        boolean isMindmap = graph.getNodes() != null && graph.getNodes().stream()
                .anyMatch(n -> "mindmap".equals(n.getType()));

        StringBuilder sb = new StringBuilder("flowchart ")
                .append(isMindmap ? "LR" : "TD").append("\n");

        if (graph.getNodes() != null) {
            for (GraphJson.GraphNode n : graph.getNodes()) {
                sb.append("    ").append(n.getId()).append(nodeShape(n)).append("\n");
            }
        }
        if (graph.getEdges() != null) {
            for (GraphJson.GraphEdge e : graph.getEdges()) {
                sb.append("    ").append(e.getSource()).append(" -->");
                if (e.getLabel() != null && !e.getLabel().isBlank()) {
                    sb.append("|\"").append(escape(e.getLabel())).append("\"|");
                }
                sb.append(e.getTarget()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 按节点类型生成 mermaid 形状语法；label 用双引号包裹（防括号/空格炸语法） */
    private String nodeShape(GraphJson.GraphNode n) {
        String label = "\"" + escape(n.getLabel()) + "\"";
        String type = n.getType() == null ? "" : n.getType();
        return switch (type) {
            case "start", "end" -> "([" + label + "])";
            case "decision"     -> "{" + label + "}";
            default             -> "[" + label + "]";
        };
    }

    /** mermaid 转义：双引号换成 #quot; 实体，换行压成空格 */
    private String escape(String text) {
        if (text == null) return "";
        return text.replace("\"", "#quot;").replaceAll("\\s*\\R\\s*", " ");
    }
}
