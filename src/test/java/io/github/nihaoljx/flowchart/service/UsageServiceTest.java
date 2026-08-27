package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.config.LlmProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务31测试：验证成本统计
 *
 * 测试不依赖 Spring 容器——手动 new LlmProperties + setPricing 模拟配置，
 * 再 new UsageService，纯单元测试，跑得快。
 */
class UsageServiceTest {

    /** 测试辅助：构造一个配好单价表的 UsageService */
    private UsageService newService(Map<String, Double> pricing) {
        LlmProperties props = new LlmProperties();
        props.setPricing(pricing);
        return new UsageService(props);
    }

    @Test
    void 记录多次调用统计正确() {
        UsageService service = newService(Map.of("test-model", 10.0));
        service.record("test-model", 1000, 2000);  // 3000 token × 10元/百万 = 0.03 元
        service.record("test-model", 500, 500);    // 1000 token = 0.01 元

        Map<String, Object> stats = service.getStats();

        assertEquals(2, stats.get("totalRequests"));
        assertEquals(1500, stats.get("promptTokens"));
        assertEquals(2500, stats.get("completionTokens"));
        assertEquals(4000, stats.get("totalTokens"));
        assertEquals(0.04, (double) stats.get("estimatedCost"), 0.0001); // 0.03 + 0.01
    }

    @Test
    void 未知模型回退默认单价() {
        UsageService service = newService(Map.of("default", 5.0));
        service.record("unknown-model", 1_000_000, 0);  // 100万 token × 5元/百万 = 5 元

        assertEquals(5.0, (double) service.getStats().get("estimatedCost"), 0.0001);
    }

    @Test
    void 没配单价表成本为0() {
        UsageService service = newService(Map.of());
        service.record("any-model", 1000, 1000);

        assertEquals(0.0, (double) service.getStats().get("estimatedCost"), 0.0001);
    }

    @Test
    void 按模型分组统计() {
        UsageService service = newService(Map.of("m1", 10.0, "m2", 20.0));
        service.record("m1", 1000, 0);
        service.record("m1", 1000, 0);
        service.record("m2", 1000, 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byModel =
                (List<Map<String, Object>>) service.getStats().get("byModel");

        assertEquals(2, byModel.size(), "两种模型应分成两组");
    }

    @Test
    void 并发记录不丢数据() throws Exception {
        UsageService service = newService(Map.of("m", 1.0));
        int threads = 20;          // 20 个线程
        int perThread = 50;        // 每个线程记 50 笔
        int expected = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1); // 让所有线程同时开跑
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                for (int j = 0; j < perThread; j++) {
                    service.record("m", 1, 1);
                }
                done.countDown();
            });
        }
        start.countDown();
        done.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(expected, service.getStats().get("totalRequests"),
                "并发写入 1000 笔不应丢失任何一条");
    }
}
