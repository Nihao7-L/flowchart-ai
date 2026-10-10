package io.github.nihaoljx.flowchart.context;

import io.github.nihaoljx.flowchart.graph.ValidationIssue;

import java.util.List;

/**
 * 组装入参（v2-41）
 *
 * <p>这里只放**本期真正要用**的字段。将来 M2(RAG) / M4(真 ReAct) 才需要的
 * 历史 / 记忆 / 工具结果，收进 {@link Extras} 子记录 —— 这样主签名不会随着
 * 功能增加而膨胀到十个参数，将来加东西也不用改所有调用点。
 *
 * @param sessionId        会话 ID（用于回放落盘命名；可为 null）
 * @param userText         用户在输入框里的原始需求
 * @param currentSceneJson 会话当前模型的 JSON（新建图时传 null）
 * @param editMode         是否编辑模式（决定 user 块是否带"当前场景 + 修改规则"）
 * @param round            这一组消息**将要用于**的轮次：1 = 首轮生成，&gt;1 = 修正轮
 * @param issues           上一轮校验失败的 issues（首轮传 null / 空）
 * @param promptVersion    提示词版本号（写入回放日志与生成日志，供 M14 按版本对照）
 * @param extras           预留区（本期恒为 {@link Extras#EMPTY}）
 */
public record AssembleInput(
        String sessionId,
        String userText,
        String currentSceneJson,
        boolean editMode,
        int round,
        List<ValidationIssue> issues,
        String promptVersion,
        Extras extras) {

    /**
     * 预留区：本期恒为 {@link #EMPTY}，M2 / M4 接入时才填。
     *
     * @param history     历史轮次（role=assistant 的内容，M4 用）
     * @param memory      检索到的记忆 / 资料（M2 RAG 用）
     * @param toolResults 工具返回（M4 用）
     */
    public record Extras(
            List<String> history,
            List<String> memory,
            List<String> toolResults) {

        /** 本期统一用这个空值 */
        public static final Extras EMPTY =
                new Extras(List.of(), List.of(), List.of());
    }

    /**
     * 便捷工厂：本期调用点只写 7 个参数，预留区自动填空。
     *
     * @param round 这一组消息将要用于的轮次（1 = 首轮；&gt;1 = 修正轮，
     *              修正轮才会把 issues 追加到 user 块末尾）
     */
    public static AssembleInput of(
            String sessionId,
            String userText,
            String currentSceneJson,
            boolean editMode,
            int round,
            List<ValidationIssue> issues,
            String promptVersion) {
        return new AssembleInput(
                sessionId, userText, currentSceneJson,
                editMode, round, issues, promptVersion,
                Extras.EMPTY);
    }
}
