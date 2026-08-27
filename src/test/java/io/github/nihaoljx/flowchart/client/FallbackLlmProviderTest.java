package io.github.nihaoljx.flowchart.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FallbackLlmProviderTest {

    /** 永远抛异常 */
    static class FailingProvider implements LlmProvider {
        @Override public String chat(String p) throws Exception { throw new Exception("boom"); }
        @Override public String chatStructured(String p, String s) throws Exception { throw new Exception("boom"); }
        @Override public boolean isConfigured() { return true; }
    }

    /** 第一次调用成功 */
    static class OkProvider implements LlmProvider {
        @Override public String chat(String p) { return "ok"; }
        @Override public String chatStructured(String p, String s) { return "ok"; }
        @Override public boolean isConfigured() { return true; }
    }

    @Test
    void 主Provider失败后切到备用() throws Exception {
        FailingProvider primary = new FailingProvider();
        OkProvider secondary = new OkProvider();
        FallbackLlmProvider fb = new FallbackLlmProvider(List.of(primary, secondary), RoutingStrategy.FIXED);
        assertEquals("ok", fb.chat("hi"));
    }

    @Test
    void 全部失败抛出异常() {
        FailingProvider a = new FailingProvider();
        FailingProvider b = new FailingProvider();
        FallbackLlmProvider fb = new FallbackLlmProvider(List.of(a, b), RoutingStrategy.FIXED);
        assertThrows(Exception.class, () -> fb.chat("hi"));
    }

    @Test
    void chatStructured也走fallback() throws Exception {
        FailingProvider primary = new FailingProvider();
        OkProvider secondary = new OkProvider();
        FallbackLlmProvider fb = new FallbackLlmProvider(List.of(primary, secondary), RoutingStrategy.FIXED);
        assertEquals("ok", fb.chatStructured("hi", "{}"));
    }
}
