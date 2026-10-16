package io.github.nihaoljx.flowchart.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AgentLlmClient 的 OpenAI 兼容实现
 *
 * 协议口径（OpenAI Chat Completions + function calling）：
 *
 * - assistant 消息带 `tool_calls` 数组（id/type/function.name/arguments）；
 *
 * - tool 结果消息带 `tool_call_id` + `content`；
 *
 * - 响应取 `choices[0].message`（非流式）或 `choices[0].delta` 逐片累积
 *      （流式）的 `content` 与 `tool_calls`；`reasoning_content`（推理草稿）
 *      刻意忽略 —— 只取正文与工具调用，草稿不进上下文（但**流式下它算"活跃
 *      信号"**：推理模型思考时正是靠不停吐 reasoning token 表示"我没卡"）。
 *
 * 超时两把尺子（2026-10-16 接上配置里早已定义、却被硬编码阉割的机制）：
 *
 * - **空闲判死（主判据，仅流式）**：连续 {@code idleTimeoutSeconds} 秒没有
 *   任何新分片（含 reasoning_content）⇒ 认为模型卡死，看门狗关闭连接、抛
 *   {@link HttpTimeoutException}（外层 FailoverAgentClient 据此冷却该候选）。
 *   这把尺子让"慢但还在吐字的大图"能一直等（实测 GLM 画复杂图思考阶段持续
 *   吐 reasoning token，可达数分钟），又能在真卡死时快速切断、不白烧。
 *
 * - **总时限（兜底）**：{@code HttpRequest.timeout(timeoutSeconds)}，
 *   timeoutSeconds 由 AgentLoop 按剩余总预算折算后传入 —— 即便模型一直在
 *   吐字（永不空闲），也有个绝对天花板，防止无限跑。
 *
 * 非流式（候选 {@code stream:false} 时）：只有总时限一把尺子，没有空闲判死
 * —— 非流式下"慢"和"卡死"都表现为"半天没响应"，无法区分，大图易被误杀。
 *
 * 范围：单候选、无重试 —— 候选链故障转移由外层 FailoverAgentClient 承担。
 */
public final class OpenAiToolClient implements AgentLlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Logger LOG =
            LoggerFactory.getLogger(OpenAiToolClient.class);

    /** 看门狗轮询间隔：不需要很密，空闲判死是秒级容忍 */
    private static final long WATCHDOG_TICK_MS = 2_000L;

    /** 流式进度日志间隔：每隔这么久打一行"还在收"，供实时监测（不刷屏） */
    private static final long PROGRESS_LOG_MS = 15_000L;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    /** 是否流式（默认 true，见 LlmProperties.Candidate.stream） */
    private final boolean stream;
    /** 空闲判死阈值（秒）：连续这么久没有新分片就认为卡死（流式主判据） */
    private final int idleTimeoutSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public OpenAiToolClient(String baseUrl, String apiKey,
                            String model, int maxTokens,
                            boolean stream, int idleTimeoutSeconds) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.stream = stream;
        this.idleTimeoutSeconds =
                idleTimeoutSeconds > 0 ? idleTimeoutSeconds : 120;
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
        HttpRequest request = buildRequest(messages, tools,
                timeoutSeconds, toolChoice);
        HttpResponse<InputStream> resp =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        if (status != 200) {
            // 错误体也要读出来（含 429/5xx 的语义），否则 FailoverAgentClient
            // 看不到 body 就无法按 error.code 分类
            String errBody = readAll(resp.body(), 2000);
            throw new IllegalStateException("LLM HTTP " + status
                    + ": " + errBody.substring(
                    0, Math.min(300, errBody.length())));
        }
        return stream
                ? respondStreaming(resp.body(), timeoutSeconds)
                : respondNonStreaming(resp.body());
    }

    private HttpRequest buildRequest(List<AgentMessage> messages,
                                     List<Map<String, Object>> tools,
                                     int timeoutSeconds,
                                     String toolChoice) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", encode(messages));
        payload.put("tools", tools);
        payload.put("tool_choice",
                toolChoice == null || toolChoice.isBlank()
                        ? "auto" : toolChoice);
        payload.put("stream", stream);
        if (maxTokens > 0) {
            payload.put("max_tokens", maxTokens);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        MAPPER.writeValueAsString(payload)))
                .build();
    }

    /**
     * 非流式：读完整响应体，一次性 decode。只有总时限一把尺子。
     */
    private AgentTurn respondNonStreaming(InputStream body) throws Exception {
        String text = readAll(body, (int) Duration.ofSeconds(60).toMillis());
        return decode(MAPPER.readTree(text));
    }

    /**
     * 流式：逐行读 SSE，累积 content 与 tool_calls，带空闲判死看门狗。
     *
     * tool_calls 的 arguments 是分片到达的（按 index 归属），必须拼接；
     * id / name 通常在首片给一次。reasoning_content 只当活跃信号、不进结果。
     */
    private AgentTurn respondStreaming(InputStream body, int timeoutSeconds)
            throws Exception {
        final long startMs = System.currentTimeMillis();
        final long idleMs = idleTimeoutSeconds * 1000L;
        final AtomicLong lastActivity = new AtomicLong(startMs);
        final AtomicBoolean idleAbort = new AtomicBoolean(false);
        final AtomicBoolean finished = new AtomicBoolean(false);

        Thread watchdog = new Thread(() -> {
            try {
                while (!finished.get() && !idleAbort.get()) {
                    Thread.sleep(WATCHDOG_TICK_MS);
                    long now = System.currentTimeMillis();
                    if (now - lastActivity.get() > idleMs) {
                        idleAbort.set(true);
                        // 关连接 → 阻塞中的 readLine 抛 IO 异常 → 上层转超时
                        try {
                            body.close();
                        } catch (Exception ignore) {
                            // 关失败也无妨：总时限 .timeout() 仍会兜底
                        }
                        return;
                    }
                    if (now - startMs > timeoutSeconds * 1000L) {
                        // 双保险：总时限到（.timeout 之外的显式墙）
                        idleAbort.set(true);
                        try {
                            body.close();
                        } catch (Exception ignore) {
                            // 同上
                        }
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();

        // 流式累积器：content 拼接 + tool_calls 按 index 分片拼接（见 SseAccumulator）
        SseAccumulator acc = new SseAccumulator();
        long lastLogMs = startMs;
        int chunkCount = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity.set(System.currentTimeMillis());
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode chunk = MAPPER.readTree(data);
                JsonNode choices = chunk.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode delta = choices.get(0).path("delta");
                // reasoning_content 只作活跃信号（上面已刷新 lastActivity），
                // 不累积进 content（契约：草稿不进上下文）；content 与
                // tool_calls 分片由 acc.feed 累积。
                acc.feed(delta);
                // 流式输出监测：每 15s 打一行进度，供实时观察"还在吐字、
                // 进度在走"（区分慢-活着 vs 卡死），不刷屏。
                chunkCount++;
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastLogMs >= PROGRESS_LOG_MS) {
                    lastLogMs = nowMs;
                    LOG.info("流式接收中 model={}: 已 {} 片 / {}s"
                                    + "（idle 判死 {}s / 总帽 {}s）",
                            model, chunkCount,
                            (nowMs - startMs) / 1000,
                            idleTimeoutSeconds, timeoutSeconds);
                }
            }
        } catch (java.io.IOException readEx) {
            if (idleAbort.get()) {
                long waited = (System.currentTimeMillis() - lastActivity.get())
                        / 1000;
                throw new HttpTimeoutException(
                        "LLM 流式空闲/超时切断：约 " + waited + "s 无新分片"
                                + "（idle=" + idleTimeoutSeconds
                                + "s / total=" + timeoutSeconds + "s）");
            }
            throw readEx;
        } finally {
            finished.set(true);
        }
        if (idleAbort.get()) {
            throw new HttpTimeoutException(
                    "LLM 流式被切断（idle=" + idleTimeoutSeconds
                            + "s / total=" + timeoutSeconds + "s）");
        }
        return acc.toTurn();
    }

    /**
     * 流式 SSE 累积器（抽出来只为可单测）：把一个又一个 {@code choices[0].delta}
     * 累积成完整的 {@link AgentTurn}。
     *
     * - content：逐片拼接（null/空跳过）；
     * - tool_calls：按 {@code index} 归属，id/name 取**首个非空**片，arguments **分片拼接**
     *   （OpenAI 流式把一份 JSON 参数拆成多个 fragment 陆续下发，必须拼起来才完整；
     *   ⚠️ siliconflow 非标：续片带 {@code name:""}（空串），若按 hasNonNull 判定
     *   会把首片的正确工具名覆盖成空 ⇒ 全部调用判「未知工具」，2026-10-16 实测）；
     * - reasoning_content：**刻意忽略**（草稿不进上下文），活跃信号由读取循环管。
     */
    static final class SseAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolAccum> tools = new LinkedHashMap<>();

        void feed(JsonNode delta) {
            String dc = delta.path("content").asText(null);
            if (dc != null && !dc.isEmpty()) {
                content.append(dc);
            }
            JsonNode tcs = delta.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    int idx = tc.path("index").asInt(0);
                    ToolAccum a = tools.computeIfAbsent(
                            idx, k -> new ToolAccum());
                    // 空串不覆盖：siliconflow 流式续片带 name:""（非标，
                    // OpenAI 官方续片无该字段），按 hasNonNull 判定会把
                    // 首片的正确 id/name 抹成空（2026-10-16 实测：
                    // 8 轮 agent 全部判「未知工具: 」）
                    String idText = tc.path("id").asText("");
                    if (!idText.isEmpty()) {
                        a.id = idText;
                    }
                    JsonNode fn = tc.path("function");
                    String fnName = fn.path("name").asText("");
                    if (!fnName.isEmpty()) {
                        a.name = fnName;
                    }
                    if (fn.hasNonNull("arguments")) {
                        a.args.append(fn.path("arguments").asText());
                    }
                }
            }
        }

        AgentTurn toTurn() {
            return assemble(content.toString(), tools);
        }
    }

    private static AgentTurn assemble(
            String content, Map<Integer, ToolAccum> toolAccum) {
        List<ToolCall> calls = new ArrayList<>();
        for (ToolAccum acc : toolAccum.values()) {
            String argsText = acc.args.length() > 0
                    ? acc.args.toString() : "{}";
            JsonNode args;
            try {
                args = MAPPER.readTree(argsText);
            } catch (Exception e) {
                args = MAPPER.createObjectNode();
            }
            calls.add(new ToolCall(
                    acc.id == null ? "" : acc.id,
                    acc.name == null ? "" : acc.name,
                    args));
        }
        return new AgentTurn(content, calls);
    }

    /** 流式 tool_call 累积器：id/name 取首片，arguments 分片拼接 */
    private static final class ToolAccum {
        private String id;
        private String name;
        private final StringBuilder args = new StringBuilder();
    }

    /** 读整个错误/非流式响应体，设上限防恶意超大响应打爆内存 */
    private static String readAll(InputStream body, long maxMillis)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        long deadline = System.currentTimeMillis() + maxMillis;
        int n;
        while ((n = body.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            if (sb.length() > 200_000
                    || System.currentTimeMillis() > deadline) {
                break;
            }
        }
        return sb.toString();
    }

    /** agent 消息账本 → OpenAI 请求体消息（含 tool_calls / tool_call_id 成对回填） */
    private static List<Map<String, Object>> encode(List<AgentMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentMessage m : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", m.role());
            switch (m.role()) {
                case "assistant" -> {
                    item.put("content", m.content());
                    if (!m.toolCalls().isEmpty()) {
                        List<Map<String, Object>> calls = new ArrayList<>();
                        for (ToolCall call : m.toolCalls()) {
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", call.name());
                            fn.put("arguments", call.arguments() == null
                                    ? "{}"
                                    : call.arguments().toString());
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("id", call.id());
                            c.put("type", "function");
                            c.put("function", fn);
                            calls.add(c);
                        }
                        item.put("tool_calls", calls);
                    }
                }
                case "tool" -> {
                    item.put("tool_call_id", m.toolCallId());
                    item.put("content", m.content());
                }
                default -> item.put("content", m.content());
            }
            out.add(item);
        }
        return out;
    }

    /** choices[0].message → AgentTurn（只取 content + tool_calls；草稿忽略） */
    private static AgentTurn decode(JsonNode root) {
        JsonNode choice = root.path("choices").get(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").asText("");
        List<ToolCall> calls = new ArrayList<>();
        JsonNode rawCalls = message.get("tool_calls");
        if (rawCalls != null && rawCalls.isArray()) {
            for (JsonNode c : rawCalls) {
                String id = c.path("id").asText("");
                String name = c.path("function").path("name").asText("");
                String argsText = c.path("function")
                        .path("arguments").asText("{}");
                JsonNode args;
                try {
                    args = MAPPER.readTree(argsText);
                } catch (Exception e) {
                    args = MAPPER.createObjectNode();
                }
                calls.add(new ToolCall(id, name, args));
            }
        }
        return new AgentTurn(content, calls);
    }
}
