package io.github.nihaoljx.flowchart.model;

/**
 * SSE 事件
 *
 * 前端按 type 分发：thinking / validation / result / error。
 *
 * 后端每一步都推，前端逐条渲染进度。
 */
public record ChatEvent(
    String type,
    String data
) {
    public static ChatEvent of(
            String type, String data) {
        return new ChatEvent(type, data);
    }
}
