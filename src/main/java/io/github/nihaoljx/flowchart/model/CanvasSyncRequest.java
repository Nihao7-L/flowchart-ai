package io.github.nihaoljx.flowchart.model;

import java.util.List;
import java.util.Map;

/**
 * 画布现状同步请求体（v2-34）
 *
 * <p><b>为什么不能只送坐标：</b>在这之前，前端回写后端的通道只有一条，
 * 而且只送 x / y。于是两件事后端完全不知情（2026-09-16 用户实测）：
 * <ul>
 *   <li>用户删掉了哪些元素 —— 被删元素在后端模型里**还在**，
 *       下一轮生成被 LLM 原样输出（用户看到"删掉的线又回来了"）；</li>
 *   <li>用户手绘了哪些元素 —— 它们从来没进过后端，LLM 不知道
 *       那里已经有线，于是再画一条，同一对图形之间出现两条线。</li>
 * </ul>
 *
 * <p><b>为什么还要送"解绑"（2026-09-16 追加）：</b>用户在画布上拖动一条
 * 连线时，Excalidraw 会解除它的端点绑定（实测：{@code n1 -> n2} 变成
 * {@code 无 -> 无}，元素本身还在）。这件事此前没有任何通道回写后端，
 * 后端模型里那条线仍然写着绑定 —— 下一轮渲染又把它绑回两端图形之间，
 * 用户拖的那一下白拖。用户原话："移动线条后线条就看不到了"。
 *
 * @param positions     坐标变更: elementId → {x, y}
 * @param removedIds    用户删掉的元素 id（后端模型里有、画布上已不存在）。
 *                      含"连坐删除"：Excalidraw 删图形时不会连带删绑定它的
 *                      箭头，所以这些孤儿连线也要一并算进来
 * @param userElements  用户手绘元素的轻量描述。只用于让 LLM 知道
 *                      "这里已经有东西了"，不会进入 elements
 * @param unboundArrows 模型里写着绑定、画布上已解绑的连线，含它们的完整
 *                      几何（x / y / points）。光说"不绑定了"不够 ——
 *                      解绑后它们得靠自己的坐标定位，否则模型里会留下
 *                      一条既无绑定又无路径的坏箭头
 */
public record CanvasSyncRequest(
    Map<String, Map<String, Double>> positions,
    List<String> removedIds,
    List<Map<String, Object>> userElements,
    List<Map<String, Object>> unboundArrows
) {
}
