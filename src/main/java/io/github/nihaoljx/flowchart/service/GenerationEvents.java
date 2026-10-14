package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.llm.ToolCall;
import io.github.nihaoljx.flowchart.model.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE 事件的统一出口
 *
 * 生成过程对前端的一切输出（过程提示 / 校验结果 / 图 / 错误）
 * 都从这里走，集中处理"通道已死"的容错：发送失败只记日志、
 * 绝不往外抛 —— 否则整个生成线程裸崩，连收尾日志都留不下来。
 */
public final class GenerationEvents {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    GenerationEvents.class);

    private GenerationEvents() {
    }

    public static void sendEvent(
            SseEmitter emitter,
            String type,
            String data) {
        try {
            emitter.send(
                    ChatEvent.of(type, data));
        } catch (IOException e) {
            LOG.warn("SSE 发送失败: {}",
                    e.getMessage());
        } catch (IllegalStateException e) {
            // 通道已结束时（连接超时被容器掐断、或客户端已断开）
            // emitter.send 抛的是 IllegalStateException 而非 IOException。
            // 这不是"发送失败"而是"已经没人收了"——继续往外抛会让
            // 整个生成线程裸崩。
            LOG.warn("SSE 通道已结束，跳过本次推送: {}",
                    e.getMessage());
        }
    }

    public static void sendError(
            SseEmitter emitter,
            String message) {
        sendEvent(emitter, "error", message);
        try {
            // 这里必须用 complete()，不能用 completeWithError()。
            //
            // 错误内容已经作为 error 事件推给客户端了，通道层再"报错"只会让
            // 容器去走 ERROR dispatch —— 而响应头此时已经是
            // Content-Type: text/event-stream，容器找不到能把错误体写成 SSE
            // 的消息转换器，实测抛 HttpMessageNotWritableException。
            // 后果是响应既不正常收尾也不断开：前端 reader.read() 永远等不到
            // done，输入框被禁用、整个界面卡死，只能刷新页面。
            emitter.complete();
        } catch (IllegalStateException e) {
            LOG.warn("SSE 通道已结束，无法收尾: {}",
                    e.getMessage());
        }
    }

    /** 工具参数摘要（SSE 文案用）：单行、有界，超长截断 */
    public static String briefArgs(ToolCall call) {
        if (call.arguments() == null) {
            return "";
        }
        String text = call.arguments().toString()
                .replace('\n', ' ');
        return text.length() <= 80
                ? text : text.substring(0, 80) + "…";
    }
}
