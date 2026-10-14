package io.github.nihaoljx.flowchart.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentLlmClient 的 OpenAI 兼容实现（步骤 3 的最小真实件）
 *
 * 协议口径（OpenAI Chat Completions + function calling）：
 *
 * - assistant 消息带 `tool_calls` 数组（id/type/function.name/arguments）；
 *
 * - tool 结果消息带 `tool_call_id` + `content`；
 *
 * - 响应取 `choices[0].message` 的 `content` 与
 *       `tool_calls`；`reasoning_content`（推理草稿）刻意忽略
 *       —— 与既有 provider「只取 delta.content」同一纪律。
 *
 * 范围刻意收窄：非流式、单候选、无重试——故障转移/冷却/流式是 M13
 * 网关的活，等探针（验收 5）定下支持 tool_calls 的候选子集后再接。
 * 超时直接用调用方传入的剩余预算。
 */
public final class OpenAiToolClient implements AgentLlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public OpenAiToolClient(String baseUrl, String apiKey,
                            String model, int maxTokens) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public AgentTurn respond(List<AgentMessage> messages,
                             List<Map<String, Object>> tools,
                             int timeoutSeconds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", encode(messages));
        payload.put("tools", tools);
        payload.put("tool_choice", "auto");
        payload.put("stream", false);
        if (maxTokens > 0) {
            payload.put("max_tokens", maxTokens);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(payload)))
                .build();
        HttpResponse<String> resp =
                http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("LLM HTTP " + resp.statusCode()
                    + ": " + resp.body().substring(
                    0, Math.min(300, resp.body().length())));
        }
        return decode(MAPPER.readTree(resp.body()));
    }

    /** agent 消息账本 → OpenAI 请求体消息（含 tool_calls / tool_call_id 成对回填） */
    private static List<Map<String, Object>> encode(List<AgentMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentMessage m : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.role());
            switch (m.role()) {
                case "assistant" -> {
                    item.put("content", m.content());
                    if (!m.toolCalls().isEmpty()) {
                        List<Map<String, Object>> calls = new ArrayList<>();
                        for (ToolCall call : m.toolCalls()) {
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", call.name());
                            fn.put("arguments", call.arguments() == null
                                    ? "{}"
                                    : call.arguments().toString());
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("id", call.id());
                            c.put("type", "function");
                            c.put("function", fn);
                            calls.add(c);
                        }
                        item.put("tool_calls", calls);
                    }
                }
                case "tool" -> {
                    item.put("tool_call_id", m.toolCallId());
                    item.put("content", m.content());
                }
                default -> item.put("content", m.content());
            }
            out.add(item);
        }
        return out;
    }

    /** choices[0].message → AgentTurn（只取 content + tool_calls；草稿忽略） */
    private static AgentTurn decode(JsonNode root) {
        JsonNode choice = root.path("choices").get(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").asText("");
        List<ToolCall> calls = new ArrayList<>();
        JsonNode rawCalls = message.get("tool_calls");
        if (rawCalls != null && rawCalls.isArray()) {
            for (JsonNode c : rawCalls) {
                String id = c.path("id").asText("");
                String name = c.path("function").path("name").asText("");
                String argsText = c.path("function")
                        .path("arguments").asText("{}");
                JsonNode args;
                try {
                    args = MAPPER.readTree(argsText);
                } catch (Exception e) {
                    args = MAPPER.createObjectNode();
                }
                calls.add(new ToolCall(id, name, args));
            }
        }
        return new AgentTurn(content, calls);
    }
}
