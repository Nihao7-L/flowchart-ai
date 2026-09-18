package io.github.nihaoljx.flowchart.graph;

/**
 * 校验问题（v2-9）
 *
 * <p>每个 issue 定位到具体字段，给出原因和修复提示，
 * 让 LLM 在下一轮修正 prompt 里知道该改哪里。
 */
public record ValidationIssue(
    String field,
    String reason,
    String hint
) {
}
