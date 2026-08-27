package io.github.nihaoljx.flowchart.model;

/**
 * 数据校验问题（任务32新增）
 *
 * LLM 输出的数据结构不合法时，用这个结构告诉前端"哪里错了、怎么改"。
 * 一条 issue = 一个具体问题，前端可以逐条渲染成用户能看懂的中文提示。
 *
 * @param field  哪个字段出问题了（如 "title" / "nodes[type=start]" / "edges[from=999]"）
 * @param reason 为什么错（现状描述）
 * @param hint   怎么改（给用户的修复建议）
 */
public record ValidationIssue(
        String field,
        String reason,
        String hint
) {}
