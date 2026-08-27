package io.github.nihaoljx.flowchart.client;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** JSON_SCHEMA 模式：沿用任务33的 json_schema + strict:true 硬约束 */
    private String buildWithSchema(String prompt, String schemaJson) throws Exception {
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

        String body = objectMapper.writeValueAsString(payload);
        String rawResponse = sendWithRetry(body);
        System.out.println("=== LLM 原始响应: " + rawResponse);
        return extractContent(rawResponse);
    }

    /** JSON_OBJECT 模式：只发 {"type":"json_object"}（MiMo 不支持 strict/name） */
    private String buildWithJsonObject(String prompt, String schemaJson) throws Exception {
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_object");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModel());
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        payload.put("response_format", responseFormat);

        String body = objectMapper.writeValueAsString(payload);
        String rawResponse = sendWithRetry(body);
        System.out.println("=== LLM 原始响应: " + rawResponse);
        return extractContent(rawResponse);
    }

    /** 带重试的请求发送（任务30逻辑，变量来源从 @Value 字段改为 config） */
    private String sendWithRetry(String body) throws Exception {
        long backoff = config.getInitialBackoffMs();
        for (int attempt = 1; ; attempt++) {
            try {
                return sendOnce(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (RetryableException | IOException e) {
                if (attempt >= config.getMaxAttempts()) {
                    throw e;
                }
                long sleepMs = backoff / 2
                        + ThreadLocalRandom.current().nextLong(backoff / 2 + 1);
                // 任务37：推一条"第 N 次失败，X 秒后重试"
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

    private String sendOnce(String body) throws IOException, RetryableException, InterruptedException {
        // 任务37：真正发起 HTTP 调用前，推一条"正在调用某模型"（前端进度面板会显示）
        ProgressContext.publish(ProgressEvent.of("provider_call",
                "调用 " + config.getName() + "（" + config.getModel() + "）",
                config.getName(), Map.of("model", config.getModel())));
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

    @SuppressWarnings("unchecked")
    private String extractContent(String rawResponse) throws Exception {
        Map<String, Object> root = objectMapper.readValue(rawResponse, Map.class);

        Map<String, Object> usage = (Map<String, Object>) root.get("usage");
        if (usage != null) {
            int promptTokens = ((Number) usage.get("prompt_tokens")).intValue();
            int completionTokens = ((Number) usage.get("completion_tokens")).intValue();
            int totalTokens = promptTokens + completionTokens;
            System.out.println("=== Token 用量: prompt=" + promptTokens
                    + ", completion=" + completionTokens
                    + ", total=" + totalTokens);
            if (usageService != null) {
                usageService.record(config.getModel(), promptTokens, completionTokens);
            }
            // 任务37：把 Token 用量推给前端，进度面板顶部会用大数字显示
            Map<String, Object> tokenData = new LinkedHashMap<>();
            tokenData.put("promptTokens", promptTokens);
            tokenData.put("completionTokens", completionTokens);
            tokenData.put("totalTokens", totalTokens);
            tokenData.put("model", config.getModel());
            ProgressContext.publish(ProgressEvent.of("token_usage", "Token 用量", config.getName(), tokenData));
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new Exception("LLM 返回中没有 choices 字段，原始响应: " + rawResponse);
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new Exception("LLM 返回中没有 message 字段，原始响应: " + rawResponse);
        }
        Object textObj = message.get("content");
        if (textObj == null || textObj.toString().isBlank()) {
            throw new Exception("LLM 返回的 content 为空，原始响应: " + rawResponse);
        }
        return textObj.toString();
    }
}
