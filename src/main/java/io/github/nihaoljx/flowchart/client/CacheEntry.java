package io.github.nihaoljx.flowchart.client;

/**
 * 缓存条目（任务35新增）
 * value = LLM 返回的纯文本；expireAt = 过期时间戳（毫秒）
 */
public record CacheEntry(String value, long expireAt) {
    boolean isExpired(long now) {
        return now >= expireAt;
    }
}
