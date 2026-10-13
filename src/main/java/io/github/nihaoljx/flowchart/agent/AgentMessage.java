package io.github.nihaoljx.flowchart.agent;

import java.util.List;

/**
 * agent 协议的消息（v2-55）
 *
 * <p>为什么不复用 {@code client.Message}：网关的 Message 是"纯文本消息"
 * （system / user / assistant / tool 四角色、只有 content）。而 agent 循环
 * 按厂商协议还需要三个字段 —— assistant 消息上的 {@code tool_calls}、
 * tool 消息上的 {@code tool_call_id} 与工具名。这些字段 step 3 接
 * OpenAI 兼容请求体时才真正序列化，现在先在类型上放好位置，
 * 避免 step 3 再回头改 {@code client.Message} 的构造口径。
 *
 * <p>与 {@code client.Message} 的关系：本类型是 agent 循环的内部账本，
 * {@code AgentLlmClient} 的实现负责把它翻译成厂商请求体；
 * system / user 角色的语义与网关 Message 一致。
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

    /** 工具结果：必须携带被应答的那个 {@link ToolCall} 的 id 与名字 */
    public static AgentMessage toolResult(ToolCall call, String content) {
        return new AgentMessage("tool", content, null, call.id(), call.name());
    }
}
