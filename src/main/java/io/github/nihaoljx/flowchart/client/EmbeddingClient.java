package io.github.nihaoljx.flowchart.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 客户端：把文字变成向量（指纹）。
 * 用于 RAG 的"检索"那一半，和 OpenAiCompatibleProvider 同构（都是 OpenAI 兼容 /v1 接口）。
 * 这里用硅基流动 BAAI/bge-m3（免费），也可换成任意支持 /v1/embeddings 的模型。
 */
public class EmbeddingClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmbeddingClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)   // 硅基流动偶发 reset Java 默认 HTTP/2 连接，强制 1.1 更稳
                .build();
    }

    /** 没配 key 或还是占位符时，视为未启用（避免静默失败） */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && !apiKey.startsWith("sk-换成");
    }

    /** 单条文本 → 向量 */
    public float[] embed(String text) throws Exception {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * 批量文本 → 向量（一次 HTTP 往返，少调接口）。
     * 带重试：Connection reset 等瞬时网络错误、以及 429/5xx 服务端错误会自动退避重试，
     * 避免偶发抖动直接让整个生成失败（任务38 容错）。
     */
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        String body = objectMapper.writeValueAsString(new EmbedRequest(model, texts));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        Exception last = null;
        long backoff = 800;            // 首次退避 0.8s，之后翻倍
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    // 429 限流 / 5xx 服务端错误 → 重试；4xx 业务错误（如 key 无效）→ 直接抛
                    if ((response.statusCode() == 429 || response.statusCode() >= 500) && attempt < maxAttempts) {
                        Thread.sleep(backoff);
                        backoff *= 2;
                        continue;
                    }
                    throw new Exception("Embedding API 错误 " + response.statusCode() + "： " + response.body());
                }
                return parse(response.body());
            } catch (InterruptedException e) {
                // Connection reset 等瞬时网络错误 → 退避重试
                last = e;
                if (attempt < maxAttempts) {
                    Thread.sleep(backoff);
                    backoff *= 2;
                    continue;
                }
            }
        }
        throw new Exception("Embedding 调用失败（已重试 " + maxAttempts + " 次）： "
                + (last != null ? last.getMessage() : "未知错误"));
    }

    /** 把 Embedding 返回体解析成向量列表 */
    private List<float[]> parse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw new Exception("Embedding 返回异常： " + body);
        }
        List<float[]> result = new ArrayList<>();
        for (JsonNode node : data) {
            JsonNode embedding = node.get("embedding");
            float[] vec = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vec[i] = (float) embedding.get(i).asDouble();
            }
            result.add(vec);
        }
        return result;
    }

    /** 对齐 OpenAI embeddings 接口的请求体 */
    private record EmbedRequest(String model, List<String> input) {}
}
