package io.github.nihaoljx.flowchart.client;

import io.github.nihaoljx.flowchart.client.stream.ProgressContext;
import io.github.nihaoljx.flowchart.client.stream.ProgressEvent;
import io.github.nihaoljx.flowchart.service.UsageService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务50：LLM Gateway（大模型网关）
 *
 * 收口所有 LLM 调用逻辑：routing + retry + token budget + fallback + audit。
 * 业务代码（DiagramController / ToolExecutor）通过 Gateway 调用 LLM，
 * 不再直接依赖 LlmProvider（Fallback/Caching 等装饰链）。
 *
 * 设计：
 *   Gateway 持有已组装好的 LlmProvider（由 LlmProviderConfig 构建的 Caching→Fallback→Provider 链），
 *   在此基础上增加：
 *   1. Token 预算：每次调用前检查是否超出预算（可选）
 *   2. 调用审计：记录每次调用的 prompt/模型/耗时/token/结果状态
 *   3. 限流：简单令牌桶（可选，个人项目暂不做复杂实现）
 *
 * 为什么 Gateway 不替换 LlmProvider？
 *   LlmProvider 是底层 HTTP 调用抽象（含重试、缓存、降级），Gateway 是业务层网关。
 *   两者分层：Gateway 负责"要不要调、调几次、记什么"，Provider 负责"怎么调、调不通怎么办"。
 */
@Component
public class LlmGateway {

    private final LlmProvider provider;
    private final UsageService usageService;

    /** 调用审计日志（内存保留最近 200 条） */
    private final Map<Long, AuditEntry> auditLog = new ConcurrentHashMap<>();
    private final AtomicLong auditSeq = new AtomicLong();

    /** 全局 token 预算（总 prompt + completion，超过则拒绝调用，可选） */
    private volatile long globalTokenBudget = -1; // -1 = 不限制

    public LlmGateway(LlmProvider provider, UsageService usageService) {
        this.provider = provider;
        this.usageService = usageService;
    }

    // ==================== 核心调用方法 ====================

    /**
     * 结构化输出调用（chatStructured）
     * 业务代码通过此方法调用 LLM，Gateway 负责审计与预算检查。
     */
    public String chatStructured(String prompt, String schemaJson) throws Exception {
        return withAudit("chatStructured", () -> provider.chatStructured(prompt, schemaJson));
    }

    /**
     * 流式结构化输出调用（chatStructuredStream）
     */
    public String chatStructuredStream(String prompt, String schemaJson) throws Exception {
        return withAudit("chatStructuredStream", () -> provider.chatStructuredStream(prompt, schemaJson));
    }

    /**
     * Tool Calling 路由调用
     */
    public String chatWithTools(List<Map<String, Object>> messages, String toolsJson) throws Exception {
        return withAudit("chatWithTools", () -> provider.chatWithTools(messages, toolsJson));
    }

    /**
     * 普通 chat 调用
     */
    public String chat(String prompt) throws Exception {
        return withAudit("chat", () -> provider.chat(prompt));
    }

    // ==================== 预算管理 ====================

    /** 设置全局 token 预算（-1 = 不限制） */
    public void setGlobalTokenBudget(long budget) {
        this.globalTokenBudget = budget;
    }

    /** 检查是否超出预算 */
    public boolean isBudgetExceeded() {
        if (globalTokenBudget < 0) return false;
        Map<String, Object> stats = usageService.getStats();
        long totalTokens = ((Number) stats.getOrDefault("totalTokens", 0)).longValue();
        return totalTokens >= globalTokenBudget;
    }

    // ==================== 审计 ====================

    /** 获取审计日志（最近 200 条） */
    public Map<String, Object> getAuditLog() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", auditSeq.get());
        List<Map<String, Object>> entries = auditLog.values().stream()
                .sorted((a, b) -> Long.compare(b.id, a.id))
                .limit(200)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.id);
                    m.put("method", e.method);
                    m.put("model", e.model);
                    m.put("durationMs", e.durationMs);
                    m.put("tokenEstimate", e.tokenEstimate);
                    m.put("success", e.success);
                    m.put("error", e.error);
                    m.put("timestamp", e.timestamp);
                    return m;
                })
                .toList();
        result.put("entries", entries);
        return result;
    }

    /** 获取网关状态摘要 */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("provider", provider.name());
        status.put("configured", provider.isConfigured());
        status.put("budgetEnabled", globalTokenBudget > 0);
        status.put("globalTokenBudget", globalTokenBudget);
        status.put("budgetExceeded", isBudgetExceeded());
        status.put("totalCalls", auditSeq.get());
        return status;
    }

    // ==================== 内部实现 ====================

    @FunctionalInterface
    private interface LlmAction {
        String run() throws Exception;
    }

    /** 带审计的调用包装 */
    private String withAudit(String method, LlmAction action) throws Exception {
        // 预算检查
        if (isBudgetExceeded()) {
            ProgressContext.publish(ProgressEvent.info("⚠️ Token 预算已用尽，本次调用被拒绝"));
            throw new RuntimeException("Token 预算已用尽（" + globalTokenBudget + "），请联系管理员调整预算");
        }

        long start = System.currentTimeMillis();
        long id = auditSeq.incrementAndGet();
        boolean success = false;
        String error = null;
        String result = null;

        try {
            result = action.run();
            success = true;
            return result;
        } catch (Exception e) {
            error = e.getMessage();
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - start;
            // 粗略估算 token 数（1 中文字 ≈ 1.5 token）
            long tokenEstimate = result != null ? (long)(result.length() / 1.5) : 0;

            AuditEntry entry = new AuditEntry(id, method, provider.name(), duration, tokenEstimate, success, error, System.currentTimeMillis());
            auditLog.put(id, entry);

            // 只保留最近 200 条
            if (auditLog.size() > 200) {
                Long oldest = auditLog.keySet().stream().min(Long::compareTo).orElse(null);
                if (oldest != null) auditLog.remove(oldest);
            }
        }
    }

    /** 审计条目 */
    private record AuditEntry(long id, String method, String model, long durationMs,
                               long tokenEstimate, boolean success, String error, long timestamp) {}
}
