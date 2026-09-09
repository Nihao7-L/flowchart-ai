package io.github.nihaoljx.flowchart.client.stream;

/**
 * 进度事件的"出水口"（任务37）
 *
 * 这是一个函数式接口（@FunctionalInterface）：只定义一个 emit 方法。
 * 谁想接收进度，就写一个 lambda 实现它。Controller 里会把它接到 SseEmitter 上——
 * 这样 Provider 内部只管 ProgressContext.publish(event)，完全不关心"事件最终去哪"。
 *
 * 好处：Provider 代码和"怎么把进度推给浏览器"彻底解耦，以后想改成推到 WebSocket / 日志都行。
 */
@FunctionalInterface
public interface ProgressSink {
    void emit(ProgressEvent event);

    /**
     * 当前请求是否已关闭（客户端断开 / 超时 / 已完成）。
     * 供流式读取循环（OpenAiCompatibleProvider.streamOnce）在每帧之间检查，用于中断生成、停止继续读流。
     * 默认返回 false（老接口 /api/generate 等非 SSE 场景没有"断开"概念）。
     */
    default boolean isClosed() { return false; }
}
