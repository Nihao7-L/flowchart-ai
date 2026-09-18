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
