package io.github.nihaoljx.flowchart.critique;

/**
 * 视觉自纠闭环的开关配置。由 {@code AgentLoopService} 从
 * {@code chartflow.visual-feedback.*} 注入。
 *
 * @param enabled    总开关（默认 false —— 关闭时 {@link VisualFeedbackService}
 *                  直接返回空报告，管线零行为变化）
 * @param geometric  几何批判器是否启用（默认 true）
 * @param vision     视觉批判器是否启用（默认 false，需先接渲染+视觉模型）
 */
public record VisualFeedbackConfig(boolean enabled, boolean geometric, boolean vision) {
}
