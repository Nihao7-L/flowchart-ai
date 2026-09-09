package io.github.nihaoljx.flowchart.client;

import io.github.nihaoljx.flowchart.client.stream.ProgressContext;
import io.github.nihaoljx.flowchart.client.stream.ProgressEvent;
import io.github.nihaoljx.flowchart.service.UsageService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结果缓存装饰器（任务35核心）
 *
 * 包在 FallbackLlmProvider 最外层：相同输入（规范化后）直接返回缓存，
 * 不往下走 Fallback 和真正的 HTTP 调用 → 秒回 + 省钱。
 *
 * 设计：
 * - key = SHA256( 规范化(prompt) + "|" + schema )，保证"改一个字重试"也能命中
 * - value = chatStructured 返回的纯文本
 * - TTL 惰性过期：读时判断 now>=expireAt 就当没命中，顺手删掉
 * - 命中计数委托给 UsageService，/api/stats 能看到省了多少
 */
public class CachingLlmProvider implements LlmProvider {

    private final LlmProvider delegate;          // 下层：FallbackLlmProvider
    private final UsageService usageService;      // 可能为 null（单测）
    private final long ttlMillis;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CachingLlmProvider(LlmProvider delegate, UsageService usageService, long ttlMillis) {
        this.delegate = delegate;
        this.usageService = usageService;
        this.ttlMillis = ttlMillis;
    }

    @Override
    public boolean isConfigured() {
        return delegate.isConfigured();
    }

    @Override
    public String name() {
        return "cache(" + delegate.name() + ")";
    }

    @Override
    public String chat(String prompt) throws Exception {
        // 普通 chat 不走结构化 schema，仍按缓存逻辑但 schema 部分为空
        return withCache(prompt, "", () -> delegate.chat(prompt));
    }

    @Override
    public String chatStructured(String prompt, String schemaJson) throws Exception {
        return withCache(prompt, schemaJson, () -> delegate.chatStructured(prompt, schemaJson));
    }

    @Override
    public String chatWithTools(List<Map<String, Object>> messages, String toolsJson) throws Exception {
        // tool calling 不进缓存：结果依赖模型实时决策，messages 结构复杂不便做 key
        return delegate.chatWithTools(messages, toolsJson);
    }

    @Override
    public String chatStructuredStream(String prompt, String schemaJson) throws Exception {
        // 任务39+：流式用于实时展示"思考过程"，不进缓存（缓存会瞬回，失去逐字滚动的效果）
        return delegate.chatStructuredStream(prompt, schemaJson);
    }

    private String withCache(String prompt, String schema, CacheAction action) throws Exception {
        String key = hash(normalize(prompt) + "|" + (schema == null ? "" : schema));
        long now = System.currentTimeMillis();

        CacheEntry hit = cache.get(key);
        if (hit != null && !hit.isExpired(now)) {
            if (usageService != null) {
                usageService.recordCacheHit();   // 命中 +1，并在内部估算省钱
            }
            // 任务37：命中即短路，没有真实 Token 消耗，推一条 cache_hit（前端大数字显示 0）
            ProgressContext.publish(ProgressEvent.of("cache_hit",
                    "命中缓存，直接返回（省了一笔调用）", delegate.name(),
                    Map.of("promptTokens", 0, "completionTokens", 0, "totalTokens", 0)));
            return hit.value();
        }

        // 未命中（或已过期）：调下层真正生成，再写回缓存
        if (hit != null) {
            cache.remove(key);                   // 清掉过期项
        }
        String value = action.run();
        cache.put(key, new CacheEntry(value, now + ttlMillis));
        return value;
    }

    /** 规范化：trim + 合并连续空白，降低"改一个字/多空格"造成的未命中 */
    private String normalize(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ");
    }

    /** SHA256 成 64 位十六进制，作为缓存 key（避免超长 prompt 当 key） */
    private String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 理论上不会走到（SHA-256 是 JVM 内置），兜底用原串
            return s;
        }
    }

    @FunctionalInterface
    private interface CacheAction {
        String run() throws Exception;
    }
}
