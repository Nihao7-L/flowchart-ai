package io.github.nihaoljx.flowchart.client;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多候选故障转移链（best-first + 每候选独立冷却）。
 *
 * <p>只在 {@code llm.failover-enabled=true} 时装配，并被标记 {@link Primary} ——
 * 于是 GenerationService 注入到的就是它，业务代码一行不用改。
 *
 * <p>触发切换的"硬失败"：HTTP 401 / 402 / 403（额度 / 权限）、429（限流 / 过载）、
 * 5xx、超时、网络错误，以及"输出被截断"。2xx 但内容不合格**不在此列** ——
 * 那是 GenerationService 校验轮的事，混在一起会让问题难定位。
 */
@Component
@Primary
@ConditionalOnProperty(name = "llm.failover-enabled", havingValue = "true")
public class FailoverLlmProvider implements LlmProvider {

    private static final Logger LOG =
            LoggerFactory.getLogger(FailoverLlmProvider.class);

    /** 额度 / 权限类：冷却 10 分钟（短时间内重试没意义） */
    private static final long COOL_QUOTA_MS = 10 * 60_000L;
    /** 限流 / 过载：冷却 60 秒 */
    private static final long COOL_RATE_MS = 60_000L;
    /** 5xx / 超时 / 网络：冷却 120 秒 */
    private static final long COOL_TEMP_MS = 120_000L;
    /** 永久停用（模型被删、key 无效） */
    private static final long STOP_FOREVER = Long.MAX_VALUE;

    private static final int MIN_ATTEMPT_SECONDS = 5;
    private static final int DEFAULT_TIMEOUT = 120;

    /**
     * 单个候选最多能占用"当前剩余预算"的比例。
     *
     * <p>防的是"一个慢候选吃掉整轮"：留出余量给后面的候选，
     * 也留出余量给下一轮（不然首轮全超时 = 后面两轮都没钱跑）。
     */
    private static final double MAX_BUDGET_SHARE = 0.6;

    /** 429 到底是"没额度"还是"太挤"——各家都用 429，只能读 body */
    private static boolean isQuotaError(String body) {
        if (body == null) {
            return false;
        }
        String b = body.toLowerCase();
        return b.contains("1113")
                || b.contains("余额不足")
                || b.contains("请充值")
                || b.contains("insufficient")
                || b.contains("quota")
                || b.contains("exceeded your current quota")
                || b.contains("no available resource");
    }

    private final LlmProperties properties;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();

    public FailoverLlmProvider(LlmProperties properties) {
        this.properties = properties;
    }

    /** 一个候选 = 一个独立的 OpenAI 兼容客户端 + 排序 / 能力元数据 */
    private static final class Entry {
        final String name;
        final int tier;
        final int minRound;
        final int timeoutSeconds;
        final boolean largeOutput;
        final OpenAiCompatibleProvider client;

        Entry(LlmProperties.Candidate c) {
            this.name = c.getName();
            this.tier = c.getTier();
            this.minRound = c.getMinRound();
            this.timeoutSeconds = c.getTimeoutSeconds();
            this.largeOutput = c.isLargeOutput();
            this.client = new OpenAiCompatibleProvider(
                    c.getBaseUrl(), c.getApiKey(), c.getModel(),
                    c.getTimeoutSeconds(), false, "127.0.0.1", 7890,
                    c.isStream(), c.getIdleTimeoutSeconds());
        }
    }

    @PostConstruct
    public void init() {
        List<LlmProperties.Candidate> cs =
                new ArrayList<>(properties.getCandidates());
        cs.sort(Comparator.comparingInt(
                LlmProperties.Candidate::getTier));
        for (LlmProperties.Candidate c : cs) {
            if (isBlank(c.getBaseUrl())
                    || isBlank(c.getApiKey())
                    || isBlank(c.getModel())) {
                LOG.warn("[Failover] 跳过未配置完整的候选: {}",
                        c.getName());
                continue;
            }
            entries.add(new Entry(c));
        }
        LOG.info("[Failover] 装载候选 {} 个（tier 升序）: {}",
                entries.size(),
                entries.stream()
                        .map(e -> e.name + "(t" + e.tier
                                + ",r" + e.minRound + ")")
                        .toList());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public boolean isConfigured() {
        return entries.stream()
                .anyMatch(e -> e.client.isConfigured());
    }

    @Override
    public String chat(String prompt) throws Exception {
        return chat(List.of(Message.user(prompt)), DEFAULT_TIMEOUT);
    }

    @Override
    public String chat(String prompt, int timeoutSeconds) throws Exception {
        return chat(List.of(Message.user(prompt)), timeoutSeconds);
    }

    @Override
    public String chat(List<Message> messages, int timeoutSeconds)
            throws Exception {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        int round = LlmEffort.currentRound();
        // 难度档 = max(轮次, 需求规模换算)：大活在第 1 轮就等同"难任务"，
        // 让 minRound=2 的强模型立刻可用（2026-10-07）
        int effort = LlmEffort.effectiveEffort();
        long deadline = System.currentTimeMillis()
                + timeoutSeconds * 1000L;

        // 按当前难度档筛出可用候选（minRound 实现"按难度升档"）
        List<Entry> order = new ArrayList<>();
        for (Entry e : entries) {
            if (e.minRound <= effort) {
                order.add(e);
            }
        }
        if (order.isEmpty()) {
            order = new ArrayList<>(entries);
        }
        LOG.info("[Failover] 本轮难度档 effort={}（轮次={}，规模={}），"
                        + "可用候选 {} 个: {}",
                effort, round, LlmEffort.currentScale(), order.size(),
                order.stream().map(x -> x.name).toList());

        Exception last = null;
        boolean truncated = false;

        while (!order.isEmpty()) {
            Entry e = pick(order, truncated);
            order.remove(e);

            Long until = cooldownUntil.get(e.name);
            if (until != null
                    && until > System.currentTimeMillis()) {
                long left = (until - System.currentTimeMillis())
                        / 1000;
                LOG.info("[Failover] 跳过冷却中的候选 {}（剩余 {}s）",
                        e.name, left);
                continue;
            }

            long remaining = (deadline - System.currentTimeMillis())
                    / 1000;
            if (remaining < MIN_ATTEMPT_SECONDS) {
                LOG.warn("[Failover] 预算不足以再试候选 {}"
                        + "（剩余 {}s），停止", e.name, remaining);
                break;
            }
            // 单候选不许吃掉整轮预算（2026-10-07 实测教训）：
            // 之前 attempt = min(remaining, 候选上限)，一个慢候选敢用 150s，
            // 三个候选依次超时正好把整轮 300s 耗光，下一轮连预算都没了。
            long fairShare = Math.max(MIN_ATTEMPT_SECONDS,
                    (long) (remaining * MAX_BUDGET_SHARE));
            int attempt = (int) Math.min(
                    Math.min(remaining, fairShare),
                    Math.max(MIN_ATTEMPT_SECONDS, e.timeoutSeconds));

            try {
                long t0 = System.currentTimeMillis();
                String out = e.client.chat(messages, attempt);
                LOG.info("[Failover] 候选 {} 命中（round={}），耗时 {} ms",
                        e.name, round,
                        System.currentTimeMillis() - t0);
                return out;
            } catch (OpenAiCompatibleProvider
                    .TruncatedResponseException te) {
                truncated = true;
                last = te;
                LOG.warn("[Failover] 候选 {} 输出被截断，"
                        + "改换「大输出」候选重试", e.name);
            } catch (OpenAiCompatibleProvider.LlmHttpException he) {
                last = he;
                // 429 有两种语义、只能靠 body 分（2026-10-07 实测）：
                //   智谱 code=1113「余额不足或无可用资源包」= 额度问题 → 长冷却
                //   智谱 code=1305「该模型当前访问量过大」= 真限流     → 短冷却
                boolean quota = isQuotaError(he.body);
                long cool = switch (he.status) {
                    case 401, 402, 403 -> COOL_QUOTA_MS;
                    case 429 -> quota ? COOL_QUOTA_MS : COOL_RATE_MS;
                    case 404 -> STOP_FOREVER;
                    default -> COOL_TEMP_MS;
                };
                applyCooldown(e, cool, "HTTP " + he.status
                        + (quota ? "(额度)" : ""));
            } catch (Exception ex) {
                last = ex;
                applyCooldown(e, COOL_TEMP_MS,
                        ex.getClass().getSimpleName());
            }
        }
        throw new Exception("所有模型候选均不可用（truncated="
                + truncated + "）", last);
    }

    /**
     * order 已按 tier 升序：默认取最优候选；
     * 刚发生过截断时，优先挑"大输出"候选。
     */
    private Entry pick(List<Entry> order, boolean preferLargeOutput) {
        if (preferLargeOutput) {
            for (Entry e : order) {
                if (e.largeOutput) {
                    return e;
                }
            }
        }
        return order.get(0);
    }

    /**
     * 仅供测试观测：某候选的剩余冷却秒数（没在冷却就返回 0）。
     *
     * <p>之所以要暴露：429 的两种语义（额度 vs 限流）只体现在冷却**时长**
     * 上，光看"是否切换"区分不出来。
     */
    long cooldownRemainingSeconds(String name) {
        Long until = cooldownUntil.get(name);
        if (until == null) {
            return 0;
        }
        if (until == STOP_FOREVER) {
            return Long.MAX_VALUE;
        }
        return Math.max(0,
                (until - System.currentTimeMillis()) / 1000);
    }

    private void applyCooldown(Entry e, long coolMs, String reason) {
        if (coolMs >= STOP_FOREVER) {
            cooldownUntil.put(e.name, STOP_FOREVER);
            LOG.error("[Failover] 候选 {} 永久失败({})，已停用；"
                    + "请检查 model id / key 是否失效", e.name, reason);
        } else {
            cooldownUntil.put(e.name,
                    System.currentTimeMillis() + coolMs);
            LOG.warn("[Failover] 候选 {} 失败({})，冷却 {} 秒",
                    e.name, reason, coolMs / 1000);
        }
    }
}
