package io.github.nihaoljx.flowchart.graph.validate;

import java.util.List;

/**
 * 语义比对结果：issue 清单 + 召回硬指标（供闸门拦截与 D5 打分共用）。
 *
 * @param issues      缺失节点/连线的点名化问题（可回喂修正轮）
 * @param foundNodes  命中的必含节点数
 * @param totalNodes  要求的必含节点总数
 * @param foundEdges  命中的必含连线数
 * @param totalEdges  要求的必含连线总数
 */
public record SemanticReport(List<ValidationIssue> issues,
                             int foundNodes, int totalNodes,
                             int foundEdges, int totalEdges) {

    /** 节点召回（0..1）；无要求时定义为 1（不惩罚） */
    public double nodeRecall() {
        return totalNodes == 0 ? 1.0 : (double) foundNodes / totalNodes;
    }

    /** 连线召回（0..1）；无要求时定义为 1（不惩罚） */
    public double edgeRecall() {
        return totalEdges == 0 ? 1.0 : (double) foundEdges / totalEdges;
    }

    public static SemanticReport empty(ReferenceSpec ref) {
        int nodes = ref == null ? 0 : ref.requiredNodes().size();
        int edges = ref == null ? 0 : ref.requiredEdges().size();
        return new SemanticReport(List.of(), 0, nodes, 0, edges);
    }
}
