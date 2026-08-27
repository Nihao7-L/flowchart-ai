package io.github.nihaoljx.flowchart.client.stream;

import java.util.Map;

/**
 * 一次进度事件的载体（任务37）
 *
 * 类比：就像物流的"状态推送"——下单( provider_call )、出库失败重试( retry )、
 * 换仓( provider_switch )、签收( token_usage )。每个事件带一个 type 让前端决定怎么渲染。
 *
 * 字段：
 * - type     事件类型，如 provider_call / retry / provider_switch / provider_exhausted / cache_hit / token_usage / info
 * - message  给人看的一句话（直接进进度日志）
 * - provider 触发事件的模型名（mimo / kimi），可为 null
 * - data     额外结构化数据（如 token 用量、切换的 from/to），可为 null
 */
public record ProgressEvent(String type, String message, String provider, Map<String, Object> data) {

    /** 普通信息日志 */
    public static ProgressEvent info(String message) {
        return new ProgressEvent("info", message, null, null);
    }

    /** 带类型的事件（无额外 data） */
    public static ProgressEvent of(String type, String message, String provider) {
        return new ProgressEvent(type, message, provider, null);
    }

    /** 带类型的事件（带额外 data） */
    public static ProgressEvent of(String type, String message, String provider, Map<String, Object> data) {
        return new ProgressEvent(type, message, provider, data);
    }
}
