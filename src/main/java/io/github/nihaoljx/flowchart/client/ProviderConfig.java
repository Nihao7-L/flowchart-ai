package io.github.nihaoljx.flowchart.client;

/**
 * 单个 Provider 的运行时配置（任务34新增）
 * 字段用包装类型，便于 yml 没配时为 null，工厂再补默认值
 */
public class ProviderConfig {
    private String name;
    private String baseUrl;
    private String apiKey;
    private String model;
    private ProviderCapability capability;
    private Integer timeoutSeconds;
    private Integer maxAttempts;
    private Long initialBackoffMs;
    private Long maxBackoffMs;
    private Boolean proxyEnabled;
    private String proxyHost;
    private Integer proxyPort;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public ProviderCapability getCapability() { return capability; }
    public void setCapability(ProviderCapability capability) { this.capability = capability; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Long getInitialBackoffMs() { return initialBackoffMs; }
    public void setInitialBackoffMs(Long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
    public Long getMaxBackoffMs() { return maxBackoffMs; }
    public void setMaxBackoffMs(Long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
    public Boolean getProxyEnabled() { return proxyEnabled; }
    public void setProxyEnabled(Boolean proxyEnabled) { this.proxyEnabled = proxyEnabled; }
    public String getProxyHost() { return proxyHost; }
    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }
    public Integer getProxyPort() { return proxyPort; }
    public void setProxyPort(Integer proxyPort) { this.proxyPort = proxyPort; }
}
