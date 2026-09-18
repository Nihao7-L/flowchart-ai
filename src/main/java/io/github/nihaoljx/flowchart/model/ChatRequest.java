package io.github.nihaoljx.flowchart.model;

/**
 * /api/chat 请求体（v2-11）
 */
public record ChatRequest(
    String sessionId,
    String message
) {
}
