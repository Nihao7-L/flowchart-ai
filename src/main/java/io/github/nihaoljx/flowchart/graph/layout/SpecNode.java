package io.github.nihaoljx.flowchart.graph.layout;

/**
 * spec 里的一个节点（id + 文字 + 形状 + 可选模块名/配色，**无几何**）
 *
 * <p>从 SubmitSpecTool 的内部记录提升为公开记录：布局家族
 * （generic / sequence / 将来的 state / mindmap）都要读它，
 * 见 {@link LayoutFamily}。
 */
public record SpecNode(String id, String label, String type,
                       String group, String color) {
}
