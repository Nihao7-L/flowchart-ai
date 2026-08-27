package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.config.LlmProperties;
import io.github.nihaoljx.flowchart.model.UsageRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 成本统计服务（任务31核心）
 *
 * 职责：
 * 1. 接收 Provider 上报的每次调用用量（record）
 * 2. 按模型单价表估算成本
 * 3. 提供 /api/stats 需要的汇总数据
 *
 * 为什么用内存（ConcurrentHashMap）而不是数据库？
 * - 单机 Demo 数据量小，内存完全够
 * - 免去引入数据库的复杂度
 * - 代价：重启后统计清零（可接受，以后要持久化换 SQLite 即可）
 *
 * 为什么用 ConcurrentHashMap + AtomicLong 而不是普通 HashMap？
 * - HTTP 请求是并发的，多个请求同时 record 会竞争同一个结构
 * - ConcurrentHashMap 的读写内部加锁，线程安全
 * - AtomicLong.incrementAndGet() 原子自增，保证 id 不重复
 */
@Service
public class UsageService {

    /** 模型单价表：模型名 → 每百万 token 价格（元） */
    private final Map<String, Double> pricingPerMillion;

    /** 所有用量记录：id → 记录。ConcurrentHashMap = 线程安全的 HashMap */
    private final Map<Long, UsageRecord> records = new ConcurrentHashMap<>();
    /** 任务35：缓存命中/未命中计数 + 估算节省成本 */
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private volatile double estimatedSavedCost = 0.0;
    /** 最近一次真实的单次平均成本（用于命中时估算省了多少） */
    private volatile double lastAvgCost = 0.0;

    /** 自增 id 生成器：保证每条记录有唯一编号 */
    private final AtomicLong sequence = new AtomicLong();

    /** 构造器注入：Spring 自动把 LlmProperties（配置绑定后的 Bean）传进来 */
    public UsageService(LlmProperties llmProperties) {
        this.pricingPerMillion = llmProperties.getPricing();
    }

    /**
     * 记录一次调用（Provider 拿到 usage 后调用）
     */
    public void record(String model, int promptTokens, int completionTokens) {
        int totalTokens = promptTokens + completionTokens;
        double cost = estimateCost(model, totalTokens);
        records.put(sequence.incrementAndGet(),
                new UsageRecord(Instant.now(), model, promptTokens, completionTokens, totalTokens, cost));
        // 更新"最近平均单次成本"，供缓存命中时估算省钱（任务35）
        long totalReq = sequence.get();
        if (totalReq > 0) {
            this.lastAvgCost = records.values().stream().mapToDouble(UsageRecord::estimatedCost).sum() / totalReq;
        }

    }
    /**
     * 任务35：记录一次缓存命中。
     * 省钱按"最近平均单次成本"估算（简单、稳定，不依赖单次明细）。
     */
    public void recordCacheHit() {
        cacheHits.incrementAndGet();
        estimatedSavedCost += lastAvgCost;
    }

    /**
     * 估算成本：totalTokens / 100万 × 每百万单价
     *
     * 例：模型单价 12 元/百万 token，本次用了 25 万 token
     *     成本 = 250000 / 1000000 × 12 = 3.0 元
     */
    private double estimateCost(String model, int totalTokens) {
        // 先查这个模型的单价，没有就 fallback 到 default，还没有就 0（免费）
        double price = pricingPerMillion.getOrDefault(model,
                pricingPerMillion.getOrDefault("default", 0.0));
        return totalTokens / 1_000_000.0 * price;
    }

    /**
     * 汇总统计（/api/stats 接口直接返回这个 Map）
     *
     * 返回结构：
     * {
     *   "totalRequests": 5,          // 累计调用次数
     *   "promptTokens": 12345,       // 累计输入 token
     *   "completionTokens": 6789,    // 累计输出 token
     *   "totalTokens": 19134,        // 累计总 token
     *   "estimatedCost": 0.23,       // 累计估算成本（元）
     *   "currency": "CNY",
     *   "byModel": [                 // 按模型分组明细
     *     { "model": "...", "requests": 5, "totalTokens": 19134, "estimatedCost": 0.23 }
     *   ]
     * }
     */
    public Map<String, Object> getStats() {
        // 快照：把所有记录取出来拷贝一份，避免遍历时被并发修改
        List<UsageRecord> snapshot = records.values().stream().toList();

        int totalRequests = snapshot.size();
        int promptTokens = snapshot.stream().mapToInt(UsageRecord::promptTokens).sum();
        int completionTokens = snapshot.stream().mapToInt(UsageRecord::completionTokens).sum();
        int totalTokens = snapshot.stream().mapToInt(UsageRecord::totalTokens).sum();
        double totalCost = snapshot.stream().mapToDouble(UsageRecord::estimatedCost).sum();

        // 按模型分组统计
        Map<String, List<UsageRecord>> byModel = snapshot.stream()
                .collect(Collectors.groupingBy(UsageRecord::model));

        List<Map<String, Object>> modelStats = byModel.entrySet().stream()
                .map(entry -> {
                    List<UsageRecord> list = entry.getValue();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("model", entry.getKey());
                    m.put("requests", list.size());
                    m.put("promptTokens", list.stream().mapToInt(UsageRecord::promptTokens).sum());
                    m.put("completionTokens", list.stream().mapToInt(UsageRecord::completionTokens).sum());
                    m.put("totalTokens", list.stream().mapToInt(UsageRecord::totalTokens).sum());
                    m.put("estimatedCost", round2(list.stream().mapToDouble(UsageRecord::estimatedCost).sum()));
                    return m;
                })
                .sorted(Comparator.comparingDouble(
                        (Map<String, Object> m) -> (double) m.get("estimatedCost")).reversed())
                .collect(Collectors.toList());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRequests", totalRequests);
        stats.put("promptTokens", promptTokens);
        stats.put("completionTokens", completionTokens);
        stats.put("totalTokens", totalTokens);
        stats.put("estimatedCost", round2(totalCost));
        stats.put("currency", "CNY");
        stats.put("byModel", modelStats);

        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long totalCache = hits + misses;
        double hitRate = totalCache == 0 ? 0.0 : (double) hits / totalCache;
        stats.put("cacheHits", hits);
        stats.put("cacheMisses", misses);
        stats.put("cacheHitRate", round2(hitRate));
        stats.put("estimatedSavedCost", round2(estimatedSavedCost));

        return stats;
    }

    /** 保留两位小数，避免浮点误差展示一长串（0.229999999 → 0.23） */
    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
