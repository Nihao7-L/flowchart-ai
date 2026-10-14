package io.github.nihaoljx.flowchart.llm;

import java.util.Set;

/**
 * LLM 网关消息：角色 + 内容
 *
 * 为什么独立成类型：LlmProvider 要支持 system / user 双消息，
 * 就得先有"消息"这个概念。它属于 LLM 网关契约的一部分，故落在 client 包，
 * 由 context/ 包组装、provider 消费 —— 依赖方向 service → context → client。
 *
 * role 取值（OpenAI Chat Completions 口径）：
 *
 * - `system` —— 固定指令区（角色 / 契约 / 护栏），每轮不变
 *
 * - `user` —— 每轮变化区（当前场景 / 用户需求 / 校验 issues）
 *
 * - `assistant` —— 历史轮次（M4 真 ReAct 用；本期不产出）
 *
 * - `tool` —— 工具返回（M4 用；本期不产出）
 *
 * 后两者本期**只预留、不产出**：白名单已放行，将来 M4 直接
 * `Message.assistant(...)` 即可，不必再改这个类型。
 *
 * 注：OpenAI 的 tool 角色在协议上还需配 `tool_call_id`。本期没有
 * 工具调用，故不带该字段；M4 引入时在本 record 上扩一个可空字段即可。
 *
 * @param role    消息角色，取值见上（非法值在构造时即被拒绝）
 * @param content 消息正文
 */
public record Message(String role, String content) {

    /** 允许的 role 白名单（含本期预留的 assistant / tool） */
    private static final Set<String> VALID_ROLES =
            Set.of("system", "user", "assistant", "tool");

    /**
     * 紧凑构造器：入口处校验，避免非法 role 一直流到 API 层才报错。
     *
 * content 为 null 时归一成空串 —— OpenAI 要求 content 字段必须存在，
     * 传 null 会让请求体校验失败，归一化比抛异常更符合"辅助约束不该打穿主流程"。
     */
    public Message {
        if (role == null || !VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException(
                    "非法的消息角色: " + role + "，允许: " + VALID_ROLES);
        }
        if (content == null) {
            content = "";
        }
    }

    /** 固定指令区（角色 / 契约 / 护栏） */
    public static Message system(String content) {
        return new Message("system", content);
    }

    /** 每轮变化区（当前场景 / 用户需求 / 校验 issues） */
    public static Message user(String content) {
        return new Message("user", content);
    }

    /** 历史轮次（M4 真 ReAct 预留，本期不调用） */
    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    /** 工具返回（M4 预留，本期不调用） */
    public static Message tool(String content) {
        return new Message("tool", content);
    }
}
