package io.github.nihaoljx.flowchart.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次连续对话的会话（任务40：会话记忆与多轮对话）
 *
 * 保留：
 * - 对话历史 messages（用户指令 + AI 产出图）
 * - 当前图 currentGraphJson（最新基线，refine 的输入）
 *
 * 持久化到 Redis 时整段序列化成 JSON，因此需要：
 * - 无参构造 + setter（Jackson 反序列化用）
 * - 字段不加 final（Jackson 无参构造后逐个 set）
 * 结构变更要保持向前兼容：新增字段用包装类型/有默认值，老 JSON 少字段也能读回来。
 */
public class Session {

    private String sessionId;
    private List<ChatMessage> messages = new ArrayList<>();
    private GraphJson currentGraphJson;
    /** 最后一次活跃时间戳，用于观察会话冷热（Redis 侧过期由 TTL 负责） */
    private long lastActive;

    /** Jackson 反序列化用 */
    public Session() {
    }

    public Session(String sessionId) {
        this.sessionId = sessionId;
        this.lastActive = System.currentTimeMillis();
    }

    public void addMessage(ChatMessage msg) {
        if (messages == null) messages = new ArrayList<>();
        messages.add(msg);
        this.lastActive = System.currentTimeMillis();
    }

    public void setCurrentGraphJson(GraphJson g) {
        this.currentGraphJson = g;
        this.lastActive = System.currentTimeMillis();
    }

    /** 只保留最近 max 条消息（滑动窗口）：防止长会话无限膨胀撑爆 Redis 与 prompt */
    public void trimMessages(int max) {
        if (messages == null || max <= 0 || messages.size() <= max) return;
        messages = new ArrayList<>(messages.subList(messages.size() - max, messages.size()));
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }

    public GraphJson getCurrentGraphJson() { return currentGraphJson; }

    public long getLastActive() { return lastActive; }
    public void setLastActive(long lastActive) { this.lastActive = lastActive; }
}
