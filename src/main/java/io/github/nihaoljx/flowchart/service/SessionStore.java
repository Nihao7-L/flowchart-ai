package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.Session;

import java.util.List;

/**
 * 会话存储抽象（任务40：会话记忆与多轮对话）
 *
 * 为什么要抽象这一层？
 * 会话记忆 MVP 用的是内存 ConcurrentHashMap（重启即丢）。换成 Redis 时，
 * 如果直接在 SessionService 里写 Redis 调用，就会出现"要么全 Redis、要么全内存"的硬切换。
 * 抽象成接口后：
 * 1. Redis 是默认实现，内存实现作为兜底（Redis 没起 / 中途挂了都能降级，应用不崩）
 * 2. 想换存储（MySQL / 文件）只要再加一个实现类，不动业务代码
 *
 * 两个实现：
 * - RedisSessionStore：JSON 存 Redis，带滑动 TTL（每次对话续命）
 * - InMemorySessionStore：ConcurrentHashMap，进程内，重启丢失
 */
public interface SessionStore {

    /** 写入（覆盖）一个会话 */
    void save(Session session);

    /** 按 id 取会话，不存在返回 null */
    Session find(String sessionId);

    /** 删除会话（如用户主动清空 / 会话过期清理） */
    void delete(String sessionId);

    /** 健康检查：存储是否可用（不可用时 SessionService 会降级） */
    boolean ping();

    /** 存储名，用于日志（"redis" / "memory"） */
    String name();

    /** 任务46：列出所有会话摘要（id + 最后活跃时间），按活跃时间降序 */
    List<SessionSummary> listAll();

    /** 会话摘要（不含完整消息，只含 id 和 lastActive，避免加载全部数据） */
    record SessionSummary(String sessionId, long lastActive) {}
}
