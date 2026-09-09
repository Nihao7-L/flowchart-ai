package io.github.nihaoljx.flowchart.client.stream;

/**
 * 进度上下文（任务37核心）—— 用 ThreadLocal 做"请求级隔离"
 *
 * 为什么用 ThreadLocal？
 * - 后端是多线程处理并发请求的，每个请求跑在不同线程上。
 * - 我们不想把 SseEmitter 当参数从 Controller 一层层传到最底层的 OpenAiCompatibleProvider
 *   （那样要改一堆方法签名，侵入性太强）。
 * - 用 ThreadLocal 把"当前线程的进度出水口"挂起来：Provider 内部只调
 *   ProgressContext.publish(event)，就能把事件发到"当前这个请求对应的 SSE 流"。
 * - 不同请求的线程拿到的是各自的 sink，互不串台。
 *
 * 关键纪律：
 * - 请求开始时 Controller 调 setSink 挂上出水口；
 * - 请求结束（finally）必须调 clear() 摘掉，否则线程池复用会串数据。
 * - 没挂出水口时（比如老接口 /api/generate 没设），publish 是 no-op，安全。
 */
public final class ProgressContext {

    private static final ThreadLocal<ProgressSink> SINK = new ThreadLocal<>();

    private ProgressContext() {}

    /** 挂上当前线程的进度出水口（由 Controller 在请求开始时调用） */
    public static void setSink(ProgressSink sink) {
        SINK.set(sink);
    }

    /** 摘掉出水口（请求结束必须调用，防止线程复用串数据） */
    public static void clear() {
        SINK.remove();
    }

    /** 当前请求是否已关闭（客户端断开/超时/已完成）——供流式读取循环检查，用于中断生成 */
    public static boolean isClosed() {
        ProgressSink sink = SINK.get();
        return sink != null && sink.isClosed();
    }

    /** 发布一个事件：有出水口就推，没有就当没发生（no-op） */
    public static void publish(ProgressEvent event) {
        ProgressSink sink = SINK.get();
        if (sink != null) {
            try {
                sink.emit(event);
            } catch (Exception ignore) {
                // 进度事件是"尽力而为"的附属信息：流已关闭（done/error 之后）还迟到的事件直接丢弃，
                // 绝不允许它把主流程或错误处理炸掉（IllegalStateException: emitter already completed）
            }
        }
    }

    /** 便捷方法：只推一条信息日志 */
    public static void publish(String type, String message) {
        publish(ProgressEvent.info(message));
    }

    /** 便捷方法：带类型的事件 + 模型名 */
    public static void publish(String type, String message, String provider) {
        publish(ProgressEvent.of(type, message, provider));
    }
}
