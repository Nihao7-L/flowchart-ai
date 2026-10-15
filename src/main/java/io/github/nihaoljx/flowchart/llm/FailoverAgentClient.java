package io.github.nihaoljx.flowchart.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

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
 * - 无冷却表 / 无错误分类：一步内把候选按序试完，全挂才抛；
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

    /** 上一次成功的候选下标（粘性主选；初始 0 = tier 最小者） */
    private volatile int preferred = 0;

    /**
     * 连续成功次数（周期性回头探主力的计数器）。每次 {@code respond}
     * 成功 +1；达到 {@link #PROBE_INTERVAL} 后，下一次请求强制从
     * {@link #primaryIndex} 开始试一次，给"因一次超时让位的主力"
     * 一个抢回主选的机会（见下）。
     */
    private volatile int successCount = 0;

    /**
     * 每多少次成功后，强制回头探一次主力。背景：主力（glm）一旦因为
     * 一次超时（超时非瞬时错误、不重试、直接切下家）让出主选，只要
     * 后面的候选健康，"粘性主选"就永远停在它们身上，主力长期闲置。
     * 这个间隔就是让主力定期出镜、证明自己又行了就自动拿回主选。
     */
    static final int PROBE_INTERVAL = 3;

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
        Exception last = null;
        // 周期性回头探主力：累计成功达到间隔，下一次强制从主力下标开始。
        boolean probe = successCount >= PROBE_INTERVAL;
        int start = probe ? primaryIndex : preferred;
        for (int i = 0; i < candidates.size(); i++) {
            int idx = (start + i) % candidates.size();
            AgentLlmClient client = candidates.get(idx);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    AgentTurn turn = client
                            .respond(messages, tools, timeoutSeconds);
                    if (attempt > 1) {
                        LOG.info("[AgentFailover] 候选 {}/{} 第 {} 次"
                                        + "重试命中", idx + 1,
                                candidates.size(), attempt);
                    }
                    preferred = idx;
                    // 探针这次成功也只算"探到一次"，计数器归零重数；
                    // 非探针则正常 +1。两路都让主力健康时 preferentially 回到主选。
                    successCount = probe ? 0 : successCount + 1;
                    return turn;
                } catch (Exception e) {
                    last = e;
                    boolean lastCandidate = i == candidates.size() - 1;
                    boolean lastAttempt = attempt == maxAttempts;
                    LOG.warn("[AgentFailover] 候选 {}/{} 第 {}/{} 次"
                                    + "失败: {}",
                            idx + 1, candidates.size(), attempt,
                            maxAttempts, brief(e));
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
