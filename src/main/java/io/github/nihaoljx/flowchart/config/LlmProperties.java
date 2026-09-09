package io.github.nihaoljx.flowchart.config;

import io.github.nihaoljx.flowchart.client.ProviderConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 相关配置的强类型绑定（任务31新增，任务34扩展）
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** 多 Provider 列表（任务34新增）。为空时退化为单份 base-url/api-key/model */
    private List<ProviderConfig> providers = new ArrayList<>();

    /** 路由策略：fixed / round_robin / weighted（默认 fixed） */
    private String routingStrategy = "fixed";
    /** 结果缓存 TTL（分钟），默认 60 */
    private int cacheTtlMinutes = 60;

    public int getCacheTtlMinutes() { return cacheTtlMinutes; }
    public void setCacheTtlMinutes(int v) { this.cacheTtlMinutes = v; }


    /** 兜底单份配置（不写 providers 时用） */
    private String baseUrl;
    private String apiKey;
    private String model;
    private int timeoutSeconds = 60;

    /** RAG 检索增强用的 Embedding 配置（任务38新增） */
    private Embedding embedding = new Embedding();

    /** 顶层共享的重试配置（工厂自动填进每个 Provider） */
    private Retry retry = new Retry();

    /** 顶层共享的代理配置 */
    private Proxy proxy = new Proxy();

    /** 各模型每百万 token 价格（元） */
    private Map<String, Double> pricing = new HashMap<>();

    public static class Retry {
        private int maxAttempts = 3;
        private long initialBackoffMs = 1000;
        private long maxBackoffMs = 8000;
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { this.maxAttempts = v; }
        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long v) { this.initialBackoffMs = v; }
        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long v) { this.maxBackoffMs = v; }
    }

    public static class Proxy {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 7890;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getHost() { return host; }
        public void setHost(String v) { this.host = v; }
        public int getPort() { return port; }
        public void setPort(int v) { this.port = v; }
    }

    /**
     * RAG 检索增强用的 Embedding 配置（任务38新增）
     * 走 OpenAI 兼容 /v1/embeddings 接口，默认用硅基流动免费 BAAI/bge-m3。
     */
    public static class Embedding {
        /** embeddings 接口地址，默认硅基流动 */
        private String baseUrl;
        /** 硅基流动 API Key（免费额度即可） */
        private String apiKey;
        /** 模型名，默认 BAAI/bge-m3（1024 维，免费） */
        private String model = "BAAI/bge-m3";
        /** 切片大小（字），默认 300 */
        private int chunkSize = 300;
        /** 检索返回片段数，默认 3 */
        private int topK = 3;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int v) { this.chunkSize = v; }
        public int getTopK() { return topK; }
        public void setTopK(int v) { this.topK = v; }
    }

    // ===== getters / setters =====
    public List<ProviderConfig> getProviders() { return providers; }
    public void setProviders(List<ProviderConfig> v) { this.providers = v; }
    public String getRoutingStrategy() { return routingStrategy; }
    public void setRoutingStrategy(String v) { this.routingStrategy = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    public Retry getRetry() { return retry; }
    public void setRetry(Retry v) { this.retry = v; }
    public Proxy getProxy() { return proxy; }
    public void setProxy(Proxy v) { this.proxy = v; }
    public Map<String, Double> getPricing() { return pricing; }
    public void setPricing(Map<String, Double> v) { this.pricing = v; }

    public Embedding getEmbedding() { return embedding; }
    public void setEmbedding(Embedding v) { this.embedding = v; }
}
