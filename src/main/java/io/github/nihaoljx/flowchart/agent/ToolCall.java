package io.github.nihaoljx.flowchart.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次工具调用请求（v2-55，learn-claude-code s02 的 tool_use 对应物）
 *
 * <p>为什么独立成类型：agent 协议里"模型提议调哪个工具、带什么参数"与
 * "执行结果"是两类东西，与 LLM 网关的 {@link com.fasterxml.jackson.databind.JsonNode}
 * 参数体分开后，{@link AgentLlmClient} 的实现（step 3 接 OpenAI 口径）才知道
 * 怎么把 {@code id} 回填进 tool_result。
 *
 * @param id        调用 ID（厂商返回的 tool_call id，回喂 observation 时必须带上）
 * @param name      工具名（须是 {@link ToolRegistry} 里注册过的名字）
 * @param arguments 工具参数（JSON 对象；畸形时由工具自己报错，不在构造时卡）
 */
public record ToolCall(String id, String name, JsonNode arguments) {
}
