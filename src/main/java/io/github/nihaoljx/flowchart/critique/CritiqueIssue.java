package io.github.nihaoljx.flowchart.critique;

/**
 * 一条结构化的「视觉/几何」批判结果。
 *
 * <p>关键：{@link #suggestion()} 永远是 spec 级的改法（拉开间距、加宽、调整走向），
 * 绝不描述像素修补 —— 这是「反馈必须结构性」铁律（D6）的载体。
 */
public record CritiqueIssue(
        String targetId,
        Category category,
        String message,
        String suggestion,
        int severity) {

    public enum Category {
        OVERLAP,        // 节点相互重叠
        OFF_CANVAS,     // 落在画布外（坐标为负）
        LABEL_OVERFLOW, // 标签文字超出框宽
        EDGE_CROSSING,  // 连线交叉
        TINY,           // 节点过小不易读
        OTHER
    }

    public static CritiqueIssue of(String targetId, Category category,
                                   String message, String suggestion, int severity) {
        return new CritiqueIssue(targetId, category, message, suggestion, severity);
    }
}
