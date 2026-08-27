package io.github.nihaoljx.flowchart.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 路由装饰器：按策略从多个 Provider 中选一个（任务34新增）
 */
public class RoutingLlmProvider implements LlmProvider {
    private final List<LlmProvider> providers;
    private final RoutingStrategy strategy;
    private final AtomicInteger rr = new AtomicInteger(0);

    public RoutingLlmProvider(List<LlmProvider> providers, RoutingStrategy strategy) {
        this.providers = providers;
        this.strategy = strategy;
    }

    /** 当前选中的主 Provider（供外部/Fallback 查询） */
    public LlmProvider select() {
        return switch (strategy) {
            case FIXED -> providers.get(0);
            case ROUND_ROBIN -> providers.get(Math.floorMod(rr.getAndIncrement(), providers.size()));
            case WEIGHTED -> providers.get(0); // TODO: 权重路由
        };
    }

    @Override
    public String chat(String prompt) throws Exception {
        return select().chat(prompt);
    }

    @Override
    public String chatStructured(String prompt, String schemaJson) throws Exception {
        return select().chatStructured(prompt, schemaJson);
    }

    @Override
    public boolean isConfigured() {
        return providers.stream().anyMatch(LlmProvider::isConfigured);
    }
}
