package io.github.nihaoljx.flowchart.client;

import io.github.nihaoljx.flowchart.client.stream.ClientDisconnectedException;
import io.github.nihaoljx.flowchart.client.stream.ProgressContext;
import io.github.nihaoljx.flowchart.client.stream.ProgressEvent;
import io.github.nihaoljx.flowchart.client.stream.RoutingContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 故障转移装饰器（任务34核心）
 *
 * 先按路由策略选主 Provider；主抛异常（含 401 错 key、429 限流、5xx）就切到下一个，
 * 全部失败才把最后一个异常抛给上层（Controller 捕获返回 500）。
 * 内部已内聚"路由+fallback"，避免再包一层 Routing 造成的重复尝试。
 */
public class FallbackLlmProvider implements LlmProvider {
    private final List<LlmProvider> providers;
    private final RoutingStrategy strategy;
    private final AtomicInteger rr = new AtomicInteger(0);

    public FallbackLlmProvider(List<LlmProvider> providers, RoutingStrategy strategy) {
        this.providers = providers;
        this.strategy = strategy;
    }

    private LlmProvider primary() {
        // 任务37后续：前端下拉框选中的模型优先作为主，其余自动成为 fallback
        // 这样"选哪个就优先打哪个"——选 mimo 先打 mimo，选 kimi 先打 kimi（失败再回退另一个）
        String selected = RoutingContext.get();
        if (selected != null) {
            for (LlmProvider p : providers) {
                if (selected.equalsIgnoreCase(p.name())) {
                    return p;
                }
            }
            // 选中的名字不在已知 provider 列表里，回退到策略默认
        }
        return switch (strategy) {
            case FIXED -> providers.get(0);
            case ROUND_ROBIN -> providers.get(Math.floorMod(rr.getAndIncrement(), providers.size()));
            case WEIGHTED -> providers.get(0); // TODO: 权重路由
        };
    }

    @Override
    public String chat(String prompt) throws Exception {
        return withFallback(p -> p.chat(prompt));
    }

    @Override
    public String chatStructured(String prompt, String schemaJson) throws Exception {
        return withFallback(p -> p.chatStructured(prompt, schemaJson));
    }

    @Override
    public String chatWithTools(List<Map<String, Object>> messages, String toolsJson) throws Exception {
        return withFallback(p -> p.chatWithTools(messages, toolsJson));
    }

    @Override
    public String chatStructuredStream(String prompt, String schemaJson) throws Exception {
        // 任务39+：流式穿主备链——主模型流式失败就切备用重新流（连接层失败时重开流，中途失败则早停）
        return withFallback(p -> p.chatStructuredStream(prompt, schemaJson));
    }

    @Override
    public boolean isConfigured() {
        return providers.stream().anyMatch(LlmProvider::isConfigured);
    }

    @Override
    public String name() {
        return "fallback";
    }

    @FunctionalInterface
    private interface CheckedFunction<T, R> {
        R apply(T t) throws Exception;
    }

    private String withFallback(CheckedFunction<LlmProvider, String> action) throws Exception {
        Exception last = null;
        // 主在前，其余去重追加（保证每个 Provider 只尝试一次）
        LinkedHashSet<LlmProvider> order = new LinkedHashSet<>();
        order.add(primary());
        order.addAll(providers);

        LlmProvider previous = null;
        int index = 0;
        for (LlmProvider p : order) {
            if (index > 0) {
                // 主模型失败，切换到下一个备用（任务37：推前端进度）
                ProgressContext.publish(ProgressEvent.of("provider_switch",
                        "主模型不可用，切换到备用：" + p.name(),
                        p.name(), Map.of("from", previous.name(), "to", p.name())));
            }
            try {
                return action.apply(p);
            } catch (ClientDisconnectedException e) {
                // 任务40：用户主动停止 / 客户端断开——不是"模型失败"，绝不能切备用继续生成，直接上抛
                throw e;
            } catch (Exception e) {
                last = e;
                // 任务37：记一条"某模型失败"
                ProgressContext.publish(ProgressEvent.of("provider_exhausted",
                        "模型 " + p.name() + " 失败：" + e.getMessage(),
                        p.name(), Map.of("reason", e.getMessage())));
            }
            previous = p;
            index++;
        }
        throw last != null ? last : new Exception("所有 Provider 均不可用");
    }
}
