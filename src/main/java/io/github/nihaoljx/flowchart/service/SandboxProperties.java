package io.github.nihaoljx.flowchart.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 沙箱自管配置（按需启停 + 60s 闲置关 + JVM 退出联动）：
 * - enabled=false：保留向后兼容，原 CodeExecuteTool 继续调 sandboxUrl（外部沙箱）
 * - enabled=true：SandboxManager 接管，按需启停
 *
 * ⚠️ Docker Desktop 必须先启动，否则 ensureRunning() 会启动失败并降级。
 */
@ConfigurationProperties(prefix = "llm.sandbox")
public class SandboxProperties {

    /** 总开关：默认 false（不接管）；true 才自管沙箱生命周期 */
    private boolean enabled = false;

    /** Docker CLI 路径（PATH 里没有时配绝对路径） */
    private String dockerPath = "docker";

    /** 镜像名 */
    private String image = "onyxdotapp/code-interpreter";

    /** 容器名（固定名，便于 stop + 排查；冲突时先 stop+rm 再 run） */
    private String containerName = "sandbox";

    /** 容器端口（与 sandboxUrl 中的端口一致） */
    private int port = 8000;

    /** docker run + 健康检查总超时（秒） */
    private int startupTimeoutSeconds = 30;

    /** 闲置超时（秒），默认 60s —— 防止短间隔复用时反复重启 */
    private int idleTimeoutSeconds = 60;

    /** 健康检查请求间隔（毫秒） */
    private long healthCheckIntervalMs = 500;

    /** 自定义 docker run 参数（覆盖默认端口映射 / 卷挂载），留空用默认 */
    private String customRunArgs = "";

    // === getters / setters ===

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDockerPath() { return dockerPath; }
    public void setDockerPath(String dockerPath) { this.dockerPath = dockerPath; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getStartupTimeoutSeconds() { return startupTimeoutSeconds; }
    public void setStartupTimeoutSeconds(int startupTimeoutSeconds) { this.startupTimeoutSeconds = startupTimeoutSeconds; }

    public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }

    public long getHealthCheckIntervalMs() { return healthCheckIntervalMs; }
    public void setHealthCheckIntervalMs(long healthCheckIntervalMs) { this.healthCheckIntervalMs = healthCheckIntervalMs; }

    public String getCustomRunArgs() { return customRunArgs; }
    public void setCustomRunArgs(String customRunArgs) { this.customRunArgs = customRunArgs; }
}
