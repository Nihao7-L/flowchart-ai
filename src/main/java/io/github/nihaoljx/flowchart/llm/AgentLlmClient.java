package io.github.nihaoljx.flowchart.llm;

import java.util.List;
import java.util.Map;

/**
 * agent 循环对 LLM 的抽象
 *
 * 与普通聊天接口的区别：agent 循环需要 tool_calls 结构化返回
 * （模型提议调哪个工具、参数是什么），纯文本聊天接口表达不了。
 * 唯一实现是 FailoverAgentClient（OpenAI 兼容 tool_calls 协议 +
 * 候选链故障转移）；测试可换桩实现，不让单测碰网络。
 */
public interface AgentLlmClient {

    /**
     * 发一轮对话，取回模型的正文与工具调用提议。
     *
     * @param messages       完整消息历史（append-only，见 AgentMessages）
     * @param tools          工具定义清单（schemas() 的产物）
     * @param timeoutSeconds 本轮调用的整次硬上限（秒）；由循环按剩余预算折算
     * @return 模型的一轮响应
     * @throws Exception 网络错误、API 错误、解析失败等（由循环上抛，交调用方处置）
     */
    AgentTurn respond(List<AgentMessage> messages,
                      List<Map<String, Object>> tools,
                      int timeoutSeconds) throws Exception;

    /**
     * 带 tool_choice 的一轮对话（2026-10-16 补）。
     *
     * <p>"required" = 强制本轮必须调工具（OpenAI 协议原生）：模型
     * 连续纯文本空转（只说话不干活）时，光回喂 nudge 有些模型下次
     * 还是说话 —— 物理上不给它说话的机会，省步数。
     *
     * <p>默认实现转发三参版本（不支持的厂商自动退回 auto 语义），
     * 具体实现在协议支持时覆写。
     */
    default AgentTurn respond(List<AgentMessage> messages,
                              List<Map<String, Object>> tools,
                              int timeoutSeconds,
                              String toolChoice) throws Exception {
        return respond(messages, tools, timeoutSeconds);
    }
}
