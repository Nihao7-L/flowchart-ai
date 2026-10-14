package io.github.nihaoljx.flowchart.session;

import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;

import java.util.List;

/**
 * 跨轮记忆层：按会话累积"这个 session 踩过什么 issue"
 *
 * 场景本身已随会话持久化（见 SessionStore），这里只补齐另一半 ——
 * 校验结论的历史。没有它，每次生成对"上一轮刚犯过的错"毫无印象：
 * 同一个会话里连续细化时，同一类 issue（比如"箭头既给 start/end
 * 又给 points"）会一轮一轮重复出现，LLM 每次都要从零发现。
 *
 * 消费方式（m5 接管）：把 recent(...) 的内容作为"历史高频问题"
 * 注入修正提示词，让模型显式避开已知的坑。本接口先落"记录"
 * 这一半 —— 记录本身不打扰既有提示词管线，注入策略与配额
 * 由后续子计划决定。
 *
 * 内存实现由本次交付，持久化归 m5（与 SessionStore 同节奏）。
 */
public interface MemoryStore {

    /** 单条历史 issue 的轻量记录（时间戳用于排序与过期清理） */
    record IssueEntry(String field, String reason,
                      String hint, long at) {
    }

    /**
     * 记录一轮校验的 issue 历史
     *
     * @param sessionId 会话 ID；null / 空白视为无状态调用，忽略
     * @param issues    本轮校验结论；null / 空列表视为无操作
     */
    void recordIssues(String sessionId,
                      List<ValidationIssue> issues);

    /**
     * 读某会话最近的 issue 历史（新→旧）
     *
     * @param sessionId 会话 ID；无历史返回空列表（不返回 null）
     * @param limit     最多返回几条；<= 0 视为不限制
     */
    List<IssueEntry> recent(String sessionId, int limit);

    /** 清空某会话的历史（测试与"重新开始"场景用） */
    void clear(String sessionId);
}
