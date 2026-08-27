package io.github.nihaoljx.flowchart.client.stream;

/**
 * 请求级"选中的模型"上下文（ThreadLocal）
 *
 * 和 ProgressContext 同理：Provider 链是启动时装配的单例 bean，无法在每个 HTTP 请求里改配置，
 * 所以把"前端下拉框选中的模型名"（mimo / kimi）挂到当前请求线程上，
 * FallbackLlmProvider 选主模型时来读它，实现"选哪个就优先打哪个"。
 *
 * 注意：请求结束必须 clear()，否则线程池复用会串数据（下一个请求误用上一个请求的模型）。
 */
public final class RoutingContext {

    private static final ThreadLocal<String> SELECTED = new ThreadLocal<>();

    private RoutingContext() {}

    /** 设置本次请求选中的模型名（mimo / kimi），传 null 表示用后端默认策略 */
    public static void set(String model) {
        SELECTED.set(model);
    }

    /** 读取选中的模型名，未设置返回 null */
    public static String get() {
        return SELECTED.get();
    }

    /** 请求结束必须清掉，避免线程池复用串数据 */
    public static void clear() {
        SELECTED.remove();
    }
}
