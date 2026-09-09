package io.github.nihaoljx.flowchart.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务51：可观测性与 Trace（调用链追踪）
 *
 * 职责：记录每次请求的调用链（LLM 调用 / 检索 / 渲染）及各步骤耗时。
 * 对应 Harness L5 评估与观测层 + JavaGuide "可观测性与 Trace"。
 *
 * 设计：
 *   - 一次请求 = 一个 Trace（含多个 Span）
 *   - Span 记录：步骤名、耗时、状态（ok/error）、附加信息
 *   - 内存保留最近 500 条 Trace，超过自动淘汰最老的
 *   - /api/metrics 端点返回汇总看板（平均耗时、失败率、总调用数）
 *
 * 为什么不用 Micrometer / Zipkin？
 *   个人项目，引入完整 Trace 框架成本高且收益有限。
 *   内存版 Trace 足以演示"会做可观测性"的能力，面试时讲"接入 Zipkin/Micrometer 只需换实现"即可。
 */
@Service
public class TraceService {

    /** 每个请求的 Trace 容器（最多保留 500 条） */
    private final Map<String, Trace> traces = new LinkedHashMap<>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Trace> eldest) {
            return size() > 500;
        }
    };
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();

    /** 开始一个 Trace（返回 traceId） */
    public String startTrace(String type) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        Trace t = new Trace(traceId, type, System.currentTimeMillis());
        synchronized (traces) {
            traces.put(traceId, t);
        }
        totalRequests.incrementAndGet();
        return traceId;
    }

    /** 记录一个 Span（步骤） */
    public void recordSpan(String traceId, String spanName, long durationMs, boolean success, String detail) {
        synchronized (traces) {
            Trace t = traces.get(traceId);
            if (t != null) {
                t.spans.add(new Span(spanName, durationMs, success, detail, System.currentTimeMillis()));
                if (!success) t.error = true;
            }
        }
        if (!success) totalErrors.incrementAndGet();
    }

    /** 结束一个 Trace */
    public void endTrace(String traceId) {
        synchronized (traces) {
            Trace t = traces.get(traceId);
            if (t != null) {
                t.endMs = System.currentTimeMillis();
                t.totalMs = t.endMs - t.startMs;
            }
        }
    }

    /** 获取单个 Trace 详情 */
    public Trace getTrace(String traceId) {
        synchronized (traces) {
            return traces.get(traceId);
        }
    }

    /** 获取最近 N 条 Trace */
    public List<Trace> getRecentTraces(int limit) {
        synchronized (traces) {
            return new ArrayList<>(traces.values()).stream()
                    .sorted((a, b) -> Long.compare(b.startMs, a.startMs))
                    .limit(limit)
                    .toList();
        }
    }

    /** 获取汇总指标（/api/metrics 用） */
    public Map<String, Object> getMetrics() {
        synchronized (traces) {
            List<Trace> all = new ArrayList<>(traces.values());
            long total = totalRequests.get();
            long errors = totalErrors.get();
            double avgMs = all.isEmpty() ? 0 : all.stream().mapToLong(t -> t.totalMs).average().orElse(0);
            double avgSpans = all.isEmpty() ? 0 : all.stream().mapToInt(t -> t.spans.size()).average().orElse(0);

            // 按类型分组
            Map<String, Long> byType = all.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            t -> t.type, java.util.stream.Collectors.counting()));

            // 按步骤名统计平均耗时
            Map<String, double[]> bySpan = new HashMap<>();
            for (Trace t : all) {
                for (Span s : t.spans) {
                    bySpan.computeIfAbsent(s.name, k -> new double[]{0, 0})[0] += s.durationMs;
                    bySpan.computeIfAbsent(s.name, k -> new double[]{0, 0})[1]++;
                }
            }
            Map<String, Map<String, Object>> spanStats = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> e : bySpan.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("totalCalls", (long) e.getValue()[1]);
                m.put("avgMs", Math.round(e.getValue()[0] / e.getValue()[1]));
                spanStats.put(e.getKey(), m);
            }

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("totalRequests", total);
            metrics.put("totalErrors", errors);
            metrics.put("errorRate", total == 0 ? 0.0 : Math.round((double) errors / total * 1000.0) / 10.0);
            metrics.put("avgRequestMs", Math.round(avgMs));
            metrics.put("avgSpansPerRequest", Math.round(avgSpans * 10.0) / 10.0);
            metrics.put("tracesStored", all.size());
            metrics.put("byType", byType);
            metrics.put("bySpan", spanStats);
            return metrics;
        }
    }

    /** Trace 数据结构 */
    public static class Trace {
        public final String id;
        public final String type;
        public final long startMs;
        public long endMs;
        public long totalMs;
        public boolean error;
        public final List<Span> spans = new ArrayList<>();

        public Trace(String id, String type, long startMs) {
            this.id = id;
            this.type = type;
            this.startMs = startMs;
        }
    }

    /** Span（步骤）数据结构 */
    public record Span(String name, long durationMs, boolean success, String detail, long timestamp) {}
}
