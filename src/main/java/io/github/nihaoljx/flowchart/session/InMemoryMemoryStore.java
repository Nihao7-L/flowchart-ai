package io.github.nihaoljx.flowchart.session;

import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MemoryStore 的内存实现
 *
 * 每会话一个有界队列（新的进、旧的出）——issue 历史只对"近期细化"
 * 有价值，无限累积只会让记忆变成噪音。进程内存实现：重启即忘，
 * 与冷却表的取舍一致；持久化归 m5。
 */
public class InMemoryMemoryStore implements MemoryStore {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    InMemoryMemoryStore.class);

    /** 每会话最多保留多少条历史 */
    private static final int MAX_ENTRIES_PER_SESSION = 50;

    /** 最多保留多少个会话（防长内存泄漏；超时淘汰最旧的写入者） */
    private static final int MAX_SESSIONS = 1_000;

    private final Map<String, Deque<MemoryStore.IssueEntry>> store =
            new ConcurrentHashMap<>();

    @Override
    public void recordIssues(
            String sessionId,
            List<ValidationIssue> issues) {
        if (sessionId == null || sessionId.isBlank()
                || issues == null || issues.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        store.compute(sessionId, (k, deque) -> {
            if (deque == null) {
                if (store.size() >= MAX_SESSIONS) {
                    // 会话数到顶：拒绝新会话入册（记录日志即可，
                    // 记忆缺失只影响"避坑提示"，不影响正确性）
                    LOG.warn("MemoryStore 会话数已达上限 {}，"
                            + "本会话不入册", MAX_SESSIONS);
                    return null;
                }
                deque = new ArrayDeque<>();
            }
            // 竞态修复（report 1.5 #1）：compute 只持 ConcurrentHashMap
            // 的 bin 锁，而 recent() 用 synchronized(deque) 迭代 —— 两个
            // 不同监视器，并发写/读会抛 ConcurrentModificationException
            // 或读到撕裂状态。这里对齐成同一把 deque 监视器：写与读互斥。
            // 锁序（bin → deque）单向，recent 只取 deque 不再取 bin，无死锁。
            synchronized (deque) {
                for (ValidationIssue issue : issues) {
                    deque.addLast(new MemoryStore.IssueEntry(
                            issue.field(), issue.reason(),
                            issue.hint(), now));
                }
                while (deque.size() > MAX_ENTRIES_PER_SESSION) {
                    deque.pollFirst();
                }
            }
            return deque;
        });
    }

    @Override
    public List<MemoryStore.IssueEntry> recent(
            String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        Deque<MemoryStore.IssueEntry> deque = store.get(sessionId);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        List<MemoryStore.IssueEntry> snapshot;
        synchronized (deque) {
            snapshot = new ArrayList<>(deque);
        }
        // 新→旧
        java.util.Collections.reverse(snapshot);
        if (limit > 0 && snapshot.size() > limit) {
            return snapshot.subList(0, limit);
        }
        return List.copyOf(snapshot);
    }

    @Override
    public void clear(String sessionId) {
        if (sessionId != null) {
            store.remove(sessionId);
        }
    }
}
