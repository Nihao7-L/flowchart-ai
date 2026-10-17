package io.github.nihaoljx.flowchart.session;

import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class InMemoryMemoryStoreTest {

    private static ValidationIssue issue(String field, String reason) {
        return new ValidationIssue(field, reason, "hint");
    }

    @Test
    @DisplayName("按会话累积；recent 返回新→旧；limit 生效")
    void recordsAndReturnsNewestFirst() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.recordIssues("s1", List.of(
                issue("a", "第一轮问题一"),
                issue("b", "第一轮问题二")));
        store.recordIssues("s1", List.of(
                issue("c", "第二轮问题三")));

        List<MemoryStore.IssueEntry> all = store.recent("s1", 0);
        assertEquals(3, all.size());
        assertEquals("c", all.get(0).field(), "最新一轮排最前");
        assertEquals("a", all.get(2).field(), "最早一轮排最后");

        List<MemoryStore.IssueEntry> top2 = store.recent("s1", 2);
        assertEquals(2, top2.size());
        assertEquals("c", top2.get(0).field());
    }

    @Test
    @DisplayName("会话之间互相隔离")
    void sessionsAreIsolated() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.recordIssues("s1", List.of(issue("a", "s1 的问题")));
        store.recordIssues("s2", List.of(issue("b", "s2 的问题")));

        assertEquals(1, store.recent("s1", 0).size());
        assertEquals("a", store.recent("s1", 0).get(0).field());
        assertEquals("b", store.recent("s2", 0).get(0).field());
    }

    @Test
    @DisplayName("空入参一律忽略：null / 空白会话、空 issue 列表")
    void ignoresBlankInput() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.recordIssues(null, List.of(issue("a", "x")));
        store.recordIssues("  ", List.of(issue("a", "x")));
        store.recordIssues("s1", List.of());
        store.recordIssues("s1", null);

        assertTrue(store.recent("s1", 0).isEmpty());
        assertTrue(store.recent(null, 0).isEmpty());
    }

    @Test
    @DisplayName("每会话有界：超过上限丢最旧的")
    void boundedPerSession() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        for (int i = 0; i < 60; i++) {
            store.recordIssues("s1", List.of(
                    issue("f" + i, "问题 " + i)));
        }
        List<MemoryStore.IssueEntry> all = store.recent("s1", 0);
        assertEquals(50, all.size(), "队列有界");
        assertEquals("f59", all.get(0).field(), "最新的还在");
        assertEquals("f10", all.get(all.size() - 1).field(),
                "最旧的 10 条被挤掉");
    }

    @Test
    @DisplayName("clear 清空指定会话，不影响其他会话")
    void clearOnlyTargetSession() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.recordIssues("s1", List.of(issue("a", "x")));
        store.recordIssues("s2", List.of(issue("b", "y")));
        store.clear("s1");

        assertTrue(store.recent("s1", 0).isEmpty());
        assertEquals(1, store.recent("s2", 0).size());
    }

    @Test
    @DisplayName("并发写/读同一会话不再抛 ConcurrentModificationException（report 1.5 #1 回归）")
    void concurrentWriteReadDoesNotThrow()
            throws Exception {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        int writers = 8;
        ExecutorService pool =
                Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new java.util.ArrayList<>();
            for (int w = 0; w < writers; w++) {
                final int who = w;
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 300; i++) {
                        store.recordIssues("s1", List.of(
                                issue("f" + who, "问题 " + i)));
                        // 读方与写方并发：old 实现在这里可能抛
                        // ConcurrentModificationException
                        store.recent("s1", 8);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : tasks) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // 队列必须仍有界
        List<MemoryStore.IssueEntry> all = store.recent("s1", 0);
        assertTrue(all.size() <= 50, "并发写后队列仍须有界: "
                + all.size());
        assertTrue(all.size() > 0, "并发写后不应为空");
    }
}
