package io.github.nihaoljx.flowchart.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.client.stream.ClientDisconnectedException;
import io.github.nihaoljx.flowchart.client.stream.ProgressContext;
import io.github.nihaoljx.flowchart.client.stream.ProgressEvent;
import io.github.nihaoljx.flowchart.service.UsageService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OpenAI 兼容格式的 LLM Provider（任务34重构）
 *
 * 改造点（相对任务33版本）：
 * - 去掉 @Component / @ConditionalOnProperty / @Value / @Autowired，
 *   改为由 LlmProviderConfig 工厂按 llm.providers 列表逐个 new 出来。
 *   同一个类可对应多个 Provider（mimo / kimi 各一份独立配置）。
 * - 配置全部来自构造器传入的 ProviderConfig，不再读 Spring @Value。
 * - HttpClient 在构造器里构建（@PostConstruct 对 new 出来的对象不生效）。
 * - chatStructured 按 capability 分支：JSON_SCHEMA 走原 strict 逻辑；
 *   JSON_OBJECT 只发 {"type":"json_object"}（MiMo 不支持 strict）；
 *   NONE 退回普通 chat。这就是能力矩阵——主备切换时自动降级 response_format。
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private final ProviderConfig config;
    private final UsageService usageService; // 单测时可能为 null
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiCompatibleProvider(ProviderConfig config, UsageService usageService) {
        this.config = config;
        this.usageService = usageService;

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(config.getProxyEnabled())) {
            builder.proxy(ProxySelector.of(
                    new InetSocketAddress(config.getProxyHost(), config.getProxyPort())));
        }
        this.httpClient = builder.build();
    }

    @Override
    public boolean isConfigured() {
        String key = config.getApiKey();
        return key != null && !key.isBlank()
                && !key.startsWith("${")
                && !key.startsWith("请配置");
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public String chat(String prompt) throws Exception {
        String escapedPrompt = objectMapper.writeValueAsString(prompt);
        String body = String.format("""
            {
                "model": "%s",
                "messages": [{"role": "user", "content": %s}]
            }
            """, config.getModel(), escapedPrompt);

        String rawResponse = sendWithRetry(body);
        System.out.println("=== LLM 原始响应: " + rawResponse);
        return extractContent(rawResponse);
    }

    @Override
    public String chatStructured(String prompt, String schemaJson) throws Exception {
        return switch (config.getCapability()) {
            case JSON_SCHEMA -> buildWithSchema(prompt, schemaJson);
            case JSON_OBJECT -> buildWithJsonObject(prompt, schemaJson);
            case NONE -> chat(prompt);
        };
    }

    /**
     * 任务39+：流式结构化输出。
     * 以 stream:true 请求，逐帧读取；把模型的思考增量（delta.reasoning_content）实时推给前端，
     * 同时累计 content 并返回完整文本（供解析出图）。
     * 若流式在该模型上不可行（如不支持 stream + response_format），自动降级回非流式 chatStructured，保证出图不挂。
     */
    @Override
    public String chatStructuredStream(String prompt, String schemaJson) throws Exception {
        try {
            return switch (config.getCapability()) {
                case JSON_SCHEMA -> streamRequest(schemaPayload(prompt, schemaJson, true));
                case JSON_OBJECT -> streamRequest(jsonObjectPayload(prompt, true));
                case NONE -> chat(prompt);
            };
        } catch (ClientDisconnectedException e) {
            // 客户端断开：直接上抛，不降级（降级会再发一次非流式请求，白烧 token）
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            System.err.println("=== 流式调用失败，降级为非流式出图: " + e.getMessage());
            return chatStructured(prompt, schemaJson);
        }
    }

    /** 流式请求 + 重试：连接层失败重开流（此时还未消费内容，重试干净） */
    private String streamRequest(String body) throws Exception {
        return withRetry(() -> streamOnce(body));
    }

    /** 退避重试（任务39 抽出：streamRequest / sendWithRetry 两处合一）：指数退避，客户端断开不重试 */
    private <T> T withRetry(Callable<T> action) throws Exception {
        long backoff = config.getInitialBackoffMs();
        for (int attempt = 1; ; attempt++) {
            try {
                return action.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (ClientDisconnectedException e) {
                throw e;  // 客户端断开：不重试
            } catch (RetryableException | IOException e) {
                if (attempt >= config.getMaxAttempts()) {
                    throw e;
                }
                long sleepMs = backoff / 2
                        + ThreadLocalRandom.current().nextLong(backoff / 2 + 1);
                ProgressContext.publish(ProgressEvent.of("retry",
                        "第" + attempt + "次调用失败，" + String.format("%.1f", sleepMs / 1000.0)
                                + " 秒后重试（原因：" + e.getMessage() + "）",
                        config.getName(), Map.of("attempt", attempt)));
                System.out.printf("=== LLM 调用失败（第%d次/%d），%.1f 秒后重试... 原因: %s%n",
                        attempt, config.getMaxAttempts(), sleepMs / 1000.0, e.getMessage());
                Thread.sleep(sleepMs);
                backoff = Math.min(config.getMaxBackoffMs(), backoff * 2);
            }
        }
    }

    /** 发起一次流式请求：读 SSE 帧，把思考增量推前端，累计 content 返回 */
    /** 真正发起 HTTP 调用前，推一条"正在调用某模型"（前端进度面板会显示） */
    private void publishProviderCall() {
        ProgressContext.publish(ProgressEvent.of("provider_call",
                "调用 " + config.getName() + "（" + config.getModel() + "）",
                config.getName(), Map.of("model", config.getModel())));
    }

    private String streamOnce(String body) throws IOException, InterruptedException, RetryableException {
        ProgressContext.publish(ProgressEvent.of("provider_call",
                "调用 " + config.getName() + "（" + config.getModel() + "）",
                config.getName(), Map.of("model", config.getModel())));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(Math.max(config.getTimeoutSeconds(), 120)))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofLines());

        int code = response.statusCode();
        if (code != 200) {
            if (code == 429 || code >= 500) {
                throw new RetryableException(code, "stream");
            }
            throw new NonRetryableException(code, "stream");
        }

        StringBuilder content = new StringBuilder();
        try (java.util.stream.Stream<String> lines = response.body()) {
            java.util.Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                // 任务40：每读一帧前检查客户端是否断开；断开则立刻中断，关闭响应体停止烧 token
                if (ProgressContext.isClosed()) {
                    throw new ClientDisconnectedException();
                }
                String line = it.next();
                if (line == null || line.isBlank()) continue;
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if (data.equals("[DONE]")) break;
                consumeStreamChunk(data, content);
            }
        }
        if (content.length() == 0) {
            throw new IOException("流式响应未收到有效 content");
        }
        return content.toString();
    }

    /** 解析一帧流式 chunk：增量思考推前端，content 累计，usage 记账 */
    @SuppressWarnings("unchecked")
    private void consumeStreamChunk(String data, StringBuilder content) {
        try {
            Map<String, Object> root = objectMapper.readValue(data, Map.class);

            recordAndPublishUsage(root);

            List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) return;
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) return;

            // 思考增量：带 delta:true 标记，前端实时追加到"思考流"（而非新建一行）
            Object reasoning = delta.get("reasoning_content");
            if (reasoning != null && !reasoning.toString().isBlank()) {
                ProgressContext.publish(ProgressEvent.of("thinking",
                        reasoning.toString(), config.getName(), Map.of("delta", true)));
            }
            // 正文累计（最终完整 JSON 供出图）
            Object contentDelta = delta.get("content");
            if (contentDelta != null) {
                content.append(contentDelta);
            }
        } catch (Exception e) {
            System.err.println("=== 解析流式 chunk 失败（忽略该帧）: " + e.getMessage());
        }
    }

    /**
     * 任务39：Tool Calling 调用。
     * 带 tools + tool_choice=auto，不带 response_format（两者互斥）。
     * 模型返回 tool_calls 时构造成 {"tool_calls":[...]} 返回；否则返回 content。
     */
    @Override
    public String chatWithTools(List<Map<String, Object>> messages, String toolsJson) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModel());
        payload.put("messages", messages);
        payload.put("tools", objectMapper.readValue(toolsJson, List.class));
        payload.put("tool_choice", "auto");

        String body = objectMapper.writeValueAsString(payload);
        String rawResponse = sendWithRetry(body);
        System.out.println("=== Tool Calling 原始响应: " + rawResponse);
        return extractToolOrContent(rawResponse);
    }

    @SuppressWarnings("unchecked")
    private String extractToolOrContent(String rawResponse) throws Exception {
        Map<String, Object> root = objectMapper.readValue(rawResponse, Map.class);

        recordAndPublishUsage(root);

        Map<String, Object> message = extractMessage(root, rawResponse);
        Object toolCalls = message.get("tool_calls");
        if (toolCalls != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tool_calls", toolCalls);
            return objectMapper.writeValueAsString(out);
        }
        Object content = message.get("content");
        return content == null ? "" : content.toString();
    }

    /** JSON_SCHEMA 模式：沿用任务33的 json_schema + strict:true 硬约束 */
    private String buildWithSchema(String prompt, String schemaJson) throws Exception {
        String body = schemaPayload(prompt, schemaJson, false);
        String rawResponse = sendWithRetry(body);
        System.out.println("=== LLM 原始响应: " + rawResponse);
        return extractContent(rawResponse);
    }

    /** 构造 JSON_SCHEMA 请求体（stream 为 true 时加 stream 字段，用于流式思考） */
    private String schemaPayload(String prompt, String schemaJson, boolean stream) throws Exception {
        Map<String, Object> schemaObj = objectMapper.readValue(schemaJson, Map.class);

        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "generated_diagram");
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", schemaObj);

        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", jsonSchema);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModel());
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        payload.put("response_format", responseFormat);
        if (stream) payload.put("stream", true);
        return objectMapper.writeValueAsString(payload);
    }

    /** JSON_OBJECT 模式：只发 {"type":"json_object"}（MiMo 不支持 strict/name） */
    private String buildWithJsonObject(String prompt, String schemaJson) throws Exception {
        String body = jsonObjectPayload(prompt, false);
        String rawResponse = sendWithRetry(body);
        System.out.println("=== LLM 原始响应: " + rawResponse);
        return extractContent(rawResponse);
    }

    /** 构造 JSON_OBJECT 请求体（stream 为 true 时加 stream 字段，用于流式思考） */
    private String jsonObjectPayload(String prompt, boolean stream) throws Exception {
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_object");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModel());
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        payload.put("response_format", responseFormat);
        if (stream) payload.put("stream", true);
        return objectMapper.writeValueAsString(payload);
    }

    /** 带重试的请求发送（任务30逻辑，变量来源从 @Value 字段改为 config） */
    private String sendWithRetry(String body) throws Exception {
        return withRetry(() -> sendOnce(body));
    }

    private String sendOnce(String body) throws IOException, RetryableException, InterruptedException {
        publishProviderCall();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        System.out.println("=== LLM HTTP 状态码: " + response.statusCode());

        int code = response.statusCode();
        if (code != 200) {
            if (code == 429 || code >= 500) {
                throw new RetryableException(code, response.body());
            }
            throw new NonRetryableException(code, response.body());
        }
        return response.body();
    }

    private static class RetryableException extends Exception {
        RetryableException(int statusCode, String body) {
            super("LLM API 返回错误码 " + statusCode + "，响应内容: " + body);
        }
    }

    private static class NonRetryableException extends RuntimeException {
        NonRetryableException(int statusCode, String body) {
            super("LLM API 返回错误码 " + statusCode + "，响应内容: " + body);
        }
    }

    /** usage 解析（任务39 抽出：extractContent / extractToolOrContent / consumeStreamChunk 三处合一）：记账 + 推前端 */
    @SuppressWarnings("unchecked")
    private void recordAndPublishUsage(Map<String, Object> root) {
        Map<String, Object> usage = (Map<String, Object>) root.get("usage");
        if (usage == null) return;
        int promptTokens = ((Number) usage.get("prompt_tokens")).intValue();
        int completionTokens = ((Number) usage.get("completion_tokens")).intValue();
        int totalTokens = promptTokens + completionTokens;
        if (usageService != null) usageService.record(config.getModel(), promptTokens, completionTokens);
        Map<String, Object> tokenData = new LinkedHashMap<>();
        tokenData.put("promptTokens", promptTokens);
        tokenData.put("completionTokens", completionTokens);
        tokenData.put("totalTokens", totalTokens);
        tokenData.put("model", config.getModel());
        ProgressContext.publish(ProgressEvent.of("token_usage", "Token 用量", config.getName(), tokenData));
    }

    /** choices/message/reasoning 提取（任务39 抽出：extractContent / extractToolOrContent 两处合一）：取 message 并推思考流 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMessage(Map<String, Object> root, String rawResponse) throws Exception {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new Exception("LLM 返回中没有 choices 字段，原始响应: " + rawResponse);
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new Exception("LLM 返回中没有 message 字段，原始响应: " + rawResponse);
        }
        // 任务39+：模型若带思考链（reasoning_content），作为"任务执行过程/思考"实时推给前端
        Object reasoning = message.get("reasoning_content");
        if (reasoning != null && !reasoning.toString().isBlank()) {
            ProgressContext.publish(ProgressEvent.of("thinking", reasoning.toString(), config.getName(), null));
        }
        return message;
    }

    @SuppressWarnings("unchecked")
    private String extractContent(String rawResponse) throws Exception {
        Map<String, Object> root = objectMapper.readValue(rawResponse, Map.class);

        recordAndPublishUsage(root);

        Map<String, Object> message = extractMessage(root, rawResponse);
        Object textObj = message.get("content");
        if (textObj == null || textObj.toString().isBlank()) {
            throw new Exception("LLM 返回的 content 为空，原始响应: " + rawResponse);
        }
        return textObj.toString();
    }
}
