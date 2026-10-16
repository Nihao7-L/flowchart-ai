package io.github.nihaoljx.flowchart.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * agent 路径的最小故障转移客户端（粘性主选 + 瞬时错误退避重试 + 调用日志）
 *
 * 为什么必须有它：真实 E2E 实测 glm-5.3-flash 画大图时中转站
 * 直接回 HTTP 502（"请求暂时未能完成"），单候选实现一步失败 = 整次生成
 * 直接兜底收场。多候选故障转移就是为这个坑设计的：一家 502 就切
 * 下一家，整次生成不跟着挂。
 *
 * 刻意最小（只做候选切换与瞬时重试，判死逻辑从简）：
 *
 * - 无错误分类，冷却只认一类：超时 / 连接类失败（候选远端挂死，
 *       连接活着但就是不回包）后给该候选记 {@value #HANG_COOLDOWN_MS}
 *       毫秒短冷却，冷却期内后续请求直接跳过它 —— 否则每次新请求都要
 *       再付一遍"挂满窗口才知道它死了"的代价（2026-10-15 批量实测：
 *       同一候选一天挂死 4+ 次，每次白烧 300s 硬帽）。瞬时 502 不冷却
 *       —— 它是立即返回的，代价只是一次请求，退避重试就能过。
 *       进程内存即可，重启即忘；正式的冷却 / 分类 / 多实例共享归 M13 网关；
 *
 * - 粘性主选：记住上一次成功的候选，后续步骤从它开始 —— 正常情况下
 *       不会因为某次 502 就每步都重付一遍前序候选的失败代价；
 *
 * - 周期性回头探主力：主力（tier 最小的 glm）一旦因一次**超时**（超时
 *       非瞬时错误、不重试、直接切下家）让出主选，只要后面的候选健康，
 *       粘性主选就永远停在它们身上、主力长期闲置。为避免这个"被一次超时
 *       永久挤下"的副作用，每 {@value #PROBE_INTERVAL} 次成功后，下一次
 *       请求强制从主力下标试一次 —— 主力恢复就自动 reclaim 主选，
 *       否则照常落到当前粘性候选（代价只是偶尔多付一次主力的失败
 *       判定，换来"主力复活自动上位"）。
 *
 * - 瞬时错误退避重试：502/503/504/429 这类**立即返回**
 *       的网关过载错误，对同一候选最多再试 {@value #TRANSIENT_MAX_ATTEMPTS}
 *       次（退避 {@value #BACKOFF_BASE_MS}ms 起、每次翻倍）——实测 r10 大图
 *       连续 3 次整轮失败都是这类瞬时 502，换候选解决不了、等一下就能过。
 *       刻意**不含超时/连接类异常**：挂满窗口的候选重试只会白烧预算；
 *
 * - 预算不放大：所有重试共享调用方传入的同一个 `timeoutSeconds`
 *       窗口（它是按剩余总预算折算的，见 AgentLoop）—— 前一个候选烧掉的
 *       时间天然挤占后一个，不会突破总预算。
 *
 * 正式的冷却 / 分类 / 多实例共享归 M13 网关。
 */
public final class FailoverAgentClient implements AgentLlmClient {

    private static final Logger LOG =
            LoggerFactory.getLogger(FailoverAgentClient.class);

    /** 瞬时错误（网关过载）对同一候选的最大尝试次数 */
    static final int TRANSIENT_MAX_ATTEMPTS = 3;

    /** 瞬时错误重试的基础退避（毫秒），第 n 次重试等待 base * 2^(n-1) */
    static final long BACKOFF_BASE_MS = 2_000L;

    private final List<AgentLlmClient> candidates;

    /**
     * 主力候选下标：tier 排序后的第一个（tier 最小者 = glm 主力）。
     * 候选表在 AgentLoopService 里已经按 tier 升序排好，所以下标 0
     * 必然是 tier 最小的那个；要换主力，改 yml 里对应候选的 tier 即可。
     */
    private final int primaryIndex = 0;

    /**
     * 挂死候选的冷却时长（毫秒）。挂死的定义：连接活着但远端不回包，
     * 只能等超时（300s 硬帽）——这种失败的信息量大（不是瞬时抖动），
     * 短冷却期内跳过它，后续请求不再重复付这个代价。
     */
    static final long HANG_COOLDOWN_MS = 120_000L;

    /** 候选下标 → 冷却截止时刻（epoch ms）。进程内存，重启即忘 */
    private final Map<Integer, Long> hangCooldown =
            new ConcurrentHashMap<>();

    /**
     * 候选下标 → 连续挂死次数（成功一次清零）。挂死冷却逐级升级：
     * 今天（2026-10-15）主力候选反复挂死，固定 120s 冷却意味着每
     * 2 分钟就要重新烧一遍 180s 硬帽 —— regress_arch7 实测一次生成
     * 201.9s 里 180s 是挂死白烧。连续挂 2 次 ×5、3 次以上 ×15，
     * 把「病候选」快速隔离出本进程生命周期。
     */
    private final Map<Integer, Integer> hangStrikes =
            new ConcurrentHashMap<>();

    /** 测试注入用：重试次数上限与基础退避 */
    private final int maxAttempts;
    private final long backoffBaseMs;

    public FailoverAgentClient(List<AgentLlmClient> candidates) {
        this(candidates, TRANSIENT_MAX_ATTEMPTS, BACKOFF_BASE_MS);
    }

    FailoverAgentClient(List<AgentLlmClient> candidates,
                        int maxAttempts, long backoffBaseMs) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "agent 故障转移至少需要一个候选客户端");
        }
        this.candidates = List.copyOf(candidates);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffBaseMs = Math.max(0, backoffBaseMs);
    }

    @Override
    public AgentTurn respond(List<AgentMessage> messages,
                             List<Map<String, Object>> tools,
                             int timeoutSeconds) throws Exception {
        return respond(messages, tools, timeoutSeconds, "auto");
    }

    @Override
    public AgentTurn respond(List<AgentMessage> messages,
                             List<Map<String, Object>> tools,
                             int timeoutSeconds,
                             String toolChoice) throws Exception {
        Exception last = null;
        // 每次都从主力（下标 0 = tier 最小者）重新试起，不做"粘性主选"。
        // 2026-10-16 用户拍板：每张图都从主力开始试（主力质量最好），
        // 主力失败才按序往后转移 —— 上一张图成功用过的候选不带到下一张。
        int start = primaryIndex;
        // 冷却过滤：保持从 start 起的环形顺序，跳过冷却中的候选；
        // 全员都在冷却时无视冷却（避免"没有候选可用"的死路）
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            order.add((start + i) % candidates.size());
        }
        List<Integer> usable = new ArrayList<>();
        for (int idx : order) {
            if (!isCooling(idx)) {
                usable.add(idx);
            }
        }
        if (usable.isEmpty()) {
            usable = order;
        }
        for (int rank = 0; rank < usable.size(); rank++) {
            int idx = usable.get(rank);
            AgentLlmClient client = candidates.get(idx);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    // auto 语义走老三参：测试桩（Mockito mock 不认
                    // 接口 default 方法）与旧行为都照旧；只有显式
                    // required 才走四参
                    AgentTurn turn =
                            (toolChoice == null
                                    || "auto".equals(toolChoice))
                            ? client.respond(messages, tools,
                                    timeoutSeconds)
                            : client.respond(messages, tools,
                                    timeoutSeconds, toolChoice);
                    if (attempt > 1) {
                        LOG.info("[AgentFailover] 候选 {}/{} 第 {} 次"
                                        + "重试命中", idx + 1,
                                candidates.size(), attempt);
                    }
                    hangCooldown.remove(idx);
                    hangStrikes.remove(idx);
                    return turn;
                } catch (Exception e) {
                    last = e;
                    boolean lastCandidate = rank == usable.size() - 1;
                    boolean lastAttempt = attempt == maxAttempts;
                    LOG.warn("[AgentFailover] 候选 {}/{} 第 {}/{} 次"
                                    + "失败: {}",
                            idx + 1, candidates.size(), attempt,
                            maxAttempts, brief(e));
                    if (isHang(e)) {
                        // 挂死（远端不回包，只能等超时）：逐级升级冷却，
                        // 后续请求不再重复付"挂满窗口"的代价。
                        // 最后一个候选也记 —— 挂死就是挂死，不能
                        // 因为"它是最后试的"就下次还当健康候选先试
                        int strikes = hangStrikes.merge(idx, 1,
                                Integer::sum);
                        long cooldown = Math.min(
                                HANG_COOLDOWN_MS
                                        * strikeMultiplier(strikes),
                                HANG_COOLDOWN_CAP_MS);
                        hangCooldown.put(idx,
                                System.currentTimeMillis() + cooldown);
                        LOG.info("[AgentFailover] 候选 {}/{} 判挂死（连续"
                                        + "第 {} 次），冷却 {}s",
                                idx + 1, candidates.size(), strikes,
                                cooldown / 1000);
                    }
                    if (lastCandidate && lastAttempt) {
                        // 最后一个候选也挂了：抛给循环走兜底语义
                        throw e;
                    }
                    if (isTransient(e) && !lastAttempt) {
                        sleepBackoff(attempt);
                        continue;
                    }
                    break; // 非瞬时错误 → 切下一个候选
                }
            }
        }
        throw last;
    }

    /** 该候选是否在挂死冷却期内 */
    private boolean isCooling(int idx) {
        Long until = hangCooldown.get(idx);
        return until != null
                && System.currentTimeMillis() < until;
    }

    /** 连续挂死次数 → 冷却倍率：1 次 ×1、2 次 ×5、3 次及以上 ×15（封顶 30 分钟） */
    static long strikeMultiplier(int strikes) {
        if (strikes <= 1) {
            return 1;
        }
        return strikes == 2 ? 5 : 15;
    }

    /** 挂死冷却的绝对上限（30 分钟）：进程内存冷却是会话级隔离手段，不是死刑 */
    static final long HANG_COOLDOWN_CAP_MS = 1_800_000L;

    /**
     * 瞬时错误判定：只认「立即返回」的网关过载状态码
     * （502/503/504/429，OpenAiToolClient 统一抛
     * "LLM HTTP 状态码"）。超时 / 连接类异常刻意不算 ——
     * 挂满窗口的候选重试只会白烧预算，换下一家更快。
     */
    private static boolean isTransient(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        return msg.contains("LLM HTTP 502")
                || msg.contains("LLM HTTP 503")
                || msg.contains("LLM HTTP 504")
                || msg.contains("LLM HTTP 429");
    }

    /**
     * 挂死判定：远端不回包、只能等超时的那类失败
     * （HTTP 客户端的 HttpTimeoutException、连接层的
     * ConnectException，以及消息里带 timed out / timeout /
     * Connection refused / reset 的包装异常）。这类失败与瞬时
     * 502 的本质区别：它要烧满整个超时窗口才知道死了 —— 值得
     * 记冷却，让后续请求别再付这个代价。
     */
    private static boolean isHang(Exception e) {
        if (e instanceof HttpTimeoutException
                || e instanceof ConnectException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        return msg.contains("timed out")
                || msg.contains("timeout")
                || msg.contains("Connection refused")
                || msg.contains("Connection reset");
    }

    /** 第 attempt 次失败后的退避：base * 2^(attempt-1)，可被中断 */
    private void sleepBackoff(int attempt) throws InterruptedException {
        long wait = backoffBaseMs * (1L << (attempt - 1));
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    /** 异常单行摘要（日志有界）：状态行 + 首段 body */
    private static String brief(Exception e) {
        String msg = String.valueOf(e.getMessage());
        msg = msg.replace('\n', ' ');
        return msg.length() <= 180
                ? msg : msg.substring(0, 180) + "…";
    }
}
