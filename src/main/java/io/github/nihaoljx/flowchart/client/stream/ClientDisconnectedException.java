package io.github.nihaoljx.flowchart.client.stream;

/**
 * 客户端断开连接（或超时）——用于中断正在进行的流式生成。
 *
 * 当用户在生成中点「停止」、浏览器 abort 了 SSE 连接，后端 SseEmitter 会触发
 * onError/onCompletion，把 closed 置 true。OpenAiCompatibleProvider.streamOnce 的
 * 流式读取循环每读一帧就检查 ProgressContext.isClosed()，一旦发现断开就抛出本异常，
 * 立刻停止继续读取 LLM 流、关闭响应体、停止解析/渲染，避免白烧 token。
 *
 * 它是 RuntimeException（非受检）：流式读取、重试循环、上层调用都不必显式声明，
 * 由 Controller 在最外层 catch 并静默结束。
 */
public class ClientDisconnectedException extends RuntimeException {
    public ClientDisconnectedException() {
        super("客户端已断开连接，中断生成");
    }
}
