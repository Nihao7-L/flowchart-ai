package io.github.nihaoljx.flowchart.config;

import io.github.nihaoljx.flowchart.client.*;
import io.github.nihaoljx.flowchart.service.RagService;
import io.github.nihaoljx.flowchart.service.UsageService;
import io.github.nihaoljx.flowchart.service.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 多 Provider 装配工厂（任务34核心）
 *
 * 不再用 @Component 单实例，而是按 llm.providers 列表逐个 new OpenAiCompatibleProvider，
 * 再用 FallbackLlmProvider 包一层故障转移，对外仍是一个 LlmProvider bean。
 */
@Configuration
public class LlmProviderConfig {

    @Bean
    public LlmProvider llmProvider(LlmProperties props, ObjectProvider<UsageService> usageOpt) {
        UsageService usage = usageOpt.getIfAvailable();

        List<ProviderConfig> raw = props.getProviders();
        List<ProviderConfig> complete = (raw == null || raw.isEmpty())
                ? List.of(fromSingle(props))                          // 向后兼容旧单份配置
                : raw.stream().map(pc -> complete(pc, props)).toList();

        List<LlmProvider> providers = new ArrayList<>();
        for (ProviderConfig c : complete) {
            providers.add(new OpenAiCompatibleProvider(c, usage));
        }

        if (providers.size() == 1) {
            return providers.get(0);
        }
        RoutingStrategy strategy = parseStrategy(props.getRoutingStrategy());
        LlmProvider fallback = new FallbackLlmProvider(providers, strategy);
        long ttlMillis = Duration.ofMinutes(props.getCacheTtlMinutes()).toMillis();
        return new CachingLlmProvider(fallback, usage, ttlMillis);

    }

    /** 任务38：RAG 内存向量库（单例，整个 JVM 共享一份） */
    @Bean
    public VectorStore vectorStore() {
        return new VectorStore();
    }

    /** 任务38：Embedding 客户端（硅基流动 BAAI/bge-m3，免费） */
    @Bean
    public EmbeddingClient embeddingClient(LlmProperties props) {
        LlmProperties.Embedding e = props.getEmbedding();
        String baseUrl = (e.getBaseUrl() == null || e.getBaseUrl().isBlank())
                ? "https://api.siliconflow.cn/v1/embeddings"
                : e.getBaseUrl();
        String model = (e.getModel() == null || e.getModel().isBlank()) ? "BAAI/bge-m3" : e.getModel();
        return new EmbeddingClient(baseUrl, e.getApiKey(), model);
    }

    /** 任务38：RAG 服务（串起 Embedding + 向量库） */
    @Bean
    public RagService ragService(EmbeddingClient embeddingClient, VectorStore vectorStore, LlmProperties props) {
        LlmProperties.Embedding e = props.getEmbedding();
        return new RagService(embeddingClient, vectorStore, e.getChunkSize(), e.getTopK());
    }

    /** 把 yml 里"只写了部分字段"的 ProviderConfig 补全默认值 */
    private ProviderConfig complete(ProviderConfig in, LlmProperties props) {
        ProviderConfig out = new ProviderConfig();
        out.setName(in.getName() != null ? in.getName() : "default");
        out.setBaseUrl(in.getBaseUrl());
        out.setApiKey(in.getApiKey());
        out.setModel(in.getModel());
        out.setCapability(in.getCapability() != null ? in.getCapability() : ProviderCapability.JSON_SCHEMA);
        out.setTimeoutSeconds(in.getTimeoutSeconds() != null ? in.getTimeoutSeconds() : props.getTimeoutSeconds());
        out.setMaxAttempts(in.getMaxAttempts() != null ? in.getMaxAttempts() : props.getRetry().getMaxAttempts());
        out.setInitialBackoffMs(in.getInitialBackoffMs() != null ? in.getInitialBackoffMs() : props.getRetry().getInitialBackoffMs());
        out.setMaxBackoffMs(in.getMaxBackoffMs() != null ? in.getMaxBackoffMs() : props.getRetry().getMaxBackoffMs());
        out.setProxyEnabled(in.getProxyEnabled() != null ? in.getProxyEnabled() : props.getProxy().isEnabled());
        out.setProxyHost(in.getProxyHost() != null ? in.getProxyHost() : props.getProxy().getHost());
        out.setProxyPort(in.getProxyPort() != null ? in.getProxyPort() : props.getProxy().getPort());
        return out;
    }

    /** 兜底：yml 没写 providers 时，用顶层 llm.base-url/api-key/model 构造单 Provider */
    private ProviderConfig fromSingle(LlmProperties props) {
        ProviderConfig out = new ProviderConfig();
        out.setName("default");
        out.setBaseUrl(props.getBaseUrl());
        out.setApiKey(props.getApiKey());
        out.setModel(props.getModel());
        out.setCapability(ProviderCapability.JSON_SCHEMA);
        out.setTimeoutSeconds(props.getTimeoutSeconds());
        out.setMaxAttempts(props.getRetry().getMaxAttempts());
        out.setInitialBackoffMs(props.getRetry().getInitialBackoffMs());
        out.setMaxBackoffMs(props.getRetry().getMaxBackoffMs());
        out.setProxyEnabled(props.getProxy().isEnabled());
        out.setProxyHost(props.getProxy().getHost());
        out.setProxyPort(props.getProxy().getPort());
        return out;
    }

    private RoutingStrategy parseStrategy(String s) {
        if (s == null) return RoutingStrategy.FIXED;
        try {
            return RoutingStrategy.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RoutingStrategy.FIXED;
        }
    }
}
