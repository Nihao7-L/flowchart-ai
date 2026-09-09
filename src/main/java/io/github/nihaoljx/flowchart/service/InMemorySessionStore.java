package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.Session;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话存储（任务40 的兜底实现）
 *
 * 用途：
 * 1. flowai.session.store=memory 时的主存储
 * 2. Redis 不可用（没启动 / 连不上 / 中途挂掉）时的降级存储
 *
 * 局限：进程内有效，重启即丢——这也是为什么要上 Redis。
 */
@Component("inMemorySessionStore")
public class InMemorySessionStore implements SessionStore {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(Session session) {
        if (session == null || session.getSessionId() == null) return;
        sessions.put(session.getSessionId(), session);
    }

    @Override
    public Session find(String sessionId) {
        return sessionId == null ? null : sessions.get(sessionId);
    }

    @Override
    public void delete(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    @Override
    public boolean ping() {
        return true;   // 内存永远可用
    }

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public List<SessionSummary> listAll() {
        List<SessionSummary> result = new ArrayList<>();
        for (Session s : sessions.values()) {
            result.add(new SessionSummary(s.getSessionId(), s.getLastActive()));
        }
        result.sort((a, b) -> Long.compare(b.lastActive(), a.lastActive()));
        return result;
    }
}
