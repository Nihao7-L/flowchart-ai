package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.GraphJson;
import io.github.nihaoljx.flowchart.model.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务40：会话记忆 Redis 持久化测试
 *
 * 为什么手工装配而不 @SpringBootTest？
 * 本测试只想验证"会话真的写进了 Redis、能读回来、TTL 生效、超长会裁剪"，
 * 启完整 Spring 上下文会顺带初始化沙箱等一堆不相关的东西，慢且引入无关失败点。
 * 这里直接 new 出 RedisSessionStore + SessionService，依赖只有本机 Redis。
 *
 * 前置：本地 Redis 已启动（redis-server，默认 6379）。没启动则测试失败——这是有意的，
 * 会话持久化本来就是这次要验证的能力。
 */
class SessionServiceRedisTest {

    private StringRedisTemplate redis;
    private RedisSessionStore redisStore;
    private SessionService sessionService;

    /** 测试期间创建的会话 id，跑完统一清理，不污染真实会话 */
    private final Set<String> created = new CopyOnWriteArraySet<>();

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redisStore = new RedisSessionStore(redis, 72);
        sessionService = new SessionService(redisStore, new InMemorySessionStore(), "redis", 40);
    }

    @AfterEach
    void tearDown() {
        for (String id : created) {
            redis.delete("flowai:session:" + id);
        }
        created.clear();
    }

    /** 核心：写进去的会话能原样读回来（含图基线），且确实落在 Redis 而不是内存 */
    @Test
    void sessionShouldBePersistedToRedis() {
        String id = sessionService.create();
        created.add(id);

        sessionService.addUserMessage(id, "生成登录流程");
        GraphJson g = new GraphJson();
        g.setTitle("登录流程");
        sessionService.addAssistantGraph(id, "生成「flowchart」", g);

        // 1) 业务侧能读回
        Session s = sessionService.get(id);
        assertNotNull(s, "会话应能从 Redis 读回");
        assertEquals(2, s.getMessages().size(), "应记下 1 条用户指令 + 1 条 AI 产出");
        assertNotNull(s.getCurrentGraphJson());
        assertEquals("登录流程", s.getCurrentGraphJson().getTitle());

        // 2) 确实写进了 Redis（不是只停在内存里）
        String raw = redis.opsForValue().get("flowai:session:" + id);
        assertNotNull(raw, "Redis 里应有 flowai:session:{id} 这个 key");
        assertTrue(raw.contains("登录流程"), "Redis 里存的应是 JSON，且包含图标题");

        // 3) TTL 已设置（72h 滑动过期）
        Long ttl = redis.getExpire("flowai:session:" + id);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "会话应带过期时间，避免死会话永久占内存，实际 ttl=" + ttl);
    }

    /** 超长会话要被裁剪：最多 40 条消息，保最近的那批 */
    @Test
    void messagesShouldBeTrimmedToMax() {
        String id = sessionService.create();
        created.add(id);

        for (int i = 1; i <= 50; i++) {
            sessionService.addUserMessage(id, "第" + i + "条指令");
        }

        Session s = sessionService.get(id);
        assertNotNull(s);
        assertEquals(40, s.getMessages().size(), "超过 40 条应丢弃最老的");
        assertEquals("第50条指令", s.getMessages().get(39).content(), "应保留最新的那条");
    }

    /** 历史文本只拼用户指令，且只取最近 10 条（滑动窗口，防止 prompt 无限变长） */
    @Test
    void historyTextShouldBeWindowed() {
        String id = sessionService.create();
        created.add(id);

        for (int i = 1; i <= 15; i++) {
            sessionService.addUserMessage(id, "指令" + i);
        }

        String history = sessionService.buildHistoryText(id);
        assertFalse(history.contains("指令1\n"), "窗口外的早期指令应被截掉");
        assertTrue(history.contains("指令15"), "最近的指令必须在窗口内");
        assertEquals(10, history.lines().count(), "窗口大小应为 10 条");
    }

    /** 容错：Redis 里没有这个会话（TTL 过期被清）时，不能抛异常打断生成 */
    @Test
    void unknownSessionShouldReturnNull() {
        assertNull(sessionService.get("not-exist-session-id"));
        assertDoesNotThrow(() -> sessionService.buildHistoryText("not-exist-session-id"));
    }
}
