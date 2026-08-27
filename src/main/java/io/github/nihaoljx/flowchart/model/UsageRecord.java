package io.github.nihaoljx.flowchart.model;

import java.time.Instant;

/**
 * 单次 LLM 调用的用量记录（任务31新增）
 *
 * 每次调用 LLM 成功后，把响应里的 usage 字段提取出来存成一条记录。
 * 一条记录 = 一次调用的完整账目。
 *
 * @param timestamp        调用时间（Instant 是 Java 8+ 的时间戳，线程安全）
 * @param model            用的哪个模型（价格和模型绑定）
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param totalTokens      总 token 数
 * @param estimatedCost    估算成本（元），由 UsageService 按单价表计算
 */
public record UsageRecord(
        Instant timestamp,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        double estimatedCost
) {}
