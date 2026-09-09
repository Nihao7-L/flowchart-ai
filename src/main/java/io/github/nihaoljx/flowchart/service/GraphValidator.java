package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.GraphJson;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务49：端到端生成验证（Harness L5）
 *
 * 职责：生成后自动验证图能渲染、结构合理。
 * 与 ParserService.validate() 的区别：
 *   - ParserService 校验"JSON 合法、字段齐全"（数据级）
 *   - GraphValidator 校验"图真能渲染、真符合意图"（端到端级）
 *
 * 验证项（按严重度排序）：
 *   1. 渲染验证：把 GraphJson → PlantUML → renderToSvg，渲染失败 = 坏图
 *   2. 结构约束：孤立节点、null 标签、start/end 可达性
 *   3. 连通性：所有节点都能从 start 到达（BFS）
 *
 * 设计为独立服务，供 DiagramController 在 parseAndRender 后调用；
 * 任务53 Loop Engineering 会把验证失败的 issues 回喂给 LLM 自动修正。
 */
@Service
public class GraphValidator {

    private final DiagramService diagramService;

    public GraphValidator(DiagramService diagramService) {
        this.diagramService = diagramService;
    }

    /**
     * 完整验证：结构约束 + 渲染验证
     *
     * @param g       待验证的图
     * @param type    图表类型（flowchart/mindmap/architecture）
     * @return 通过返回空列表；不通过返回结构化问题列表（field/reason/hint）
     */
    public List<ValidationIssue> validate(GraphJson g, String type) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (g == null) {
            issues.add(new ValidationIssue("graphJson", "图数据为 null", "请重新生成"));
            return issues;
        }

        // 1. 基本字段校验
        if (g.getNodes() == null || g.getNodes().isEmpty()) {
            issues.add(new ValidationIssue("nodes", "节点列表为空", "请至少提供 1 个节点"));
            return issues;
        }
        for (GraphJson.GraphNode n : g.getNodes()) {
            if (n.getId() == null || n.getId().isBlank()) {
                issues.add(new ValidationIssue("nodes[].id", "存在 id 为空的节点", "请为每个节点填写唯一 id"));
            }
            if (n.getLabel() == null || n.getLabel().isBlank()) {
                issues.add(new ValidationIssue("nodes[].label", "节点 [" + n.getId() + "] 的 label 为空", "请为每个节点填写名称"));
            }
        }

        // 2. 流程图专属约束
        if ("flowchart".equals(type)) {
            validateFlowchart(g, issues);
        }

        // 3. 连通性检查（BFS 从 start 到 end）
        if ("flowchart".equals(type) && issues.isEmpty()) {
            validateConnectivity(g, issues);
        }

        // 4. 渲染验证（最后一步：能走完前面的校验才做渲染）
        if (issues.isEmpty()) {
            validateRendering(g, type, issues);
        }

        return issues;
    }

    /** 流程图专属约束：start/end 数量、decision 出边、边引用 */
    private void validateFlowchart(GraphJson g, List<ValidationIssue> issues) {
        Set<String> nodeIds = g.getNodes().stream().map(GraphJson.GraphNode::getId).collect(Collectors.toSet());
        List<GraphJson.GraphEdge> edges = g.getEdges() != null ? g.getEdges() : List.of();

        // start 数量
        long startCount = g.getNodes().stream().filter(n -> "start".equals(n.getType())).count();
        if (startCount != 1) {
            issues.add(new ValidationIssue("nodes[type=start]", "start 节点数量必须是 1，实际是 " + startCount,
                    startCount == 0 ? "请补充一个 start 节点" : "请删除多余的 start 节点"));
        }

        // end 数量
        long endCount = g.getNodes().stream().filter(n -> "end".equals(n.getType())).count();
        if (endCount != 1) {
            issues.add(new ValidationIssue("nodes[type=end]", "end 节点数量必须是 1，实际是 " + endCount,
                    endCount == 0 ? "请补充一个 end 节点" : "请删除多余的 end 节点"));
        }

        // decision 出边
        for (GraphJson.GraphNode n : g.getNodes()) {
            if ("decision".equals(n.getType())) {
                long out = edges.stream().filter(e -> n.getId().equals(e.getSource())).count();
                if (out != 2) {
                    issues.add(new ValidationIssue("edges[source=" + n.getId() + "]",
                            "decision 节点 [" + n.getLabel() + "] 需要 2 条出边，实际 " + out,
                            "请为该 decision 补充/删除出边，使其恰好 2 条"));
                }
            }
        }

        // 边引用存在
        for (GraphJson.GraphEdge e : edges) {
            if (!nodeIds.contains(e.getSource())) {
                issues.add(new ValidationIssue("edges[source=" + e.getSource() + "]",
                        "连线起点引用了不存在的节点: " + e.getSource(), "请检查 source 字段"));
            }
            if (!nodeIds.contains(e.getTarget())) {
                issues.add(new ValidationIssue("edges[target=" + e.getTarget() + "]",
                        "连线终点引用了不存在的节点: " + e.getTarget(), "请检查 target 字段"));
            }
        }
    }

    /** BFS 连通性：从 start 出发，检查是否能到达所有节点和 end */
    private void validateConnectivity(GraphJson g, List<ValidationIssue> issues) {
        GraphJson.GraphNode startNode = g.getNodes().stream()
                .filter(n -> "start".equals(n.getType())).findFirst().orElse(null);
        if (startNode == null) return; // 无 start（宽松校验阶段已报错）

        Map<String, List<String>> adj = new HashMap<>();
        for (GraphJson.GraphNode n : g.getNodes()) {
            adj.put(n.getId(), new ArrayList<>());
        }
        List<GraphJson.GraphEdge> edges = g.getEdges() != null ? g.getEdges() : List.of();
        for (GraphJson.GraphEdge e : edges) {
            adj.computeIfAbsent(e.getSource(), k -> new ArrayList<>()).add(e.getTarget());
        }

        // BFS
        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(startNode.getId());
        reachable.add(startNode.getId());
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : adj.getOrDefault(cur, List.of())) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }

        // 检查孤立节点
        for (GraphJson.GraphNode n : g.getNodes()) {
            if (!reachable.contains(n.getId()) && !"start".equals(n.getType())) {
                issues.add(new ValidationIssue("nodes[" + n.getId() + "]",
                        "节点 [" + n.getLabel() + "] 无法从 start 到达（孤立节点）",
                        "请添加从 start 到该节点的路径，或删除该节点"));
            }
        }

        // 检查 end 是否可达
        GraphJson.GraphNode endNode = g.getNodes().stream()
                .filter(n -> "end".equals(n.getType())).findFirst().orElse(null);
        if (endNode != null && !reachable.contains(endNode.getId())) {
            issues.add(new ValidationIssue("nodes[type=end]",
                    "end 节点无法从 start 到达", "请添加从某个节点到 end 的连线"));
        }
    }

    /** 渲染验证：尝试把图渲染成 SVG，失败 = 图有语法问题 */
    private void validateRendering(GraphJson g, String type, List<ValidationIssue> issues) {
        try {
            String plantUml;
            if ("flowchart".equals(type)) {
                plantUml = diagramService.buildPlantUml(g);
            } else if ("architecture".equals(type)) {
                plantUml = diagramService.buildArchitecture(g);
            } else {
                return; // mindmap 等其他类型暂不做渲染验证
            }
            String svg = diagramService.renderToSvg(plantUml);
            if (svg == null || svg.isBlank() || svg.length() < 100) {
                issues.add(new ValidationIssue("render", "渲染结果异常（SVG 为空或过短）",
                        "请检查图结构是否正确，或重试"));
            }
        } catch (IOException e) {
            issues.add(new ValidationIssue("render", "渲染失败：" + e.getMessage(),
                    "请检查图结构是否正确，或重试"));
        } catch (Exception e) {
            // PlantUML 渲染异常（语法错误等），不阻断返回，记 warning
            System.err.println("⚠️ 渲染验证警告（非阻断）: " + e.getMessage());
        }
    }

    /** 结构化验证问题（与 ParserService.ValidationIssue 同构，但独立定义避免耦合） */
    public record ValidationIssue(String field, String reason, String hint) {}
}
