package io.github.nihaoljx.flowchart.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务30测试：重试与退避
 * 任务33测试：结构化输出（JSON_SCHEMA 模式请求体含 response_format 硬约束）
 * 任务34测试：JSON_OBJECT 模式只发 {"type":"json_object"}，不含 strict
 */
class OpenAiCompatibleProviderTest {

    private HttpServer server;
    private int port;
    private AtomicInteger calls;
    private AtomicReference<String> lastRequestBody;

    @BeforeEach
    void setUp() throws Exception {
        calls = new AtomicInteger();
        lastRequestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 限流429后重试成功() throws Exception {
        stub(429, 200);
        OpenAiCompatibleProvider provider = newProvider(3, ProviderCapability.JSON_SCHEMA);
        assertEquals("你好", provider.chat("你好"));
        assertEquals(2, calls.get(), "429 后应重试一次");
    }

    @Test
    void 服务器500连续失败达到上限抛出异常() throws Exception {
        stub(500, 500, 500);
        OpenAiCompatibleProvider provider = newProvider(3, ProviderCapability.JSON_SCHEMA);
        Exception e = assertThrows(Exception.class, () -> provider.chat("你好"));
        assertTrue(e.getMessage().contains("500"), "错误信息应包含状态码");
        assertEquals(3, calls.get(), "最多尝试 3 次");
    }

    @Test
    void 客户端400错误不重试() throws Exception {
        stub(400);
        OpenAiCompatibleProvider provider = newProvider(3, ProviderCapability.JSON_SCHEMA);
        assertThrows(Exception.class, () -> provider.chat("你好"));
        assertEquals(1, calls.get(), "4xx 不应重试");
    }

    @Test
    void 第一次就成功不重试() throws Exception {
        stub(200);
        OpenAiCompatibleProvider provider = newProvider(3, ProviderCapability.JSON_SCHEMA);
        provider.chat("你好");
        assertEquals(1, calls.get(), "成功不应重试");
    }

    @Test
    void 结构化输出_JSON_SCHEMA模式含response_format硬约束() throws Exception {
        stub(200);
        OpenAiCompatibleProvider provider = newProvider(3, ProviderCapability.JSON_SCHEMA);
        String schema = """
            {
              "type": "object",
              "properties": { "title": { "type": "string" } },
              "required": ["title"],
              "additionalProperties": false
            }
            """;
        assertEquals("你好", provider.chatStructured("你好", schema));
        String body = lastRequestBody.get();
        assertNotNull(body);
        assertTrue(body.contains("\"response_format\""));
        assertTrue(body.contains("\"type\":\"json_schema\""));
        assertTrue(body.contains("\"strict\":true"));
        assertTrue(body.contains("\"name\":\"generated_diagram\""));
        assertTrue(body.contains("\"required\":[\"title\"]"));
        assertTrue(body.contains("\"model\":\"test-model\""));
    }

    @Test
    void 结构化输出_JSON_OBJECT模式只发json_object不含strict() throws Exception {
        stub(200);
        OpenAiCompatibleProvider provider = newProvider(3, ProviderCapability.JSON_OBJECT);
        String schema = """
            { "type": "object", "properties": { "title": { "type": "string" } } }
            """;
        assertEquals("你好", provider.chatStructured("你好", schema));
        String body = lastRequestBody.get();
        assertNotNull(body);
        assertTrue(body.contains("\"type\":\"json_object\""), "JSON_OBJECT 模式应发 json_object");
        assertFalse(body.contains("\"strict\""), "JSON_OBJECT 模式不应含 strict");
        assertFalse(body.contains("\"json_schema\""), "JSON_OBJECT 模式不应含 json_schema");
    }

    @Test
    void 非法Schema字符串应在请求前抛出异常() throws Exception {
        stub(200);
        OpenAiCompatibleProvider provider = newProvider(3, ProviderCapability.JSON_SCHEMA);
        assertThrows(Exception.class, () -> provider.chatStructured("你好", "这不是JSON{{{"));
        assertEquals(0, calls.get(), "非法 Schema 不应发出请求");
    }

    private OpenAiCompatibleProvider newProvider(int maxAttempts, ProviderCapability cap) {
        ProviderConfig cfg = new ProviderConfig();
        cfg.setBaseUrl("http://127.0.0.1:" + port + "/v1/chat/completions");
        cfg.setApiKey("test-key");
        cfg.setModel("test-model");
        cfg.setCapability(cap);
        cfg.setTimeoutSeconds(5);
        cfg.setMaxAttempts(maxAttempts);
        cfg.setInitialBackoffMs(50L);
        cfg.setMaxBackoffMs(200L);
        cfg.setProxyEnabled(false);
        return new OpenAiCompatibleProvider(cfg, null);
    }

    private void stub(int... statusCodes) {
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int idx = Math.min(calls.getAndIncrement(), statusCodes.length - 1);
            int code = statusCodes[idx];
            if (code == 200) {
                String json = "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else {
                exchange.sendResponseHeaders(code, -1);
            }
            exchange.close();
        });
    }
}
