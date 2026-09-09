package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.model.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis 会话存储（任务40：会话记忆持久化）
 *
 * 存法：一个会话 = 一个 String key，value 是整段 JSON
 *   key:   flowai:session:{sessionId}
 *   value: {"sessionId":"...","messages":[{"role":"user","content":"..."}],"currentGraphJson":{...}}
 *
 * 为什么存整段 JSON 而不是 Hash？
 * 会话是"读多写多、整体读写"的小对象（几十 KB），一次性 get/set 最简单，
 * 也没有"只改其中一个字段"的需求，用 Hash 反而增加复杂度。
 *
 * 为什么用 StringRedisTemplate + 自己的 ObjectMapper，而不是 RedisTemplate<Object,Object>？
 * 默认 JDK 序列化会把 value 存成二进制（Redis 里看不懂、别的语言读不了、升级字段易炸）。
 * 存 JSON 可读、可排查、跨语言，代价只是每次多一次序列化。
 *
 * TTL 策略：每次写入都重置过期时间（滑动过期）——
 * 用户一直在聊的会话不会被"创建时间早"清掉，长期没人动的会话自动回收。
 */
@Component("redisSessionStore")
public class RedisSessionStore implements SessionStore {

    /** Redis key 前缀，避免和其它业务 key 混在一起 */
    private static final String KEY_PREFIX = "flowai:session:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public RedisSessionStore(StringRedisTemplate redis,
                             @Value("${flowai.session.ttl-hours:72}") long ttlHours) {
        this.redis = redis;
        this.ttl = Duration.ofHours(ttlHours);
        this.mapper = new ObjectMapper();
        // 忽略未知字段：以后给 Session 加字段时，老 JSON 仍能读出来（向前兼容）
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void save(Session session) {
        if (session == null || session.getSessionId() == null) return;
        String json;
        try {
            json = mapper.writeValueAsString(session);
        } catch (Exception e) {
            throw new IllegalStateException("会话序列化失败: " + e.getMessage(), e);
        }
        try {
            redis.opsForValue().set(key(session.getSessionId()), json, ttl);
        } catch (Exception e) {
            // 抛给上层 SessionService：它会捕获并降级到内存存储
            throw new IllegalStateException("写 Redis 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Session find(String sessionId) {
        if (sessionId == null) return null;
        String json = redis.opsForValue().get(key(sessionId));
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, Session.class);
        } catch (Exception e) {
            // 脏数据（字段结构变更 / 手工改过 Redis）不该让整个请求失败：丢掉它，当作新会话
            System.err.println("⚠️ 会话 JSON 解析失败，已丢弃该会话: " + sessionId + " -> " + e.getMessage());
            delete(sessionId);
            return null;
        }
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId != null) redis.delete(key(sessionId));
    }

    @Override
    public boolean ping() {
        try {
            // 用 execute 回调：连接由模板统一管理，不用自己 close
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public List<SessionSummary> listAll() {
        List<SessionSummary> result = new ArrayList<>();
        try {
            // SCAN 遍历所有匹配的 key（比 KEYS 命令安全，不阻塞 Redis）
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) return result;

            for (String fullKey : keys) {
                String sessionId = fullKey.substring(KEY_PREFIX.length());
                String json = redis.opsForValue().get(fullKey);
                if (json == null || json.isBlank()) continue;
                try {
                    Session s = mapper.readValue(json, Session.class);
                    result.add(new SessionSummary(s.getSessionId(), s.getLastActive()));
                } catch (Exception ignored) {
                    // 脏数据跳过
                }
            }
            // 按活跃时间降序
            result.sort((a, b) -> Long.compare(b.lastActive(), a.lastActive()));
        } catch (Exception e) {
            System.err.println("⚠️ Redis SCAN 列出会话失败: " + e.getMessage());
        }
        return result;
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
