package io.github.nihaoljx.flowchart.session;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话存储的内存实现（v2-11）
 *
 * <p>开发 / 测试用：重启丢数据，但满足 S1 验收。
 * <p>M5 替换为 Redis 实现，接口不变。
 */
@Component
public class InMemorySessionStore
        implements SessionStore {

    /**
     * 解绑时要从画布侧搬回模型的几何字段
     *
     * <p>{@code points} 是关键那个：解绑后这条线不再能靠两端图形算位置，
     * 必须有自己的路径点，否则模型里就是一条算不出长度的坏箭头。
     */
    private static final List<String> FREED_ARROW_FIELDS =
            List.of("x", "y", "width", "height",
                    "points");

    private final ConcurrentHashMap<String,
            Map<String, Object>> models =
            new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> getCurrentModel(
            String sessionId) {
        return models.get(sessionId);
    }

    @Override
    public void setCurrentModel(
            String sessionId,
            Map<String, Object> model) {
        models.put(sessionId, model);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void updatePositions(
            String sessionId,
            Map<String, Map<String, Double>>
                    positions) {

        // 前端一次同步三类信息，某一类为空是常态（比如只拖了坐标），
        // 老实现没有这道判断，会直接 NPE 打成 500
        if (positions == null
                || positions.isEmpty()) {
            return;
        }

        Map<String, Object> model =
                models.get(sessionId);
        if (model == null) {
            return;
        }

        Object elementsObj =
                model.get("elements");
        if (!(elementsObj
                instanceof List<?> elements)) {
            return;
        }

        for (Object elemObj : elements) {
            if (!(elemObj
                    instanceof Map<?, ?> elem)) {
                continue;
            }
            Object idObj = elem.get("id");
            if (!(idObj instanceof String id)) {
                continue;
            }
            Map<String, Double> pos =
                    positions.get(id);
            if (pos == null) {
                continue;
            }
            if (pos.containsKey("x")) {
                ((Map<String, Object>) elem)
                        .put("x", pos.get("x"));
            }
            if (pos.containsKey("y")) {
                ((Map<String, Object>) elem)
                        .put("y", pos.get("y"));
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public int removeElements(
            String sessionId,
            Collection<String> ids) {

        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Map<String, Object> model =
                models.get(sessionId);
        if (model == null) {
            return 0;
        }
        Object elementsObj =
                model.get("elements");
        if (!(elementsObj
                instanceof List<?> elements)) {
            return 0;
        }

        Set<String> targets = new HashSet<>(ids);
        List<Object> kept = new ArrayList<>();
        for (Object elemObj : elements) {
            String id = idOf(elemObj);
            if (id != null
                    && targets.contains(id)) {
                continue;
            }
            kept.add(elemObj);
        }
        if (kept.size() == elements.size()) {
            return 0;
        }
        // 重建列表而不是就地 removeIf：Jackson 反序列化产物通常可变，
        // 但这里不赌实现类，重建一定安全
        ((Map<String, Object>) model)
                .put("elements", kept);
        return elements.size() - kept.size();
    }

    @Override
    public void setUserElements(
            String sessionId,
            List<Map<String, Object>> userElements) {

        Map<String, Object> model =
                models.get(sessionId);
        if (model == null) {
            return;
        }
        model.put(USER_ELEMENTS_KEY,
                userElements == null
                        ? new ArrayList<>()
                        : new ArrayList<>(
                                userElements));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void releaseBindings(
            String sessionId,
            List<Map<String, Object>> freeArrows) {

        if (freeArrows == null
                || freeArrows.isEmpty()) {
            return;
        }
        Map<String, Object> model =
                models.get(sessionId);
        if (model == null) {
            return;
        }
        Object elementsObj =
                model.get("elements");
        if (!(elementsObj
                instanceof List<?> elements)) {
            return;
        }

        Map<String, Map<String, Object>> byId =
                new HashMap<>();
        for (Map<String, Object> arrow
                : freeArrows) {
            if (arrow == null) {
                continue;
            }
            Object id = arrow.get("id");
            if (id instanceof String s) {
                byId.put(s, arrow);
            }
        }

        for (Object elemObj : elements) {
            if (!(elemObj
                    instanceof Map<?, ?> elem)) {
                continue;
            }
            Object idObj = elem.get("id");
            if (!(idObj instanceof String id)) {
                continue;
            }
            Map<String, Object> arrow =
                    byId.get(id);
            if (arrow == null) {
                continue;
            }
            Map<String, Object> target =
                    (Map<String, Object>) elem;
            // 端点引用必须整条摘掉：留着 start/end 而 x/y 又是用户
            // 拖出来的位置，渲染器仍会按"绑定式"重算几何，等于白拖
            target.remove("start");
            target.remove("end");
            for (String field
                    : FREED_ARROW_FIELDS) {
                Object value = arrow.get(field);
                if (value != null) {
                    target.put(field, value);
                }
            }
        }
    }

    /** 取元素的 id；不是 Map 或没有字符串 id 时返回 null */
    private String idOf(Object elemObj) {
        if (elemObj instanceof Map<?, ?> elem) {
            Object id = elem.get("id");
            if (id instanceof String s) {
                return s;
            }
        }
        return null;
    }
}
