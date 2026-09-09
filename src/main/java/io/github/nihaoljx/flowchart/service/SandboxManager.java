package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.stream.ProgressContext;
import io.github.nihaoljx.flowchart.client.stream.ProgressEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 沙箱生命周期管理器（按需启停 + 60s 闲置关 + JVM 退出联动）：
 *  - 按需启：CodeExecuteTool.execute 调 ensureRunning()
 *  - 用完关：scheduler 每 10s 扫描，闲置超 60s 自动停
 *  - JVM 联动：@PostConstruct 注册 shutdown hook，容器跟着 Spring Boot 关
 *
 * 设计要点：
 *  - 用 fixed containerName（而非 cidfile），便于 docker ps 排查；启动走两级策略：容器已存在 → docker start 复用（快、保留文件系统），不存在 → docker run 创建
 *  - 用 ReentrantLock + Condition 做并发：第一个进入 STARTING 状态，其余 await；READY 后 broadcast
 *  - 健康检查 = POST /v1/execute body={print("ok")}（不用 /health，因镜像未必暴露）
 *  - scheduler 单线程，避免并发问题；停服时优雅退出
 *  - enabled=false 时所有方法 no-op；保持向后兼容（已配外部 sandboxUrl 的用户零影响）
 *  - 默认 run 参数挂载 docker.sock：onyxdotapp/code-interpreter 是双层架构（外层 API 容器要调 Docker 起内层
 *    python-executor-sci），没有 sock 或 --privileged 会在 entrypoint 阶段因 cgroup 只读而崩（实测 Exited 1）
 */
@Component
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxProperties props;

    /** 状态机当前态（thread-safe 原子引用） */
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    /** 最后一次 touch() 的时间戳（毫秒） */
    private final AtomicLong lastUsedAt = new AtomicLong(0);

    /** 并发锁：ensureRunning() / shutdown() / checkIdle() 同步用 */
    private final ReentrantLock lock = new ReentrantLock();
    /** 启动完成时唤醒所有等待者 */
    private final Condition ready = lock.newCondition();

    /** 闲置扫描器：单线程 ScheduledExecutor */
    private ScheduledExecutorService scheduler;

    /** HTTP 健康检查客户端（复用） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** 关停时屏蔽后续 ensureRunning（Spring 关闭中再启动容器无意义） */
    private volatile boolean shuttingDown = false;

    public SandboxManager(SandboxProperties props) {
        this.props = props;
    }

    public enum State { STOPPED, STARTING, READY, STOPPING }

    public boolean isEnabled() { return props.isEnabled(); }
    public int getPort() { return props.getPort(); }

    @PostConstruct
    void init() {
        if (!props.isEnabled()) {
            log.info("[SandboxManager] disabled（llm.sandbox.enabled=false），所有方法 no-op");
            return;
        }
        // 闲置扫描：单线程；10s 一次，60s 闲置容差合理
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sandbox-idle-scanner");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::checkIdle, 10, 10, TimeUnit.SECONDS);

        // JVM 退出 hook：Spring 关停时也要保证容器被 stop
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[SandboxManager] JVM shutdown hook triggered → stop container");
            shutdown();
        }, "sandbox-shutdown-hook"));
        log.info("[SandboxManager] enabled（docker={}, image={}, container={}, idle={}s）",
                props.getDockerPath(), props.getImage(), props.getContainerName(), props.getIdleTimeoutSeconds());
    }

    @PreDestroy
    void destroy() {
        shutdown();
        if (scheduler != null) scheduler.shutdownNow();
    }

    /**
     * 确保沙箱处于 READY：调用方在 execute() 前调一次
     * @return true=就绪；false=未启用或启动失败（调用方按 fail 处理）
     */
    public boolean ensureRunning() {
        if (!props.isEnabled() || shuttingDown) return false;

        lock.lock();
        try {
            // 快路径：已 READY → 更新 lastUsedAt 直接返回
            if (state.get() == State.READY) {
                touch();
                return true;
            }

            // 第一次进入 STARTING → 启动；其它进入者 await
            if (state.compareAndSet(State.STOPPED, State.STARTING)) {
                log.info("[SandboxManager] ensureRunning() → 启动容器");
                try {
                    doStart();
                    state.set(State.READY);
                    touch();
                    ready.signalAll();
                    ProgressContext.publish(ProgressEvent.info("✅ 沙箱已就绪"));
                    return true;
                } catch (Exception e) {
                    log.error("[SandboxManager] 启动失败: {}", e.getMessage());
                    state.set(State.STOPPED);
                    ready.signalAll();
                    ProgressContext.publish(ProgressEvent.info("⚠️ 沙箱启动失败：" + e.getMessage()));
                    return false;
                }
            }

            // 已经在启动中：等启动完成或超时
            long deadlineMs = System.currentTimeMillis() + props.getStartupTimeoutSeconds() * 1000L;
            while (state.get() == State.STARTING) {
                long remaining = deadlineMs - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.warn("[SandboxManager] 等待启动超时（{}s）", props.getStartupTimeoutSeconds());
                    return false;
                }
                ready.await(remaining, TimeUnit.MILLISECONDS);
            }
            boolean readyState = state.get() == State.READY;
            if (readyState) touch();
            return readyState;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 每次 execute() 完成后调：刷新最后使用时间（scheduler 据此判断闲置） */
    public void touch() {
        lastUsedAt.set(System.currentTimeMillis());
    }

    /** 立即关停：JVM hook / Spring @PreDestroy 调用 */
    public void shutdown() {
        if (!props.isEnabled()) return;
        shuttingDown = true;
        lock.lock();
        try {
            if (state.get() == State.STOPPED) return;
            state.set(State.STOPPING);
            doStop();
            state.set(State.STOPPED);
            log.info("[SandboxManager] 容器已停止");
        } catch (Exception e) {
            log.warn("[SandboxManager] 停止容器异常: {}", e.getMessage());
            state.set(State.STOPPED); // 强行重置，避免卡死
        } finally {
            lock.unlock();
        }
    }

    /** scheduler 回调：检查闲置是否超阈值 */
    private void checkIdle() {
        if (state.get() != State.READY) return;
        long idleMs = System.currentTimeMillis() - lastUsedAt.get();
        if (idleMs < props.getIdleTimeoutSeconds() * 1000L) return;

        lock.lock();
        try {
            // 双重检查（持锁后重新读）
            if (state.get() != State.READY) return;
            long idleMsNow = System.currentTimeMillis() - lastUsedAt.get();
            if (idleMsNow < props.getIdleTimeoutSeconds() * 1000L) return;

            log.info("[SandboxManager] 闲置 {}s → 自动停止容器", idleMsNow / 1000);
            state.set(State.STOPPING);
            doStop();
            state.set(State.STOPPED);
            ProgressContext.publish(ProgressEvent.info("💤 沙箱闲置超时，已自动停止"));
        } catch (Exception e) {
            log.warn("[SandboxManager] 闲置关停异常: {}", e.getMessage());
            state.set(State.STOPPED);
        } finally {
            lock.unlock();
        }
    }

    // ====== 底层 docker 命令调用 ======

    /**
     * 启动沙箱（两级策略，2026-09-05 实测优化）：
     *  - 快路径：容器已存在（含 stopped 状态）→ docker start 复用（实测 3.7s，且保留文件系统/已装 pip 包）
     *  - 慢路径：容器不存在 → docker run 创建（实测 5.8s，仅首次或容器被手动删除时走）
     * start 失败（如容器状态损坏）→ rm 清理后降级走慢路径。
     */
    private void doStart() throws Exception {
        if (containerExists()) {
            try {
                log.info("[SandboxManager] 发现已有容器「{}」→ docker start 复用（保留文件系统，更快）",
                        props.getContainerName());
                runDocker("start", props.getContainerName());
                awaitHealthy();
                return;
            } catch (Exception e) {
                log.warn("[SandboxManager] docker start 失败（{}）→ 清理后重新 run", e.getMessage());
                try {
                    runDocker("rm", "-f", props.getContainerName());
                } catch (Exception ignored) {
                }
            }
        }

        String[] runArgs = props.getCustomRunArgs().isBlank()
                ? new String[]{
                    "run", "-d",
                    "--name", props.getContainerName(),
                    "--user", "root",
                    "-p", props.getPort() + ":" + props.getPort(),
                    // 必须挂载：onyxdotapp/code-interpreter 是双层架构（外层 API + 内层调用 Docker 跑
                    // python-executor-sci），没有 sock 或 --privileged 会在 entrypoint 阶段
                    // 因 cgroup 只读而崩（Exited 1，"mkdir /sys/fs/cgroup/init: Read-only file system"）
                    "-v", "/var/run/docker.sock:/var/run/docker.sock",
                    props.getImage()
                }
                : ("run -d --name " + props.getContainerName() + " " + props.getCustomRunArgs()).split("\\s+");
        runDocker(runArgs);
        awaitHealthy();
    }

    /** 容器是否存在（含 stopped 状态；用 ^/name$ 正则锚定，避免 name= 前缀误匹配） */
    private boolean containerExists() throws Exception {
        try {
            String out = runDocker("ps", "-a", "--filter",
                    "name=^/" + props.getContainerName() + "$", "--format", "{{.Names}}");
            return out.contains(props.getContainerName());
        } catch (Exception e) {
            return false;
        }
    }

    /** 健康检查循环：等 HTTP 端口可用 + 试跑一次，超时抛异常 */
    private void awaitHealthy() throws Exception {
        String healthUrl = "http://localhost:" + props.getPort() + "/v1/execute";
        long deadline = System.currentTimeMillis() + props.getStartupTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (tryHealthCheck(healthUrl)) return;
            Thread.sleep(props.getHealthCheckIntervalMs());
        }
        throw new RuntimeException("沙箱启动超时（" + props.getStartupTimeoutSeconds() + "s）");
    }

    /** POST /v1/execute body=print('ok')：最快判断服务可用的方式 */
    private boolean tryHealthCheck(String url) {
        try {
            String body = "{\"code\":\"print('ok')\",\"timeout_ms\":2000,\"last_line_interactive\":true,\"files\":[]}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return false;
            return resp.body() != null && resp.body().contains("\"exit_code\":0");
        } catch (Exception e) {
            return false; // 连接拒绝 / 超时 → 还没就绪
        }
    }

    /** docker stop 容器（SIGTERM；超 10s 不退会自动 SIGKILL 因 --rm） */
    private void doStop() throws Exception {
        try {
            runDocker("stop", props.getContainerName());
        } catch (Exception e) {
            log.warn("[SandboxManager] docker stop 失败，尝试 rm -f: {}", e.getMessage());
            runDocker("rm", "-f", props.getContainerName());
        }
    }

    /** 调 docker 命令，等待退出，返回 stdout；非零退出码抛异常 */
    private String runDocker(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command(merge(props.getDockerPath(), args));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("docker " + String.join(" ", args) + " 退出码=" + code + ", 输出=" + output);
        }
        return output;
    }

    private static String[] merge(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }
}
