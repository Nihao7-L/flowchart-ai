package io.github.nihaoljx.flowchart.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * agent 循环的消息账本（v2-55，learn-claude-code s01 的 messages[]）
 *
 * <p>与 legacy 管线的本质区别：一份消息列表从头追加到尾，模型天然
 * 看见自己上一轮干了什么（Manus"保留错误"、12-factor#9 统一执行态）。
 * 两条纪律在这里强制：
 * <ul>
 *   <li><b>append-only</b> —— 只提供 append，不提供改写/删除，
 *       保 KV-cache 前缀稳定（同一 run 内 system 字节级不变）；</li>
 *   <li><b>一批工具全部执行完再回喂</b> —— 不留孤立的 tool_result
 *       （s02 纪律，由 {@link io.github.nihaoljx.flowchart.agent.AgentLoop} 保证）。</li>
 * </ul>
 */
public final class AgentMessages {

    private final List<AgentMessage> items = new ArrayList<>();

    private AgentMessages() {
    }

    /**
     * 播种：system（稳定块）+ user（本次需求）。
     */
    public static AgentMessages seed(String systemBlock, String userRequest) {
        AgentMessages m = new AgentMessages();
        m.items.add(AgentMessage.system(systemBlock));
        m.items.add(AgentMessage.user(userRequest));
        return m;
    }

    /** 追加一条消息（append-only：只进不出） */
    public void append(AgentMessage message) {
        items.add(message);
    }

    /** 追加模型轮次（正文 + 本轮提议的工具调用） */
    public void appendAssistant(AgentTurn turn) {
        items.add(AgentMessage.assistant(turn.content(), turn.toolCalls()));
    }

    /** 追加工具结果（携带被应答的调用 id，协议要求成对出现） */
    public void appendToolResult(ToolCall call, Observation observation) {
        String content = observation.summary();
        items.add(AgentMessage.toolResult(call, content));
    }

    /** 追加一条 user 消息（修正指令 / 纯文本轮的回喂提示） */
    public void appendUser(String text) {
        items.add(AgentMessage.user(text));
    }

    /** 当前完整历史的不可变快照（发给 {@link AgentLlmClient}） */
    public List<AgentMessage> snapshot() {
        return List.copyOf(items);
    }

    /** 当前消息条数（记账/日志用） */
    public int size() {
        return items.size();
    }
}
