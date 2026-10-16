package io.github.nihaoljx.flowchart.graph.layout;

/**
 * spec 里的一条连线（起止节点 id + 可选文字 + 可选关系语义，**无几何**）
 *
 * <p>数组顺序即叙事顺序 —— 时序图排版器（{@code SequenceLayout}）靠它
 * 推消息的时间先后，见 {@link LayoutFamily}。
 */
public record SpecEdge(String from, String to,
                       String label, String relation) {
}
