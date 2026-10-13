package io.github.nihaoljx.flowchart.agent;

import java.util.List;
import java.util.Map;

/**
 * agent 循环对 LLM 的抽象（v2-55）
 *
 * <p>为什么不是 {@code LlmProvider}：现有网关只回纯文本，而 agent 循环
 * 需要 tool_calls 结构化返回。这层抽象让 {@link AgentLoop} 可以先拿
 * 桩实现做单测（step 1），真实实现（OpenAI 兼容 tool_calls 协议 +
 * 故障转移复用）在 step 3 接线时落到 {@code client} 包。
 *
 * <p>实现纪律（step 3 时生效）：超时 / 空内容 / 截断的处理必须原样
 * 复用 {@code FailoverLlmProvider} 的既有口径（70e501b 之后），不另起炉灶。
 */
public interface AgentLlmClient {

    /**
     * 发一轮对话，取回模型的正文与工具调用提议。
     *
     * @param messages       完整消息历史（append-only，见 {@link AgentMessages}）
     * @param tools          工具定义清单（{@link ToolRegistry#schemas()} 的产物）
     * @param timeoutSeconds 本轮调用的整次硬上限（秒）；由循环按剩余预算折算
     * @return 模型的一轮响应
     * @throws Exception 网络错误、API 错误、解析失败等（由循环上抛，交调用方处置）
     */
    AgentTurn respond(List<AgentMessage> messages,
                      List<Map<String, Object>> tools,
                      int timeoutSeconds) throws Exception;
}
