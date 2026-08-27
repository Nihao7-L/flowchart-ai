package io.github.nihaoljx.flowchart.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CachingLlmProviderTest {

    /** 第一次返回固定串，调用次数可数 */
    static class CountingProvider implements LlmProvider {
        final AtomicInteger calls = new AtomicInteger();
        @Override public String chat(String p) { calls.incrementAndGet(); return "resp"; }
        @Override public String chatStructured(String p, String s) { calls.incrementAndGet(); return "resp"; }
        @Override public boolean isConfigured() { return true; }
    }

    @Test
    void 命中缓存不重复调LLM() throws Exception {
        CountingProvider inner = new CountingProvider();
        CachingLlmProvider cache = new CachingLlmProvider(inner, null, 60_000L);
        String a = cache.chatStructured("画个登录流程", "{}");
        String b = cache.chatStructured("画个登录流程", "{}");   // 相同输入
        assertEquals("resp", a);
        assertEquals("resp", b);
        assertEquals(1, inner.calls.get(), "第二次应命中缓存，不再调下层");
    }

    @Test
    void 不同输入分别调用() throws Exception {
        CountingProvider inner = new CountingProvider();
        CachingLlmProvider cache = new CachingLlmProvider(inner, null, 60_000L);
        cache.chatStructured("流程A", "{}");
        cache.chatStructured("流程B", "{}");
        assertEquals(2, inner.calls.get());
    }

    @Test
    void 过期后重新调用() throws Exception {
        CountingProvider inner = new CountingProvider();
        CachingLlmProvider cache = new CachingLlmProvider(inner, null, 1L); // TTL=1ms
        cache.chatStructured("x", "{}");
        Thread.sleep(5);
        cache.chatStructured("x", "{}");
        assertEquals(2, inner.calls.get(), "TTL 过期后应重新调下层");
    }
}
