package io.github.nihaoljx.flowchart.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAiCompatibleProvider 测试
 *
 * 测试范围：请求拼装 + 响应解析 + 异常语义（不触网）
 *
 * 做法：用 JDK 自带的 com.sun.net.httpserver.HttpServer 在 127.0.0.1 随机端口起桩服务，
 * 把 llm.base-url 指向它 —— 因此不需要 MockWebServer 之类的新依赖。
 *
 * 覆盖的失败模式：HTTP 非 200、响应结构缺字段、content 为空、非法 JSON
 */
class OpenAiCompatibleProviderTest {

    private HttpServer server;

    /** 最近一次请求体：用来断言"到底发出去了什么"（v2-41 多消息入口需要） */
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    /** 起一个只回固定状态码 + 固定响应体的桩服务，返回它的完整 URL */
    private String startStub(int statusCode, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    /** 同上，但额外把请求体存下来，供断言"请求体长什么样" */
    private String startStubCapturing(int statusCode, String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    /** 构造一个已注入配置的 provider（字段是 @Value 注入的私有字段，测试用反射塞值） */
    private OpenAiCompatibleProvider newProvider(String baseUrl, String apiKey) {
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider();
        ReflectionTestUtils.setField(provider, "apiUrl", baseUrl);
        ReflectionTestUtils.setField(provider, "apiKey", apiKey);
        ReflectionTestUtils.setField(provider, "model", "test-model");
        ReflectionTestUtils.setField(provider, "proxyEnabled", false);
        ReflectionTestUtils.setField(provider, "timeoutSeconds", 5);
        provider.init();
        return provider;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("正常响应：提取 choices[0].message.content")
    void chatExtractsContent() throws Exception {
        String body = """
                {
                  "choices": [ { "message": { "role": "assistant", "content": "你好，我是模型" } } ],
                  "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
                }
                """;
        String url = startStub(200, body);

        assertEquals("你好，我是模型", newProvider(url, "sk-test").chat("随便问点什么"));
    }

    @Test
    @DisplayName("正常响应：prompt 里的引号与换行不会破坏请求体 JSON")
    void chatEscapesPromptSafely() throws Exception {
        String url = startStub(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

        String tricky = "他说\"你好\"\n第二行\\反斜杠";
        assertEquals("ok", newProvider(url, "sk-test").chat(tricky));
    }

    // ==================== 多消息入口（v2-41）====================

    @Test
    @DisplayName("多消息入口：system 与 user 两个角色都发出、顺序不变")
    void chatSendsSystemAndUserRolesInOrder() throws Exception {
        String ok = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        String url = startStubCapturing(200, ok);

        String result = newProvider(url, "sk-test").chat(
                List.of(Message.system("我是规则"), Message.user("用户需求")), 5);

        assertEquals("ok", result);
        JsonNode sent = new ObjectMapper().readTree(lastRequestBody.get());
        assertEquals("test-model", sent.get("model").asText());
        JsonNode messages = sent.get("messages");
        assertEquals(2, messages.size(), "应发出两条消息");
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("我是规则", messages.get(0).get("content").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("用户需求", messages.get(1).get("content").asText());
    }

    @Test
    @DisplayName("旧入口仍等价于单条 user 消息（兼容性）")
    void legacyChatSendsSingleUserMessage() throws Exception {
        String url = startStubCapturing(
                200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

        assertEquals("ok", newProvider(url, "sk-test").chat("老调用方"));

        JsonNode sent = new ObjectMapper().readTree(lastRequestBody.get());
        assertEquals(1, sent.get("messages").size());
        assertEquals("user", sent.get("messages").get(0).get("role").asText());
        assertEquals("老调用方", sent.get("messages").get(0).get("content").asText());
    }

    @Test
    @DisplayName("多消息入口：空消息列表直接拒绝，不发请求")
    void chatRejectsEmptyMessages() {
        assertThrows(IllegalArgumentException.class,
                () -> newProvider("http://127.0.0.1:1/x", "sk-test")
                        .chat(List.of(), 5));
    }

    @Test
    @DisplayName("Message 拒绝非法 role（预留角色可通过）")
    void messageValidatesRole() {
        assertThrows(IllegalArgumentException.class, () -> new Message("robot", "x"));
        assertEquals("assistant", Message.assistant("x").role());
        assertEquals("tool", Message.tool("x").role());
    }

    // ==================== 异常路径 ====================

    @Test
    @DisplayName("HTTP 401：抛异常且消息里带状态码与响应体")
    void chatThrowsOnNon200() throws Exception {
        String url = startStub(401, "{\"error\":{\"message\":\"invalid api key\"}}");

        Exception ex = assertThrows(Exception.class,
                () -> newProvider(url, "sk-bad").chat("hi"));

        assertTrue(ex.getMessage().contains("401"), "异常消息应含状态码，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("invalid api key"), "异常消息应含响应体");
    }

    @Test
    @DisplayName("响应缺 choices：抛异常并指明缺失字段")
    void chatThrowsWhenChoicesMissing() throws Exception {
        String url = startStub(200, "{\"id\":\"x\",\"usage\":{}}");

        Exception ex = assertThrows(Exception.class,
                () -> newProvider(url, "sk-test").chat("hi"));

        assertTrue(ex.getMessage().contains("choices"));
    }

    @Test
    @DisplayName("choices 为空数组：同样抛异常")
    void chatThrowsWhenChoicesEmpty() throws Exception {
        String url = startStub(200, "{\"choices\":[]}");

        Exception ex = assertThrows(Exception.class,
                () -> newProvider(url, "sk-test").chat("hi"));

        assertTrue(ex.getMessage().contains("choices"));
    }

    @Test
    @DisplayName("响应缺 message：抛异常")
    void chatThrowsWhenMessageMissing() throws Exception {
        String url = startStub(200, "{\"choices\":[{\"finish_reason\":\"stop\"}]}");

        Exception ex = assertThrows(Exception.class,
                () -> newProvider(url, "sk-test").chat("hi"));

        assertTrue(ex.getMessage().contains("message"));
    }

    @Test
    @DisplayName("content 为空白：抛异常")
    void chatThrowsWhenContentBlank() throws Exception {
        String url = startStub(200, "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}");

        Exception ex = assertThrows(Exception.class,
                () -> newProvider(url, "sk-test").chat("hi"));

        assertTrue(ex.getMessage().contains("content"));
    }

    @Test
    @DisplayName("响应不是合法 JSON：抛异常")
    void chatThrowsOnMalformedJson() throws Exception {
        String url = startStub(200, "<html>502 Bad Gateway</html>");

        assertThrows(Exception.class, () -> newProvider(url, "sk-test").chat("hi"));
    }

    // ==================== isConfigured 判定 ====================

    @Test
    @DisplayName("isConfigured：空 key / 占位符 / 中文提示语都算未配置")
    void isConfiguredRejectsPlaceholders() {
        assertFalse(newProvider("http://127.0.0.1:1/x", null).isConfigured(), "null 应视为未配置");
        assertFalse(newProvider("http://127.0.0.1:1/x", "").isConfigured(), "空串应视为未配置");
        assertFalse(newProvider("http://127.0.0.1:1/x", "   ").isConfigured(), "空白应视为未配置");
        assertFalse(newProvider("http://127.0.0.1:1/x", "${LLM_API_KEY}").isConfigured(), "未替换的占位符应视为未配置");
        assertFalse(newProvider("http://127.0.0.1:1/x", "请配置你的 key").isConfigured(), "中文提示语应视为未配置");
    }

    @Test
    @DisplayName("isConfigured：真实 key 视为已配置")
    void isConfiguredAcceptsRealKey() {
        assertTrue(newProvider("http://127.0.0.1:1/x", "sk-abcdef123456").isConfigured());
    }

    // ==================== max_tokens（2026-10-10）====================

    @Test
    @DisplayName("请求体：配了 max-tokens 就必须真的发出去（截断的解药）")
    void sendsMaxTokensWhenConfigured() throws Exception {
        String url = startStubCapturing(
                200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        OpenAiCompatibleProvider provider = newProvider(url, "sk-test");
        ReflectionTestUtils.setField(provider, "maxTokens", 8192);

        assertEquals("ok", provider.chat("hi"));

        JsonNode sent = new ObjectMapper().readTree(lastRequestBody.get());
        assertTrue(sent.has("max_tokens"),
                "配了 max-tokens 却没发出去 = 大图仍会被厂商默认上限截断");
        assertEquals(8192, sent.get("max_tokens").asInt());
    }

    @Test
    @DisplayName("请求体：没配 max-tokens 就不要凭空加这个字段")
    void omitsMaxTokensWhenNotConfigured() throws Exception {
        String url = startStubCapturing(
                200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

        assertEquals("ok", newProvider(url, "sk-test").chat("hi"));

        JsonNode sent = new ObjectMapper().readTree(lastRequestBody.get());
        assertFalse(sent.has("max_tokens"),
                "默认不设：各厂商对越界的 max_tokens 会直接 400");
    }

    // ==================== 流式逐帧 usage 占位（2026-10-10 实测现场）====================

    @Test
    @DisplayName("流式：每帧都带全 0 的 usage 占位 → 正文仍完整拼接")
    void streamingToleratesZeroUsagePlaceholders() throws Exception {
        // 复刻魔搭的实测行为：每一帧都塞一份全 0 的 usage 对象。
        // 原先代码在逐帧回调里直接 println 这份占位，一次 3000+ 字符的输出
        // 会刷出几百行 "prompt=0, completion=0, total=0"（本次修的就是它）。
        // 这里守住数据面：占位值不得污染正文拼接。
        String zeroUsage = "\"usage\":{\"prompt_tokens\":0,"
                + "\"completion_tokens\":0,\"total_tokens\":0},";
        String sse = "data: {" + zeroUsage + "\"choices\":[{\"finish_reason\":null,"
                + "\"delta\":{\"content\":\"{\\\"elements\\\":\"}}]}\n\n"
                + "data: {" + zeroUsage + "\"choices\":[{\"finish_reason\":null,"
                + "\"delta\":{\"content\":\"[]}\"}}]}\n\n"
                + "data: {" + zeroUsage + "\"choices\":[{\"finish_reason\":\"stop\","
                + "\"delta\":{}}]}\n\n"
                + "data: [DONE]\n\n";
        String url = startStub(200, sse);
        OpenAiCompatibleProvider provider = newProvider(url, "sk-test");
        ReflectionTestUtils.setField(provider, "streamMode", true);

        assertEquals("{\"elements\":[]}", provider.chat("hi"),
                "逐帧零值 usage 不得影响正文拼接");
    }
}
