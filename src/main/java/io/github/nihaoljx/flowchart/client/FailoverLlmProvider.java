package io.github.nihaoljx.flowchart.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 多候选故障转移链（best-first + 每候选独立冷却）。
 *
 * <p>只在 {@code llm.failover-enabled=true} 时装配，并被标记 {@link Primary} ——
 * 于是 GenerationService 注入到的就是它，业务代码一行不用改。
 *
 * <p>触发切换的"硬失败"：HTTP 401 / 402 / 403（额度 / 权限）、429（限流 / 过载）、
 * 5xx、超时、网络错误，以及"输出被截断"。2xx 但内容不合格**不在此列** ——
 * 那是 GenerationService 校验轮的事，混在一起会让问题难定位。
 *
 * <p><b>2026-10-10 重构：错误先分类、再决定冷却。</b>
 * 之前冷却时长直接由 HTTP 状态码决定，导致"请求本身有问题"
 * （如上下文超长，厂商可能返回 400 甚至 429）被当成候选故障，
 * 把健康的候选冷却掉、甚至拖垮整条候选链。现在统一走
 * {@link LlmFailureKind} 分类，冷却时长按分类查表，见 {@link #cooldownOf}。
 */
@Component
@Primary
@ConditionalOnProperty(name = "llm.failover-enabled", havingValue = "true")
public class FailoverLlmProvider implements LlmProvider {

    private static final Logger LOG =
            LoggerFactory.getLogger(FailoverLlmProvider.class);

    /**
     * 额度 / 权限类冷却：30 秒（2026-10-11 由 10 分钟改短）。
     *
     * <p>改短的理由是一次实测事故：一个复杂请求把 5 个候选全部打失败，
     * 每个候选各吃 120s~10min 冷却 ⇒ 后续每次请求进来时候选表**全在冷却**，
     * 3 轮重试在 <b>47 毫秒</b>内空转完毕 —— 日志明写"剩余预算 869s 仍够"，
     * 却只能吐兜底占位符。冷却的作用是"让下游喘口气"，不该变成"把整条链锁死"。
     */
    private static final long COOL_QUOTA_MS = 30_000L;

    /** 限流 / 过载（429）：15 秒（原 60 秒） */
    private static final long COOL_RATE_MS = 15_000L;

    /** 5xx / 超时 / 网络 / 空正文：20 秒（原 120 秒） */
    private static final long COOL_TEMP_MS = 20_000L;

    /**
     * 任何非永久冷却的**硬上限**：30 秒。
     *
     * <p>它保证"冷却时长"永远远小于一轮重试的量级（单轮上限 420 秒）——
     * 于是同一请求的第 2、3 轮重试时候选必然已恢复，这才是"三轮重试"该有的语义。
     * 只要这个上限被突破（历史上是 10 分钟），三轮重试就退化成"3 次瞬间空转"。
     */
    private static final long COOL_MAX_MS = 30_000L;

    /** 永久停用（模型被删、key 无效） */
    private static final long STOP_FOREVER = Long.MAX_VALUE;

    private static final int MIN_ATTEMPT_SECONDS = 5;
    private static final int DEFAULT_TIMEOUT = 120;

    /**
     * 单个候选最多能占用"当前剩余预算"的比例。
     *
     * <p>2026-10-11 实测改口径：原值 0.6 的目的是"防一个慢候选吃掉整轮"，
     * 但对付费主力（glm-5.3-flash，复杂图实测要 380s）来说，0.6 把首候选窗口
     * 压到 {@code 420×0.6 = 252s} ⇒ 每轮都在它还没写完时被掐断
     * （实测 2_order_flow：849s、三轮全灭、只剩占位符）。
     *
     * <p>提到 0.95 后（配合 MAX_ROUND_SECONDS=900）：首候选拿到
     * {@code min(900, 900×0.95) = 855s} ⇒ 主力一次能连续写 14 分钟，
     * 只要 120 秒内有新内容就永不判死（空闲判死见 OpenAiCompatibleProvider）。
     *
     * <p>代价：备用候选在单轮里基本分不到时间。在复杂图上这个代价为 0 ——
     * 备用（deepseek-v4.1-flash）自身需要 131s，分到 100s 本来也注定 0 产出。
     *
     * <p>⚠️ 注意本值处在 {@code min} 的最内层，它**只能往下压窗口**。
     * 候选自身的 {@code timeout-seconds}（application.yml）在更外层，
     * 无论写多大都抬不过本值算出的 fairShare —— 所以调窗口只能调本常量。
     */
    private static final double MAX_BUDGET_SHARE = 0.95;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 命中即判"请求太大"：这类错换任何候选都一样，必须最早判，见 {@link #classify} */
    private static final String[] CONTEXT_OVERFLOW_HINTS = {
            "context_length", "context length", "maximum context",
            "too many tokens", "too many input tokens",
            "input is too long", "prompt is too long",
            "reduce the length", "字符串超长", "输入长度超"};

    /** 命中即判"这个候选不支持该请求"：只停它，不停整条链（换家可能行） */
    private static final String[] CANDIDATE_DEAD_HINTS = {
            "has no permission", "does not exist", "model_not_found",
            "unsupported model", "permission denied"};

    /** 一次失败的完整判定结果 */
    static final class Verdict {
        final LlmFailureKind kind;
        final long cooldownMs;
        /** true = 立刻中断整条候选链（同一份 messages 再发给谁都一样的错） */
        final boolean abortChain;
        final String reason;

        Verdict(LlmFailureKind kind, boolean rateLimit,
                boolean abortChain, String reason) {
            this.kind = kind;
            // 冷却时长统一由 cooldownOf 查表得出，不在各处散写魔法数字
            this.cooldownMs = cooldownOf(kind, rateLimit);
            this.abortChain = abortChain;
            this.reason = reason;
        }
    }

    /**
     * 请求被拒（确定性失败）：上层收到它应改需求 / 缩规模，而不是继续重试。
     *
     * <p>单独一个类型是为了让调用方能把"该改需求"和"该等一会儿"分开。
     */
    public static class LlmRequestRejectedException extends Exception {
        public LlmRequestRejectedException(String message, Throwable cause) {
            super(message, cause);
        }
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
        final String vendor;
        final int tier;
        final int minRound;
        final int timeoutSeconds;
        final boolean largeOutput;
        final OpenAiCompatibleProvider client;

        Entry(LlmProperties.Candidate c) {
            this.name = c.getName();
            this.vendor = vendorOf(c.getBaseUrl());
            this.tier = c.getTier();
            this.minRound = c.getMinRound();
            this.timeoutSeconds = c.getTimeoutSeconds();
            this.largeOutput = c.isLargeOutput();
            this.client = new OpenAiCompatibleProvider(
                    c.getBaseUrl(), c.getApiKey(), c.getModel(),
                    c.getTimeoutSeconds(), false, "127.0.0.1", 7890,
                    c.isStream(), c.getIdleTimeoutSeconds(),
                    c.getMaxTokens());
        }
    }

    /**
     * 按 host 归厂商。
     *
     * <p>用途：同一家的多个候选在限流时往往一起倒（共享同一份账号配额），
     * 逐个重试纯属浪费一轮预算，见 {@link #chat} 里的同厂商跳过。
     */
    private static String vendorOf(String baseUrl) {
        if (isBlank(baseUrl)) {
            return "";
        }
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? baseUrl : host;
        } catch (Exception e) {
            return baseUrl;
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
        // 取消「强模型升档」设计：候选一律按 tier 升序全量参与，
        // 谁不可用（错误/冷却/超时/截断）就自动切下一个，恢复后再被优先选回。
        // minRound 字段不再用于路由（配置里留着无害）。
        long deadline = System.currentTimeMillis()
                + timeoutSeconds * 1000L;

        List<Entry> order = new ArrayList<>(entries); // tier 升序，全量
        LOG.info("[Failover] 候选 {} 个（tier 升序，纯故障转移）: {}",
                order.size(),
                order.stream().map(x -> x.name + "(t" + x.tier + ")")
                        .toList());

        Exception last = null;
        boolean truncated = false;
        // 本次调用内已被判"额度/限流"的厂商：同家剩余候选一起跳过
        Set<String> deadVendors = new HashSet<>();
        Verdict abort = null;

        while (!order.isEmpty()) {
            if (!deadVendors.isEmpty() && order.stream()
                    .anyMatch(e -> !deadVendors.contains(e.vendor))) {
                // 只在"还剩别家可试"时才跳，否则宁可把同家候选再试一遍
                int before = order.size();
                order.removeIf(e -> deadVendors.contains(e.vendor));
                if (order.size() < before) {
                    LOG.info("[Failover] 跳过厂商 {} 的剩余候选 {} 个",
                            deadVendors, before - order.size());
                }
            }
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
                // 2026-10-12 修复「空内容不判失败」：模型返回 null / 空白正文时，
                // 绝不能当成功回给上层（否则前端拿到空画布）。
                // 按失败处理并冷却，让坏候选被隔离，下一轮重试优先选别家。
                if (out == null || out.isBlank()) {
                    last = new IllegalStateException(
                            "候选 " + e.name + " 返回空内容");
                    LOG.warn("[Failover] 候选 {} 返回空内容，"
                            + "按失败处理并冷却，改换下一候选", e.name);
                    applyCooldown(e, withJitter(COOL_TEMP_MS), "空内容");
                    continue;
                }
                LOG.info("[Failover] 候选 {} 命中，耗时 {} ms",
                        e.name, System.currentTimeMillis() - t0);
                return out;
            } catch (OpenAiCompatibleProvider
                    .TruncatedResponseException te) {
                truncated = true;
                last = te;
                // 2026-10-12 修复「截断分支不打冷却」：finish_reason=length
                // （如 deepseek 把额度全花在草稿上、正文 0 字符的"白纸"）走这里，
                // 原实现只置 truncated 标、不冷却 ⇒ 坏候选下次仍第一个上、重复白烧。
                // 现在同样冷却，使其在下个请求里被隔离
                // （本轮仍优先换「大输出」候选重试）。
                LOG.warn("[Failover] 候选 {} 输出被截断，按失败冷却并"
                        + "改换「大输出」候选重试", e.name);
                applyCooldown(e, withJitter(COOL_TEMP_MS), "输出截断");
            } catch (OpenAiCompatibleProvider.LlmHttpException he) {
                last = he;
                Verdict v = classify(he.status, he.body);
                if (v.cooldownMs > 0) {
                    applyCooldown(e, withJitter(v.cooldownMs),
                            v.reason + "(" + v.kind + ")");
                } else {
                    LOG.warn("[Failover] 候选 {} 失败({})，不冷却："
                                    + "误冷却等同于凭空摘掉一个健康候选",
                            e.name, v.reason);
                }
                if (v.kind == LlmFailureKind.QUOTA) {
                    deadVendors.add(e.vendor);
                }
                if (v.abortChain) {
                    abort = v;
                    LOG.warn("[Failover] 判定为确定性失败({})，中断候选链："
                                    + "同一份请求换任何候选都是一样的错，"
                                    + "继续试只会白烧时间窗",
                            v.reason);
                    break;
                }
            } catch (Exception ex) {
                last = ex;
                applyCooldown(e, withJitter(COOL_TEMP_MS),
                        ex.getClass().getSimpleName());
            }
        }
        if (abort != null) {
            throw new LlmRequestRejectedException(
                    "LLM 请求被拒：" + abort.reason
                            + "（重试无用，请缩短需求或降低规模）", last);
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

    private static boolean containsAny(String body, String... keys) {
        for (String k : keys) {
            if (body.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取厂商错误码（如智谱 1113 余额不足 / 1305 过载）。
     *
     * <p>刻意不用 {@code body.contains("1113")}：那是**全文子串匹配**，
     * request_id、时间戳、token 计数里出现这四个数字就会被误判成余额不足，
     * 从而把健康候选冷却 10 分钟。只认 {@code error.code} 字段。
     *
     * @return 厂商 code；取不到返回 null（调用方按"认不出"保守处理）
     */
    private static String vendorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            var root = MAPPER.readTree(body);
            var err = root.path("error");
            var code = err.has("code") ? err.get("code") : root.get("code");
            if (code != null && !code.isNull()) {
                return code.asText();
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体（网关 HTML 错误页之类）→ 当认不出处理
        }
        return null;
    }

    /** 冷却时长只看错误性质，不看状态码；结果统一受 {@link #COOL_MAX_MS} 封顶。 */
    private static long cooldownOf(LlmFailureKind kind, boolean rateLimit) {
        if (kind == LlmFailureKind.QUOTA && rateLimit) {
            return COOL_RATE_MS;
        }
        long ms = switch (kind) {
            case DETERMINISTIC -> 0L;
            case TRANSIENT -> COOL_TEMP_MS;
            case QUOTA -> COOL_QUOTA_MS;
            case PERMANENT -> STOP_FOREVER;
        };
        // 统一封顶：只有"永久停用"能突破上限，其余一律 ≤ COOL_MAX_MS。
        // 放在这里而不是各常量上，是为了让"上限"只有一个来源 ——
        // 以后调任何一档的时长都不会不小心越过它。
        return ms >= STOP_FOREVER ? ms : Math.min(ms, COOL_MAX_MS);
    }

    /**
     * 唯一的错误分类入口（2026-10-10）。
     *
     * <p>顺序是刻意的：<b>先读 body 语义、再按状态码兜底</b>。
     * 厂商常把"上下文超长"返回成 400 甚至 429，若按状态码先判，
     * 就会被误归到限流 / 额度，整条候选链被误冷却 —— 这正是 P0 级事故。
     *
     * @param status HTTP 状态码
     * @param body   响应体（可能为 null）
     * @return 判定结果：性质 + 冷却时长 + 是否中断整条链
     */
    static Verdict classify(int status, String body) {
        String b = body == null ? "" : body.toLowerCase();

        // ① 请求本身太大：换谁都不行，且绝不能冷却任何候选
        if (containsAny(b, CONTEXT_OVERFLOW_HINTS)) {
            return new Verdict(LlmFailureKind.DETERMINISTIC, false, true,
                    "上下文超长(HTTP " + status + ")");
        }
        // ② 这个候选不支持该请求：只停它（换家可能行），不停整条链
        if (containsAny(b, CANDIDATE_DEAD_HINTS)) {
            return new Verdict(LlmFailureKind.PERMANENT, false, false,
                    "候选不支持该请求(HTTP " + status + ")");
        }
        if (status == 404) {
            return new Verdict(LlmFailureKind.PERMANENT, false, false,
                    "模型不存在(404)");
        }
        if (status == 401 || status == 402 || status == 403) {
            return new Verdict(LlmFailureKind.QUOTA, false, false,
                    "鉴权/额度(HTTP " + status + ")");
        }
        if (status == 429) {
            // 429 有两种语义、只能靠厂商 code 分（2026-10-07 实测）：
            //   智谱 code=1113「余额不足或无可用资源包」= 额度问题 → 长冷却
            //   智谱 code=1305「该模型当前访问量过大」= 真限流     → 短冷却
            String code = vendorCode(body);
            if ("1113".equals(code)) {
                return new Verdict(LlmFailureKind.QUOTA, false, false,
                        "余额不足(code=1113)");
            }
            if ("1305".equals(code)) {
                return new Verdict(LlmFailureKind.QUOTA, true, false,
                        "访问量过大(code=1305)");
            }
            // 认不出厂商 code：保守当瞬时错误，宁可多试一次
            return new Verdict(LlmFailureKind.TRANSIENT, false, false,
                    "429 未知语义(code=" + code + ")");
        }
        if (status >= 500) {
            return new Verdict(LlmFailureKind.TRANSIENT, false, false,
                    "服务端错误(HTTP " + status + ")");
        }
        // 其余 4xx：重试通常没用，所以不冷却候选；但**不中断整条链** ——
        // 因为 4xx 里混着"厂商特性差异"（如某家不吃 max_tokens 这个值、
        // 某家不支持某个可选参数），换一家可能就通了。只有"上下文超长"
        // 那种与候选无关的错才值得直接中断（见上面第 ① 条）。
        return new Verdict(LlmFailureKind.DETERMINISTIC, false, false,
                "请求被拒(HTTP " + status + ")");
    }

    /**
     * 冷却时长加**双向抖动**（±30%）。
     *
     * <p>为什么要抖动：多个候选被同时冷却时，若时长完全一致，它们会在**同一时刻**
     * 一起恢复、又被下一次请求一起打中，形成二次冲击。
     * 把恢复时刻抖开，这次冲击就被摊平了。
     *
     * <p>2026-10-11 由"只加不减的 0~10%"改成"双向 ±30%"：配合总量封顶 30 秒之后，
     * 需要的相对错开幅度更大；且正向抖动本来就会被 {@link #COOL_MAX_MS} 截断，
     * 只有双向才能让"接近上限"的那一档也有真实错开。下限保 1 秒。
     */
    private static long withJitter(long coolMs) {
        if (coolMs <= 0 || coolMs >= STOP_FOREVER) {
            return coolMs;
        }
        long span = Math.max(1_000L, (long) (coolMs * 0.3));
        long jittered = coolMs
                + ThreadLocalRandom.current().nextLong(-span, span + 1);
        return Math.max(1_000L, Math.min(COOL_MAX_MS, jittered));
    }
}
