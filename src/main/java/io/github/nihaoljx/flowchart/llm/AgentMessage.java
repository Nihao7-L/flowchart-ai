package io.github.nihaoljx.flowchart.llm;

import java.util.List;

/**
 * agent 协议的消息
 *
 * 比普通聊天消息多三个字段：assistant 消息上的 `tool_calls`、
 * tool 消息上的 `tool_call_id` 与工具名 —— 厂商 tool_calls 协议
 * 回喂观察结果时要靠它们对账。
 *
 * 本类型是 agent 循环的内部账本；`AgentLlmClient` 的实现
 * （FailoverAgentClient）负责把它翻译成厂商请求体。
 */
public record AgentMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName) {

    /** assistant 消息允许的角色白名单（toolCalls / toolCallId 按角色选用） */
    private static final List<String> VALID_ROLES =
            List.of("system", "user", "assistant", "tool");

    /**
     * 紧凑构造器：toolCalls 归一成不可变列表；role 白名单入口即卡。
     */
    public AgentMessage {
        if (role == null || !VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException(
                    "非法的 agent 消息角色: " + role);
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        content = content == null ? "" : content;
    }

    /** 固定指令区（system 块，整轮循环字节级不变 —— KV-cache 纪律） */
    public static AgentMessage system(String content) {
        return new AgentMessage("system", content, null, null, null);
    }

    /** 每轮变化区（需求 / 修正指令 / 兜底提示） */
    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, null, null);
    }

    /** assistant 轮次：正文（可空）+ 本轮提议的工具调用（可空） */
    public static AgentMessage assistant(String content, List<ToolCall> toolCalls) {
        return new AgentMessage("assistant", content, toolCalls, null, null);
    }

    /** 工具结果：必须携带被应答的那个 ToolCall 的 id 与名字 */
    public static AgentMessage toolResult(ToolCall call, String content) {
        return new AgentMessage("tool", content, null, call.id(), call.name());
    }
}
