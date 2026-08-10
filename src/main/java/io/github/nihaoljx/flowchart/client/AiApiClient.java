package io.github.nihaoljx.flowchart.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Gemini API 调用客户端
 *
 * 职责：把 prompt 发给 Gemini，拿回原始 JSON 响应
 * 不做解析、不做校验——那些是 ParserService 的事
 *
 * @Component 告诉 Spring：把这个类自动创建为单例 Bean
 *              其他类用 @Autowired 就能拿到它
 */
@Component
public class AiApiClient {

    /** Gemini API 地址，从 application.yml 注入 */
    @Value("${llm.base-url}")
    private String apiUrl;

    /** API Key，从环境变量 LLM_API_KEY 注入 */
    @Value("${llm.api-key}")
    private String apiKey;

    /**
     * Java 11 内置的 HttpClient，不需要额外依赖
     * 全项目共用一个实例（线程安全）
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))  // 建连超时 10 秒
            .build();

    /**
     * 发送 prompt 到 Gemini，返回原始 JSON
     *
     * Gemini API 格式备忘：
     * - URL: https://generativelanguage.googleapis.com/v1beta/models/...:generateContent?key=xxx
     * - 请求体: {"contents": [{"parts": [{"text": "prompt内容"}]}]}
     * - 响应体: {"candidates": [{"content": {"parts": [{"text": "返回内容"}]}}]}
     *
     * 注意：Gemini 的 API Key 是 URL 参数，不是 HTTP Header
     *       这和 OpenAI/DeepSeek 不一样
     *
     * @param prompt 完整 prompt（模板 + 用户输入，由 PromptService 组装）
     * @return Gemini 返回的原始 JSON 字符串，不做任何处理
     */
    public String chat(String prompt) throws Exception {
        // 构建请求体：把 prompt 内容转义后塞进 Gemini 格式
        // \\ → \\\\  \" → \\\"  \n → \\n  防止 JSON 格式被破坏
        String body = String.format("""
            {
                "contents": [{
                    "parts": [{"text": "%s"}]
                }]
            }
            """, prompt.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n"));

        // API Key 拼在 URL 后面，这是 Gemini 特有的鉴权方式
        URI uri = URI.create(apiUrl + "?key=" + apiKey);

        // 构建 HTTP POST 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))  // 请求总超时 30 秒
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        // 同步发送（会阻塞直到收到响应）
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        return response.body();
    }
}
