package io.github.nihaoljx.flowchart.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FailoverLlmProvider 测试：故障转移 / 冷却 / 截断重试 / 升档。
 *
 * <p>用 JDK 自带 com.sun.net.httpserver 起桩服务（不触网），
 * 与 OpenAiCompatibleProviderTest 同一套做法。
 */
class FailoverLlmProviderTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(s -> s.stop(0));
        servers.clear();
    }

    private String startStub(AtomicInteger hits, int statusCode, String body)
            throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            hits.incrementAndGet();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            // 成功响应走 SSE（生产路径就是流式）；错误响应仍是普通 JSON
            boolean sse = body.startsWith("data:");
            ex.getResponseHeaders().add("Content-Type",
                    sse ? "text/event-stream" : "application/json");
            ex.sendResponseHeaders(statusCode, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        s.start();
        servers.add(s);
        return "http://127.0.0.1:" + s.getAddress().getPort()
                + "/v1/chat/completions";
    }

    /**
     * 起一个"吐一帧就卡住"的桩：用于验证空闲判死（模型还在跑 vs 卡死）。
     * 第一帧立即送出，之后 {@code stallMs} 毫秒内不再发任何字节。
     */
    private String startStallingStub(AtomicInteger hits, int stallMs)
            throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            byte[] first = ("data: {\"choices\":[{\"finish_reason\":null,"
                    + "\"delta\":{\"content\":\"AAAA\"}}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            // 用 chunked（长度 0 = 未知）：先发头 + 首帧，再挂住
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            os.write(first);
            os.flush();
            try {
                Thread.sleep(stallMs);
                os.write("data: [DONE]\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                os.close();
            }
        });
        s.start();
        servers.add(s);
        return "http://127.0.0.1:" + s.getAddress().getPort()
                + "/v1/chat/completions";
    }

    /** 成功响应：SSE 流（一帧正文 + 一帧 finish_reason=stop + [DONE]） */
    private static String ok(String content) {
        return "data: {\"choices\":[{\"finish_reason\":null,"
                + "\"delta\":{\"content\":\"" + content + "\"}}]}\n\n"
                + "data: {\"choices\":[{\"finish_reason\":\"stop\","
                + "\"delta\":{}}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private static LlmProperties.Candidate cand(
            String name, String url, String model, int tier) {
        LlmProperties.Candidate c = new LlmProperties.Candidate();
        c.setName(name);
        c.setBaseUrl(url);
        c.setApiKey("test-key");
        c.setModel(model);
        c.setTier(tier);
        return c;
    }

    private static FailoverLlmProvider router(LlmProperties.Candidate... cs) {
        LlmProperties p = new LlmProperties();
        p.setFailoverEnabled(true);
        p.setCandidates(List.of(cs));
        FailoverLlmProvider r = new FailoverLlmProvider(p);
        r.init();
        return r;
    }

    @Test
    @DisplayName("首选 402 → 自动切换并命中次选")
    void fallsBackOn402() throws Exception {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();
        String a = startStub(hitsA, 402,
                "{\"error\":{\"message\":\"Insufficient balance\"}}");
        String b = startStub(hitsB, 200, ok("HELLO"));
        FailoverLlmProvider r = router(
                cand("a", a, "m1", 1), cand("b", b, "m2", 2));

        assertEquals("HELLO", r.chat(List.of(Message.user("hi")), 10));
        assertEquals(1, hitsA.get());
        assertEquals(1, hitsB.get());
    }

    @Test
    @DisplayName("首选 402 进入冷却，第二次调用不再打它")
    void coolsDownAfter402() throws Exception {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();
        String a = startStub(hitsA, 402, "{}");
        String b = startStub(hitsB, 200, ok("OK"));
        FailoverLlmProvider r = router(
                cand("a", a, "m1", 1), cand("b", b, "m2", 2));

        r.chat(List.of(Message.user("hi")), 10);
        r.chat(List.of(Message.user("hi")), 10);
        assertEquals(1, hitsA.get(), "冷却期内不应再打首选");
        assertEquals(2, hitsB.get());
    }

    @Test
    @DisplayName("输出截断(finish_reason=length) → 换候选重试")
    void retriesOnTruncation() throws Exception {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();
        String a = startStub(hitsA, 200,
                "data: {\"choices\":[{\"finish_reason\":\"length\","
                        + "\"delta\":{\"content\":\"{partial\"}}]}\n\n"
                        + "data: [DONE]\n\n");
        String b = startStub(hitsB, 200, ok("FULL"));
        FailoverLlmProvider r = router(
                cand("a", a, "m1", 1), cand("b", b, "m2", 2));

        assertEquals("FULL", r.chat(List.of(Message.user("hi")), 10));
        assertEquals(1, hitsA.get());
        assertEquals(1, hitsB.get());
    }

    @Test
    @DisplayName("min-round 升档：第 1 轮排除只许第 2 轮起的强模型")
    void respectsMinRound() throws Exception {
        AtomicInteger hitsStrong = new AtomicInteger();
        AtomicInteger hitsCheap = new AtomicInteger();
        String strong = startStub(hitsStrong, 200, ok("STRONG"));
        String cheap = startStub(hitsCheap, 200, ok("CHEAP"));

        LlmProperties.Candidate c1 = cand("strong", strong, "M-strong", 1);
        c1.setMinRound(2);
        LlmProperties.Candidate c2 = cand("cheap", cheap, "M-cheap", 2);
        FailoverLlmProvider r = router(c1, c2);

        LlmEffort.setRound(1);
        assertEquals("CHEAP", r.chat(List.of(Message.user("hi")), 10));
        assertEquals(0, hitsStrong.get(), "第 1 轮不该动强模型");

        LlmEffort.setRound(2);
        assertEquals("STRONG", r.chat(List.of(Message.user("hi")), 10));
        assertEquals(1, hitsStrong.get());
        LlmEffort.clear();
    }

    @Test
    @DisplayName("规模升档：判定为大活时，第 1 轮就放开 minRound=2 的强模型")
    void largeRequestUnlocksStrongModel() throws Exception {
        AtomicInteger hitsStrong = new AtomicInteger();
        AtomicInteger hitsCheap = new AtomicInteger();
        String strong = startStub(hitsStrong, 200, ok("STRONG"));
        String cheap = startStub(hitsCheap, 200, ok("CHEAP"));

        LlmProperties.Candidate c1 = cand("strong", strong, "M-strong", 1);
        c1.setMinRound(2);
        LlmProperties.Candidate c2 = cand("cheap", cheap, "M-cheap", 2);
        FailoverLlmProvider r = router(c1, c2);

        // 小活：第 1 轮不该动强模型（与 respectsMinRound 同口径）
        LlmEffort.setRound(1);
        LlmEffort.setScale(1);
        assertEquals("CHEAP", r.chat(List.of(Message.user("hi")), 10));
        assertEquals(0, hitsStrong.get(), "小活第 1 轮不该动强模型");

        // 大活：同样第 1 轮，规模把难度档抬到 2 → 强模型直接上
        LlmEffort.setRound(1);
        LlmEffort.setScale(3);
        assertEquals("STRONG", r.chat(List.of(Message.user("hi")), 10));
        assertEquals(1, hitsStrong.get(), "大活第 1 轮就该上强模型");
        assertEquals(1, hitsCheap.get(), "不该重复打便宜候选");

        LlmEffort.clear();
    }

    @Test
    @DisplayName("规模升档：中活不抬档（限量强模型不能被中等需求烧掉）")
    void mediumRequestKeepsCheapFirst() throws Exception {
        AtomicInteger hitsStrong = new AtomicInteger();
        String strong = startStub(hitsStrong, 200, ok("STRONG"));
        String cheap = startStub(new AtomicInteger(), 200, ok("CHEAP"));

        LlmProperties.Candidate c1 = cand("strong", strong, "M-strong", 1);
        c1.setMinRound(2);
        FailoverLlmProvider r = router(
                c1, cand("cheap", cheap, "M-cheap", 2));

        LlmEffort.setRound(1);
        LlmEffort.setScale(2);
        assertEquals("CHEAP", r.chat(List.of(Message.user("hi")), 10));
        assertEquals(0, hitsStrong.get(), "中活第 1 轮仍应先走便宜候选");
        LlmEffort.clear();
    }

    @Test
    @DisplayName("全部候选失败 → 抛出异常（由上层走兜底）")
    void throwsWhenAllFail() throws Exception {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();
        String a = startStub(hitsA, 500, "{\"error\":{}}");
        String b = startStub(hitsB, 503, "{\"error\":{}}");
        FailoverLlmProvider r = router(
                cand("a", a, "m1", 1), cand("b", b, "m2", 2));

        assertThrows(Exception.class,
                () -> r.chat(List.of(Message.user("hi")), 10));
    }

    @Test
    @DisplayName("流式：模型卡住(空闲) → 判死切下一个；仍在吐字则不判死")
    void switchesWhenStreamStalls() throws Exception {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();
        // a：吐一帧就挂住 3 秒；空闲阈值设 1 秒 → 应被判死
        String a = startStallingStub(hitsA, 3000);
        String b = startStub(hitsB, 200, ok("BBBB"));

        LlmProperties.Candidate ca = cand("stall", a, "m1", 1);
        ca.setIdleTimeoutSeconds(1);
        FailoverLlmProvider r = router(ca, cand("ok", b, "m2", 2));

        assertEquals("BBBB", r.chat(List.of(Message.user("hi")), 30));
        assertEquals(1, hitsA.get());
        assertEquals(1, hitsB.get());
    }

    @Test
    @DisplayName("429 两种语义：额度不足(1113)长冷却，真限流(1305)短冷却")
    void quota429CoolsDownLonger() throws Exception {
        AtomicInteger hitsQuota = new AtomicInteger();
        AtomicInteger hitsRate = new AtomicInteger();
        AtomicInteger hitsOk = new AtomicInteger();
        String quota = startStub(hitsQuota, 429,
                "{\"error\":{\"code\":\"1113\","
                        + "\"message\":\"余额不足或无可用资源包,请充值。\"}}");
        String rate = startStub(hitsRate, 429,
                "{\"error\":{\"code\":\"1305\","
                        + "\"message\":\"该模型当前访问量过大\"}}");
        String okSrv = startStub(hitsOk, 200, ok("OK"));

        FailoverLlmProvider r = router(
                cand("quota", quota, "m1", 1),
                cand("rate", rate, "m2", 2),
                cand("ok", okSrv, "m3", 3));

        assertEquals("OK", r.chat(List.of(Message.user("hi")), 20));

        long quotaCool = r.cooldownRemainingSeconds("quota");
        long rateCool = r.cooldownRemainingSeconds("rate");
        assertTrue(quotaCool > 500,
                "额度类 429 应按 10 分钟冷却，实际 " + quotaCool + "s");
        assertTrue(rateCool > 0 && rateCool <= 61,
                "限流类 429 应按 60 秒冷却，实际 " + rateCool + "s");
    }
}
