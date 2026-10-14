package io.github.nihaoljx.flowchart.model;

/**
 * /api/chat 请求体
 */
public record ChatRequest(
    String sessionId,
    String message
) {
}
