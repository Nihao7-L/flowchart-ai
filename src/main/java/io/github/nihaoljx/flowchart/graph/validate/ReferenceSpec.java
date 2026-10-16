package io.github.nihaoljx.flowchart.graph.validate;

import java.util.List;

/**
 * 参考结构（期望图的最小机器可读描述）。
 *
 * <p>按**标签文本**匹配而不是 id —— 参考结构由人/需求方书写，
 * 不可能预知模型会给节点起什么 id；标签是双方共同的语义锚点。
 *
 * @param requiredNodes 必须出现的节点标签（如「开始」「支付网关」）
 * @param requiredEdges 必须出现的连线（按「起点标签→终点标签」的有向对）
 */
public record ReferenceSpec(List<String> requiredNodes,
                            List<RequiredEdge> requiredEdges) {

    /** 一条必须存在的有向连线（按标签对描述） */
    public record RequiredEdge(String fromLabel, String toLabel) {
    }

    public static ReferenceSpec of(List<String> nodes, List<RequiredEdge> edges) {
        return new ReferenceSpec(
                nodes == null ? List.of() : List.copyOf(nodes),
                edges == null ? List.of() : List.copyOf(edges));
    }
}
