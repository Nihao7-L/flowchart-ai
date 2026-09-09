package io.github.nihaoljx.flowchart.model;

/**
 * 会话里的一条消息（任务40：会话记忆与多轮对话）
 *
 * role:    user（用户说的指令）/ assistant（AI 生成的图）
 * content: 文本内容（用户指令 / 或 AI 这轮的简要说明）
 * graphJson: 仅 assistant 轮携带，这一轮 AI 产出的图（后续 refine 的基线）
 */
public record ChatMessage(
        String role,
        String content,
        GraphJson graphJson
) {
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null);
    }

    public static ChatMessage assistant(String content, GraphJson graphJson) {
        return new ChatMessage("assistant", content, graphJson);
    }
}
