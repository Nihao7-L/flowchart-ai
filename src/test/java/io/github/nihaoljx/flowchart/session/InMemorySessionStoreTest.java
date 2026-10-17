package io.github.nihaoljx.flowchart.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySessionStoreTest {

    private InMemorySessionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
    }

    @Test
    void setAndGetModel() {
        Map<String, Object> model = new HashMap<>();
        model.put("elements", "[]");
        store.setCurrentModel("s1", model);

        assertEquals(model,
                store.getCurrentModel("s1"));
    }

    @Test
    void nonExistentReturnsNull() {
        assertNull(
                store.getCurrentModel("no-such"));
    }

    @Test
    void updatePositions() {
        Map<String, Object> elem = new HashMap<>();
        elem.put("id", "n1");
        elem.put("x", 0.0);
        elem.put("y", 0.0);

        Map<String, Object> model = new HashMap<>();
        model.put("elements",
                List.of(elem));
        store.setCurrentModel("s1", model);

        Map<String,
                Map<String, Double>> positions =
                new HashMap<>();
        positions.put("n1",
                Map.of("x", 100.0, "y", 200.0));
        store.updatePositions("s1", positions);

        Map<String, Object> updated =
                store.getCurrentModel("s1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements =
                (List<Map<String, Object>>)
                        updated.get("elements");
        assertEquals(100.0,
                elements.get(0).get("x"));
        assertEquals(200.0,
                elements.get(0).get("y"));
    }

    @Test
    void updatePositionsNoopWhenNoSession() {
        store.updatePositions("no-such",
                Map.of("n1",
                        Map.of("x", 1.0)));
    }

    /**
     * 前端一次同步三类信息，某类为空是常态（只拖了坐标时会送
     * removedIds=[]）。老实现没有这道 null 判断，会 NPE 打成 500。
     */
    @Test
    void updatePositionsToleratesNull() {
        seed();
        store.updatePositions("s1", null);
    }

    @Test
    void removeElementsDropsMatchingIds() {
        seed();
        assertEquals(1,
                store.removeElements("s1",
                        List.of("n1")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements =
                (List<Map<String, Object>>)
                        store.getCurrentModel("s1")
                                .get("elements");
        assertEquals(1, elements.size());
        assertEquals("n2", elements.get(0).get("id"));
    }

    @Test
    void removeElementsIsNoopOnUnknownOrEmpty() {
        seed();
        assertEquals(0, store.removeElements(
                "s1", List.of("nope")));
        assertEquals(0, store.removeElements(
                "s1", List.of()));
        assertEquals(0, store.removeElements(
                "s1", null));
        assertEquals(0, store.removeElements(
                "no-such", List.of("n1")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements =
                (List<Map<String, Object>>)
                        store.getCurrentModel("s1")
                                .get("elements");
        assertEquals(2, elements.size());
    }

    @Test
    void setUserElementsStoresSideChannel() {
        seed();
        store.setUserElements("s1",
                List.of(Map.of(
                        "id", "u1",
                        "type", "arrow")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> userElements =
                (List<Map<String, Object>>)
                        store.getCurrentModel("s1")
                                .get(SessionStore
                                        .USER_ELEMENTS_KEY);
        assertEquals(1, userElements.size());
        assertEquals("u1",
                userElements.get(0).get("id"));

        // null = 清空（不是"忽略"）：用户把画布上的手绘全删了
        store.setUserElements("s1", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cleared =
                (List<Map<String, Object>>)
                        store.getCurrentModel("s1")
                                .get(SessionStore
                                        .USER_ELEMENTS_KEY);
        assertTrue(cleared.isEmpty());
    }

    @Test
    void setUserElementsNoopWhenNoSession() {
        store.setUserElements("no-such",
                List.of(Map.of("id", "u1")));
    }

    /**
     * 拖动一条线时 Excalidraw 会解除它的端点绑定。没有这个方法时，
     * 后端模型里那条线仍然写着绑定 —— 下一轮渲染又把它绑回两端图形
     * 之间，用户拖的那一下白拖（2026-09-16 用户实测："移动线条后
     * 线条就看不到了"）。
     */
    @Test
    void releaseBindingsClearsEndpointsAndTakesGeometry() {
        Map<String, Object> arrow = new HashMap<>();
        arrow.put("id", "a1");
        arrow.put("type", "arrow");
        arrow.put("start", new HashMap<>(
                Map.of("id", "n1")));
        arrow.put("end", new HashMap<>(
                Map.of("id", "n2")));
        Map<String, Object> model = new HashMap<>();
        model.put("elements",
                new java.util.ArrayList<>(
                        List.of(arrow)));
        store.setCurrentModel("s1", model);

        store.releaseBindings("s1", List.of(
                new HashMap<>(Map.of(
                        "id", "a1",
                        "x", 100.0,
                        "y", 250.0,
                        "points", List.of(
                                List.of(0, 0),
                                List.of(300, 0))))));

        assertFalse(arrow.containsKey("start"),
                "端点绑定必须整条摘掉：留着 start/end 而 x/y 又是"
                        + "用户拖出来的位置，渲染器仍会按绑定式重算几何");
        assertFalse(arrow.containsKey("end"));
        assertEquals(100.0, arrow.get("x"));
        assertEquals(250.0, arrow.get("y"));
        assertTrue(arrow.containsKey("points"),
                "解绑后必须有自己的路径点，"
                        + "否则是条算不出长度的坏箭头");
    }

    @Test
    void releaseBindingsIgnoresUnknownIdsAndEmptyInput() {
        seed();
        store.releaseBindings("s1", List.of(
                new HashMap<>(Map.of(
                        "id", "nope", "x", 1.0))));
        store.releaseBindings("s1", List.of());
        store.releaseBindings("s1", null);
        store.releaseBindings("no-such", List.of(
                new HashMap<>(Map.of("id", "n1"))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements =
                (List<Map<String, Object>>)
                        store.getCurrentModel("s1")
                                .get("elements");
        // 未知 id 不能误伤别的元素
        assertEquals(2, elements.size());
    }

    /**
     * 竞态回归（report 1.5 #2）：并发删除同一会话的多个不同元素，
     * 最终必须一个不剩——老实现在 models.get 的结果上裸做读→重建→put，
     * 无锁；并发时两个 removeElements 会各拿一份旧列表，最后一个 put
     * 回写覆盖另一份，被删元素"复活"，总删除数小于请求数。换成
     * compute 原子临界区后不存在丢更新。
     */
    @Test
    void concurrentRemoveElementsLosesNone()
            throws Exception {
        Map<String, Object> model = new HashMap<>();
        java.util.ArrayList<Map<String, Object>> elems =
                new java.util.ArrayList<>();
        int total = 64;
        for (int i = 0; i < total; i++) {
            Map<String, Object> e = new HashMap<>();
            e.put("id", "n" + i);
            elems.add(e);
        }
        model.put("elements", elems);
        store.setCurrentModel("s1", model);

        int threads = 8;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors
                        .newFixedThreadPool(threads);
        try {
            java.util.concurrent.CountDownLatch start =
                    new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<?>> tasks =
                    new java.util.ArrayList<>();
            for (int i = 0; i < total; i++) {
                final String id = "n" + i;
                tasks.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread()
                                .interrupt();
                        throw new RuntimeException(ie);
                    }
                    store.removeElements("s1",
                            java.util.List.of(id));
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : tasks) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> remaining =
                (java.util.List<Map<String, Object>>)
                        store.getCurrentModel("s1")
                                .get("elements");
        assertTrue(remaining.isEmpty(),
                "并发删除 64 个元素后不应有残留，实际剩 "
                        + remaining.size());
    }

    /**
     * TTL 逐出（report 1.5 #7）：会话超过 TTL 未被访问就被
     * purgeExpired 回收，读不到旧模型。
     */
    @Test
    void expiredSessionIsPurgedAndReadableAgain() {
        store = new InMemorySessionStore(
                java.time.Duration.ofMillis(50));

        Map<String, Object> model = new HashMap<>();
        model.put("elements", "[]");
        store.setCurrentModel("s-ttl", model);
        assertEquals(model,
                store.getCurrentModel("s-ttl"));

        // 等等让会话过期（50ms TTL）
        try {
            Thread.sleep(80);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }

        assertTrue(store.purgeExpired() >= 1,
                "过期会话应被逐出");
        assertNull(store.getCurrentModel("s-ttl"),
                "逐出后旧模型不可再读");
    }

    /** TTL 未到时 active 会话不被误逐出 */
    @Test
    void freshSessionSurvivesPurge() {
        store = new InMemorySessionStore(
                java.time.Duration.ofHours(1));

        Map<String, Object> model = new HashMap<>();
        model.put("elements", "[]");
        store.setCurrentModel("s-fresh", model);

        assertEquals(0, store.purgeExpired(),
                "未过期活跃会话不应被逐出");
        assertNotNull(
                store.getCurrentModel("s-fresh"));
    }

    /** 两个节点的会话模型：删除 / 旁路用例的公共底座 */
    private void seed() {
        Map<String, Object> n1 = new HashMap<>();
        n1.put("id", "n1");
        Map<String, Object> n2 = new HashMap<>();
        n2.put("id", "n2");
        Map<String, Object> model = new HashMap<>();
        model.put("elements", new java.util.ArrayList<>(
                List.of(n1, n2)));
        store.setCurrentModel("s1", model);
    }
}
