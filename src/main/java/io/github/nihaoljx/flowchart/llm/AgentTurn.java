package io.github.nihaoljx.flowchart.llm;

import java.util.List;

/**
 * 一次模型响应
 *
 * 模型在一轮里可以只说话（无工具调用）、只调工具、或两者都做。
 * 循环对三种形态都接：纯文本轮回喂"请用工具"，工具轮全部执行完再回喂。
 *
 * @param content   模型的正文（思维链 / 说明文字），可为空
 * @param toolCalls 本轮提议的全部工具调用（一批，顺序执行）
 */
public record AgentTurn(String content, List<ToolCall> toolCalls) {

    public AgentTurn {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        content = content == null ? "" : content;
    }
}
