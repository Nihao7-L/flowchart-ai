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
}
