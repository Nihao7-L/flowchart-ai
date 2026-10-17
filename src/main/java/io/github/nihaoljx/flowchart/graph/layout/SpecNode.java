package io.github.nihaoljx.flowchart.graph.layout;

import java.util.List;

/**
 * spec 里的一个节点（id + 文字 + 形状 + 可选模块路径/配色，**无几何**）
 *
 * <p>从 SubmitSpecTool 的内部记录提升为公开记录：布局家族
 * （generic / sequence / 将来的 state / mindmap）都要读它，
 * 见 {@link LayoutFamily}。
 *
 * <p>{@code group} 是**从内到外**的模块路径（见 {@link GroupPath}）：
 * 单层模块是单元素表，空表表示没分组。它不再是 String —— 用 String
 * 存路径时「数组 group 被 asText() 读成空串」这个坑会原样复发。
 */
public record SpecNode(String id, String label, String type,
                       List<String> group, String color) {
}
