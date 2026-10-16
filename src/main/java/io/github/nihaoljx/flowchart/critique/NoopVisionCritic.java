package io.github.nihaoljx.flowchart.critique;

import java.util.List;
import java.util.Map;

/**
 * 视觉批判器的默认空实现：不产出任何问题，保证「视觉反馈关闭」时管线行为与
 * 改造前完全一致。等对接了真实渲染 + 视觉模型后，用真正的 {@link VisionCritic}
 * 实现替换即可。
 */
public final class NoopVisionCritic implements VisionCritic {

    @Override
    public List<CritiqueIssue> critique(List<Map<String, Object>> elements,
                                        String userIntent) {
        return List.of();
    }
}
