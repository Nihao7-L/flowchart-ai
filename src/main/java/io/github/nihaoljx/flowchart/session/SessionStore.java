package io.github.nihaoljx.flowchart.session;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 会话存储接口（v2-11）
 *
 * <p>后端持有 IR 唯一真相源（ADR-2），
 * 前端 Excalidraw 只是渲染镜像。
 * <p>内存实现由 M1 交付，Redis 持久化归 M5。
 */
public interface SessionStore {

    /**
     * 会话模型里"用户手绘元素"的旁路字段名
     *
     * <p>单独存、不混进 {@code elements}：它是**给 LLM 看的避重信息**
     * （"用户已经手工画了这些，别再重复画"），不是模型的组成部分 ——
     * 混进去会被 scene.schema.json 的 additionalProperties 拦下。
     */
    String USER_ELEMENTS_KEY = "_userElements";

    /**
     * 获取当前图模型（全量 elements JSON）
     *
     * @param sessionId 会话 ID
     * @return 模型 Map，不存在返回 null
     */
    Map<String, Object> getCurrentModel(
            String sessionId);

    /**
     * 保存/覆盖当前图模型
     */
    void setCurrentModel(
            String sessionId,
            Map<String, Object> model);

    /**
     * 增量更新坐标（用户拖拽后调用）
     *
     * @param sessionId 会话 ID
     * @param positions 坐标变更:
     *   elementId → {x, y}
     */
    void updatePositions(
            String sessionId,
            Map<String, Map<String, Double>>
                    positions);

    /**
     * 删除元素（用户在前端删掉后调用，v2-34）
     *
     * <p>没有这个方法时，用户在前端的删除对后端是**隐形**的：
     * 被删元素留在模型里，下一轮生成原样带回来。
     *
     * @param sessionId 会话 ID
     * @param ids       要删除的元素 id；null / 空视为无操作
     * @return 实际删掉的元素个数
     */
    int removeElements(
            String sessionId,
            Collection<String> ids);

    /**
     * 覆盖"用户手绘元素"旁路（v2-34）
     *
     * @param sessionId    会话 ID
     * @param userElements 用户手绘元素的轻量描述；null 视为清空
     */
    void setUserElements(
            String sessionId,
            List<Map<String, Object>> userElements);

    /**
     * 解除连线绑定并回写几何（v2-34）
     *
     * <p>用户在画布上拖动一条线时，Excalidraw 会解除它的端点绑定
     * （实测：{@code n1 -> n2} 变成 {@code 无 -> 无}，元素本身还在、
     * 位置跟着鼠标走）。没有这个方法时，后端模型里那条线仍然写着绑定 ——
     * 下一轮渲染又把它绑回两端图形之间，用户拖的那一下白拖
     * （2026-09-16 用户反馈："移动线条后线条就看不到了"）。
     *
     * <p>只摘掉 {@code start} / {@code end} 还不够：解绑后这条线失去
     * "按两端图形算位置"的能力，必须有自己的几何，否则模型里留下一条
     * 既没有绑定、又没有路径的坏箭头（渲染时算不出长度，会污染整张图的
     * 取景边界）。所以几何要一并从画布侧搬回来。
     *
     * @param sessionId  会话 ID
     * @param freeArrows 已解绑的连线，每项含 id 与几何
     *                   （x / y / width / height / points）；
     *                   null / 空视为无操作
     */
    void releaseBindings(
            String sessionId,
            List<Map<String, Object>> freeArrows);
}
