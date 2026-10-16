package io.github.nihaoljx.flowchart.critique;

import java.util.List;
import java.util.Map;

/**
 * 图批判性接口：输入布局后的 Excalidraw 元素（{@code List<Map>}，
 * 键含 x/y/width/height/type/text/label/startBinding/endBinding），
 * 输出结构化问题清单。
 *
 * <p>实现可以是确定性的（几何，见 {@link GeometricCritic}），也可以是基于
 * 渲染位图的视觉模型（见 {@link VisionCritic}）。两者都返回同一种
 * {@link CritiqueIssue}，便于汇总。
 */
public interface DiagramCritic {
    List<CritiqueIssue> critique(List<Map<String, Object>> elements, String userIntent);
}
