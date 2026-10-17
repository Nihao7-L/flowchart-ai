package io.github.nihaoljx.flowchart.session;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话存储的内存实现
 *
 * 开发 / 测试用：重启丢数据，但满足 S1 验收。
 *
 * M5 替换为 Redis 实现，接口不变。
 */
@Component
public class InMemorySessionStore
        implements SessionStore {

    /**
     * 解绑时要从画布侧搬回模型的几何字段
     *
 * `points` 是关键那个：解绑后这条线不再能靠两端图形算位置，
     * 必须有自己的路径点，否则模型里就是一条算不出长度的坏箭头。
     */
    private static final List<String> FREED_ARROW_FIELDS =
            List.of("x", "y", "width", "height",
                    "points");

    /** 默认 TTL：会话超过这个时长未被访问即逐出 */
    private static final Duration DEFAULT_TTL =
            Duration.ofHours(2);

    private final ConcurrentHashMap<String,
            Map<String, Object>> models =
            new ConcurrentHashMap<>();

    /** 每个会话最近一次被访问的时间戳（epoch millis），用于过期逐出 */
    private final ConcurrentHashMap<String, Long>
            lastAccess = new ConcurrentHashMap<>();

    private final Duration ttl;

    /** 无参便捷构造（默认 TTL）；Spring 走下方 @Autowired 构造 */
    public InMemorySessionStore() {
        this(DEFAULT_TTL);
    }

    @Autowired
    public InMemorySessionStore(
            @Value("${flowchart.session.ttl:PT2H}")
            Duration sessionTtl) {
        this.ttl = sessionTtl;
    }

    @Override
    public Map<String, Object> getCurrentModel(
            String sessionId) {
        stamp(sessionId);
        return models.get(sessionId);
    }

    @Override
    public void setCurrentModel(
            String sessionId,
            Map<String, Object> model) {
        stamp(sessionId);
        models.put(sessionId, model);
        // 惰性逐出：每次写（创建/覆盖会话）都顺带回收过期会话，
        // 避免旧的躺在内存里永不释放（report 1.5 #7）
        purgeExpired();
    }

    /** 记录某会话的最近访问时间 */
    private void stamp(String sessionId) {
        lastAccess.put(sessionId,
                System.currentTimeMillis());
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
        stamp(sessionId);

        // 竞态修复（report 1.5 #2，本类）：读→判断→就地 mutate 原先
        // 在 models.get 的结果上裸做，无任何锁；同 session 并发 PUT
        // 会撕裂这一读改写。改用 models.compute（ConcurrentHashMap
        // 按 key 上锁），把整段读改写放进同一原子临界区。
        models.compute(sessionId, (k, model) -> {
            if (model == null) {
                return null;
            }
            Object elementsObj =
                    model.get("elements");
            if (!(elementsObj
                    instanceof List<?> elements)) {
                return model;
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
            return model;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public int removeElements(
            String sessionId,
            Collection<String> ids) {

        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        stamp(sessionId);

        // 竞态修复（report 1.5 #2，本类）：与 updatePositions 同理，
        // 读→判断→重建原先是 models.get 裸做，无锁；并发 PUT 会撕裂。
        // 改成 compute 原子临界区，并借 int[1] 把删除数传出来。
        int[] removed = {0};
        models.compute(sessionId, (k, model) -> {
            if (model == null) {
                return null;
            }
            Object elementsObj =
                    model.get("elements");
            if (!(elementsObj
                    instanceof List<?> elements)) {
                return model;
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
            if (kept.size() != elements.size()) {
                // 重建列表而不是就地 removeIf：Jackson 反序列化产物通常可变，
                // 但这里不赌实现类，重建一定安全
                ((Map<String, Object>) model)
                        .put("elements", kept);
                removed[0] =
                        elements.size() - kept.size();
            }
            return model;
        });
        return removed[0];
    }

    @Override
    public void setUserElements(
            String sessionId,
            List<Map<String, Object>> userElements) {
        stamp(sessionId);

        // 竞态修复（report 1.5 #2，本类）：同上，compute 原子写。
        models.compute(sessionId, (k, model) -> {
            if (model == null) {
                return null;
            }
            model.put(USER_ELEMENTS_KEY,
                    userElements == null
                            ? new ArrayList<>()
                            : new ArrayList<>(
                                    userElements));
            return model;
        });
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
        stamp(sessionId);

        // 竞态修复（report 1.5 #2，本类）：同上，compute 原子写。
        models.compute(sessionId, (k, model) -> {
            if (model == null) {
                return null;
            }
            Object elementsObj =
                    model.get("elements");
            if (!(elementsObj
                    instanceof List<?> elements)) {
                return model;
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
            return model;
        });
    }

    @Override
    public int purgeExpired() {
        long cutoff = System.currentTimeMillis()
                - ttl.toMillis();
        int removed = 0;
        for (Map.Entry<String, Long> e
                : lastAccess.entrySet()) {
            String sessionId = e.getKey();
            Long seen = e.getValue();
            // 条件删除：只有"快照里的时间戳仍是当前值"才删，
            // 避免把此刻正在被刷新的活跃会话误逐出
            if (seen != null
                    && seen < cutoff
                    && lastAccess.remove(sessionId,
                            seen)) {
                models.remove(sessionId);
                removed++;
            }
        }
        return removed;
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
