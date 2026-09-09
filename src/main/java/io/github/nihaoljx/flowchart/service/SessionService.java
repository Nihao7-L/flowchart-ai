package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.ChatMessage;
import io.github.nihaoljx.flowchart.model.GraphJson;
import io.github.nihaoljx.flowchart.model.Session;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 会话记忆服务（任务40：会话记忆与多轮对话）
 *
 * 存储：Redis 优先，内存兜底。
 * - Redis 可用：会话写 Redis（JSON + 滑动 TTL），后端重启后前端带着 sessionId 回来仍能接着改图
 * - Redis 不可用（没启动 / 连不上 / 中途挂掉）：自动降级内存，应用照常工作，只是重启会丢
 * - 降级后每 5 分钟探测一次 Redis，恢复了自动切回
 *
 * 为什么必须做降级？
 * 会话记忆是"锦上添花"的能力，不能因为 Redis 没起就让整个生成功能 500。
 * 这类"外部依赖可失败但不阻断主流程"的设计，就是 graceful degradation（优雅降级）。
 *
 * 窗口策略：
 * - 存：单个会话最多保留 max-messages 条消息（默认 40 条约 20 轮），超出丢最老的，防 Redis 膨胀
 * - 用：拼 refine prompt 时只取最近 WINDOW_SIZE 轮，避免上下文无限变长（滑动窗口）
 * 早期对话摘要属于进阶能力，验收场景（3 轮）用滑动窗口已足够。
 */
@Service
public class SessionService {

    /** 拼进 refine prompt 的最近对话轮数 */
    private static final int WINDOW_SIZE = 10;

    /** 降级后多久再探一次 Redis（毫秒） */
    private static final long PROBE_INTERVAL_MS = 5 * 60 * 1000L;

    private final SessionStore redisStore;
    private final SessionStore memoryStore;
    private final boolean useRedis;
    private final int maxMessages;

    /** 是否已降级到内存 */
    private volatile boolean degraded = false;
    private volatile long degradedAt = 0L;

    public SessionService(@Qualifier("redisSessionStore") SessionStore redisStore,
                          @Qualifier("inMemorySessionStore") SessionStore memoryStore,
                          @Value("${flowai.session.store:redis}") String storeType,
                          @Value("${flowai.session.max-messages:40}") int maxMessages) {
        this.redisStore = redisStore;
        this.memoryStore = memoryStore;
        this.useRedis = !"memory".equalsIgnoreCase(storeType);
        this.maxMessages = maxMessages;
    }

    /** 启动时先探一次 Redis：连不上就直接走内存，避免每次请求都等连接超时 */
    @PostConstruct
    public void init() {
        if (!useRedis) {
            System.out.println("ℹ️  会话存储：内存（flowai.session.store=memory），重启后会话丢失");
            return;
        }
        if (redisStore.ping()) {
            System.out.println("✅ 会话存储：Redis（滑动 TTL，重启不丢）");
        } else {
            degrade(null);
        }
    }

    /** 无 sessionId 时建新会话，返回新 id */
    public String create() {
        String id = newId();
        Session s = new Session(id);
        save(s);
        return id;
    }

    /** 取会话，不存在返回 null（调用方决定建新还是报错） */
    public Session get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            return active().find(sessionId);
        } catch (Exception e) {
            degrade(e);
            return memoryStore.find(sessionId);
        }
    }

    /** 追加一条用户消息（generate 原始需求 / refine 修改指令） */
    public void addUserMessage(String sessionId, String content) {
        Session s = getOrCreate(sessionId);
        s.addMessage(ChatMessage.user(content));
        save(s);
    }

    /** 追加一条 AI 消息，并更新当前图基线 */
    public void addAssistantGraph(String sessionId, String content, GraphJson graph) {
        Session s = getOrCreate(sessionId);
        s.addMessage(ChatMessage.assistant(content, graph));
        if (graph != null) s.setCurrentGraphJson(graph);
        save(s);
    }

    /**
     * 取最近 WINDOW_SIZE 轮里的用户指令，拼成文本给 refine prompt。
     * 早期轮被截断（滑动窗口）；无历史返回空串。
     */
    public String buildHistoryText(String sessionId) {
        Session s = get(sessionId);
        if (s == null || s.getMessages() == null || s.getMessages().isEmpty()) return "";
        List<ChatMessage> msgs = s.getMessages();
        int from = Math.max(0, msgs.size() - WINDOW_SIZE);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < msgs.size(); i++) {
            ChatMessage m = msgs.get(i);
            if ("user".equals(m.role())) {
                sb.append("- ").append(m.content()).append("\n");
            }
        }
        return sb.toString().strip();
    }

    /** 当前实际使用的存储名（日志 / 排查用，降级时会带标记） */
    public String currentStore() {
        if (!useRedis) return "memory";
        return degraded ? "memory(degraded)" : "redis";
    }

    /** 任务46：列出所有会话摘要（id + lastActive），按活跃时间降序 */
    public List<SessionStore.SessionSummary> listAll() {
        try {
            return active().listAll();
        } catch (Exception e) {
            degrade(e);
            return memoryStore.listAll();
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 取会话，取不到就新建。
     * 场景：Redis 里的会话 TTL 过期被清了，但前端还拿着旧 sessionId 来 refine——
     * 这时不该抛异常打断生成，静默开一个新会话即可（用户只表现为"历史记不清了"）。
     */
    private Session getOrCreate(String sessionId) {
        Session s = get(sessionId);
        if (s != null) return s;
        String id = (sessionId == null || sessionId.isBlank()) ? newId() : sessionId;
        Session created = new Session(id);
        save(created);
        return created;
    }

    private void save(Session s) {
        s.trimMessages(maxMessages);
        try {
            active().save(s);
        } catch (Exception e) {
            degrade(e);
            memoryStore.save(s);
        }
    }

    /** 选存储：Redis 优先；已降级则先用内存，到点探一次 Redis */
    private SessionStore active() {
        if (!useRedis) return memoryStore;
        if (degraded) {
            if (System.currentTimeMillis() - degradedAt > PROBE_INTERVAL_MS) {
                if (redisStore.ping()) {
                    degraded = false;
                    System.out.println("✅ Redis 已恢复，会话存储切回 Redis");
                } else {
                    degradedAt = System.currentTimeMillis();  // 没恢复，再等一个周期
                }
            }
            if (degraded) return memoryStore;
        }
        return redisStore;
    }

    private void degrade(Exception e) {
        if (!useRedis) return;
        degradedAt = System.currentTimeMillis();
        if (!degraded) {
            degraded = true;
            System.err.println("⚠️ Redis 不可用，会话存储降级为内存（重启后会话丢失）："
                    + (e == null ? "启动探测失败" : e.getMessage()));
        }
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
