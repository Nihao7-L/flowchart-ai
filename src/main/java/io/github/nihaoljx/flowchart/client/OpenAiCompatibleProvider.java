package io.github.nihaoljx.flowchart.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI 兼容格式的 LLM Provider
 *
 * 适用范围：Kimi、DeepSeek、通义千问、智谱 GLM 等——
 * 凡是遵循 OpenAI Chat Completions 格式的 API，都用这一个类，改配置就行
 *
 * @ConditionalOnProperty：Spring 条件装配
 *   当 application.yml 里 llm.provider=openai 时，这个类才会被创建
 *   换成别的 provider 时，这个类不会被加载
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiCompatibleProvider implements LlmProvider {

    @Value("${llm.base-url}")
    private String apiUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${llm.proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${llm.proxy.port:7890}")
    private int proxyPort;

    @Value("${llm.timeout-seconds:60}")
    private int timeoutSeconds;

    private HttpClient httpClient;

    /** Jackson 用于 JSON 序列化（转义 prompt）和反序列化（解析响应） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (proxyEnabled) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        httpClient = builder.build();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && !apiKey.equals("${LLM_API_KEY}")
                && !apiKey.startsWith("请配置");
    }

    /**
     * 发送 prompt → 返回提取后的纯文本
     *
     * 两步合一：发 HTTP 请求 + 解析响应 JSON
     * 对外只暴露纯文本，调用方（解析/校验层）不关心响应格式
     */
    @Override
    public String chat(String prompt) throws Exception {
        return chat(prompt, timeoutSeconds);
    }

    @Override
    public String chat(String prompt, int callTimeoutSeconds)
            throws Exception {
        // ===== 1. 构建 OpenAI 兼容格式的请求体 =====
        // 用 Jackson 自动转义，不再手拼 JSON
        String escapedPrompt = objectMapper.writeValueAsString(prompt);

        String body = String.format("""
            {
                "model": "%s",
                "messages": [{"role": "user", "content": %s}]
            }
            """, model, escapedPrompt);

        // ===== 2. 发 HTTP 请求（Bearer 鉴权）=====
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(callTimeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> response =
                sendWithHardTimeout(request, callTimeoutSeconds);
        long costMs = System.currentTimeMillis() - startedAt;

        // 打印日志方便排查（带上耗时：这是判断"要不要收紧单轮上限"的依据）
        System.out.println("=== LLM HTTP 状态码: "
                + response.statusCode()
                + "，耗时 " + costMs + " ms");
        System.out.println("=== LLM 原始响应: " + response.body());

        if (response.statusCode() != 200) {
            throw new Exception("LLM API 返回错误码 " + response.statusCode()
                    + "，响应内容: " + response.body());
        }

        // ===== 3. 解析响应：choices[0].message.content =====
        return extractContent(response.body());
    }

    /**
     * 带"真·总时限"的发送
     *
     * 不能只依赖 {@code HttpRequest.timeout()}：实测推理类模型会在真正
     * 开始生成前先回一个响应头（首字节 8.2 秒即到达），此后 JDK 的请求
     * 计时便不再生效，正文可以再传近百秒。所以这里在 Future 层补一道
     * 总时限，覆盖"连接 + 响应头 + 正文"全过程。
     *
     * @param request 已构建的请求
     * @param seconds 整次调用的硬上限，单位秒
     * @return HTTP 响应
     * @throws Exception 超时、网络错误或被中断
     */
    private HttpResponse<String> sendWithHardTimeout(
            HttpRequest request, int seconds) throws Exception {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception("LLM 调用超时：整次调用超过 "
                    + seconds + " 秒（含正文传输）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new Exception("LLM 调用失败: "
                    + (cause == null
                            ? e.getMessage()
                            : cause.getMessage()),
                    cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new Exception("LLM 调用被中断");
        }
    }

    /**
     * 从 OpenAI 兼容格式的响应中提取文本
     *
     * 响应格式：
     * {
     *   "choices": [{ "message": { "role": "assistant", "content": "..." } }],
     *   "usage": { "prompt_tokens": 123, "completion_tokens": 456, "total_tokens": 579 }
     * }
     *
     * 成本统计预留：usage 字段先打日志，以后要做成本统计时
     * 把 log 换成存数据库即可，chat() 接口签名不用改
     */
    @SuppressWarnings("unchecked")
    private String extractContent(String rawResponse) throws Exception {
        Map<String, Object> root = objectMapper.readValue(rawResponse, Map.class);

        // ===== 提取 usage（成本统计用，目前只打日志）=====
        Map<String, Object> usage = (Map<String, Object>) root.get("usage");
        if (usage != null) {
            System.out.println("=== Token 用量: prompt=" + usage.get("prompt_tokens")
                    + ", completion=" + usage.get("completion_tokens")
                    + ", total=" + usage.get("total_tokens"));
        }

        // ===== 提取 choices[0].message.content =====
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
