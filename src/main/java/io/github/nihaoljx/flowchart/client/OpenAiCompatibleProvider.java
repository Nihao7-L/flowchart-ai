package io.github.nihaoljx.flowchart.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI 兼容格式的 LLM Provider
 *
 * 适用范围：Kimi、DeepSeek、通义千问、智谱 GLM 等——
 * 凡是遵循 OpenAI Chat Completions 格式的 API，都用这一个类，改配置就行
 *
 * @ConditionalOnProperty：Spring 条件装配
 *   当 application.yml 里 llm.provider=openai 时，这个类才会被创建
 *   换成别的 provider 时，这个类不会被加载
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiCompatibleProvider implements LlmProvider {

    @Value("${llm.base-url}")
    private String apiUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${llm.proxy.host:127.0.0.1}")
    private String proxyHost;

    @Value("${llm.proxy.port:7890}")
    private int proxyPort;

    @Value("${llm.timeout-seconds:60}")
    private int timeoutSeconds;

    private HttpClient httpClient;

    /**
     * 流式模式（2026-10-07）。
     *
     * <p>为什么必须开：非流式下我们只有"整次调用的墙钟上限"这**一把**尺子，
     * 于是"模型还在稳定吐字"和"模型已经卡死"看起来一模一样 —— 大图任务
     * 明明在正常产出，却会被总时限一刀切掉（实测 15 模块大图就是这样把
     * 一轮预算耗光的）。开流式后可以拿"**多久没有新内容**"当判据：
     * 只要还在吐字就一直等，只有真卡住才判死。
     */
    private boolean streamMode;

    /** 空闲（多久没有新内容）判死阈值，单位秒；仅流式模式生效 */
    private int idleTimeoutSeconds = DEFAULT_IDLE_TIMEOUT_SECONDS;

    /** 空闲判死的默认阈值：推理模型"想"的阶段可能静止较久，别定太紧 */
    private static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 120;

    /** Jackson 用于 JSON 序列化（构建请求体）和反序列化（解析响应） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 无参构造器保留给 Spring 字段注入（@Value）。
     *
     * <p>必须显式存在：本类新增了带参构造器后，若只剩带参构造器，
     * Spring 会试图自动装配它 —— 那些 String/int 参数无法解析，启动即失败。
     */
    public OpenAiCompatibleProvider() {
    }

    /**
     * 按候选直接构造（FailoverLlmProvider 用），构造即完成 httpClient 初始化。
     *
     * <p>非流式口径：只有总时限、没有空闲判据，适合短问答，不适合大图。
     */
    public OpenAiCompatibleProvider(String apiUrl, String apiKey, String model,
                                    int timeoutSeconds, boolean proxyEnabled,
                                    String proxyHost, int proxyPort) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.proxyEnabled = proxyEnabled;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.init();
    }

    /**
     * 按候选直接构造 + 指定传输模式（2026-10-07）。
     *
     * @param streamMode         true = 流式（空闲判死，推荐）；false = 非流式（总时限）
     * @param idleTimeoutSeconds 空闲判死阈值，秒；streamMode=false 时忽略
     */
    public OpenAiCompatibleProvider(String apiUrl, String apiKey, String model,
                                    int timeoutSeconds, boolean proxyEnabled,
                                    String proxyHost, int proxyPort,
                                    boolean streamMode,
                                    int idleTimeoutSeconds) {
        this(apiUrl, apiKey, model, timeoutSeconds,
                proxyEnabled, proxyHost, proxyPort);
        this.streamMode = streamMode;
        if (idleTimeoutSeconds > 0) {
            this.idleTimeoutSeconds = idleTimeoutSeconds;
        }
    }

    /** 非 200 的 HTTP 错误，带状态码便于失败分类（冷却 / 永久停用） */
    public static class LlmHttpException extends Exception {
        public final int status;
        public final String body;

        public LlmHttpException(int status, String body) {
            super("LLM API 返回错误码 " + status
                    + "，响应内容: " + body);
            this.status = status;
            this.body = body;
        }
    }

    /**
     * 输出被截断（finish_reason=length）：内容不完整，
     * 应换更大输出上限的模型重试，而不是当成"非法 JSON"直接兜底。
     */
    public static class TruncatedResponseException extends Exception {
        public TruncatedResponseException(String message) {
            super(message);
        }
    }

    @PostConstruct
    public void init() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (proxyEnabled) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        httpClient = builder.build();
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && !apiKey.equals("${LLM_API_KEY}")
                && !apiKey.startsWith("请配置");
    }

    /**
     * 发送 prompt → 返回提取后的纯文本（单条消息入口）
     *
     * <p>v2-41 起改为 delegate 到多消息入口 —— 两条入口只有一份实现，
     * 不会出现"新入口修了 bug、旧入口没修"的分叉。
     */
    @Override
    public String chat(String prompt) throws Exception {
        return chat(prompt, timeoutSeconds);
    }

    /**
     * 旧入口（单条 prompt）：等价于"只有一条 user 消息"。
     *
     * <p>行为与改造前完全一致：请求体仍是 {@code messages:[{role:user,...}]}。
     */
    @Override
    public String chat(String prompt, int callTimeoutSeconds)
            throws Exception {
        return chat(List.of(Message.user(prompt)), callTimeoutSeconds);
    }

    /**
     * 多消息入口（v2-41）：system / user 角色由此生效。
     *
     * <p>把消息按顺序映射成 OpenAI 的 messages 数组，复用同一套发送与解析逻辑。
     */
    @Override
    public String chat(List<Message> messages, int callTimeoutSeconds)
            throws Exception {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        return send(buildRequestBody(messages), callTimeoutSeconds);
    }

    /**
     * 把消息列表构造成请求体 JSON。
     *
     * <p>用 Jackson 的节点 API 构建（而非 String.format 手拼）：转义交由 Jackson
     * 处理，且便于按需追加字段 —— 例如 M4 接真 ReAct 时的 {@code tools}。
     *
     * @param messages 有序消息列表
     * @return 请求体 JSON 文本
     * @throws Exception 序列化失败
     */
    private String buildRequestBody(List<Message> messages) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        if (streamMode) {
            root.put("stream", true);
        }
        var array = root.putArray("messages");
        for (Message message : messages) {
            ObjectNode item = array.addObject();
            item.put("role", message.role());
            item.put("content", message.content());
        }
        // 预留：M4 接真 ReAct（工具调用）时在此追加，本期不启用
        //   root.set("tools", ...);
        //   root.put("tool_choice", "auto");
        return objectMapper.writeValueAsString(root);
    }

    /**
     * 发请求 + 收响应 + 解析文本（两条 chat 入口共用）
     *
     * 两步合一：发 HTTP 请求 + 解析响应 JSON。
     * 对外只暴露纯文本，调用方（解析/校验层）不关心响应格式。
     *
     * @param body              请求体 JSON
     * @param callTimeoutSeconds 整次调用的硬上限，单位秒
     * @return LLM 生成的文本内容
     * @throws Exception 超时、网络错误、非 200、响应结构异常
     */
    private String send(String body, int callTimeoutSeconds)
            throws Exception {
        if (streamMode) {
            return sendStreaming(body, callTimeoutSeconds);
        }
        // ===== 1. 发 HTTP 请求（Bearer 鉴权）=====
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(callTimeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> response =
                sendWithHardTimeout(request, callTimeoutSeconds);
        long costMs = System.currentTimeMillis() - startedAt;

        // 打印日志方便排查（带上耗时：这是判断"要不要收紧单轮上限"的依据）
        System.out.println("=== LLM HTTP 状态码: "
                + response.statusCode()
                + "，耗时 " + costMs + " ms");
        System.out.println("=== LLM 原始响应: " + response.body());

        if (response.statusCode() != 200) {
            throw new LlmHttpException(response.statusCode(),
                    response.body());
        }

        // ===== 2. 解析响应：choices[0].message.content =====
        return extractContent(response.body());
    }

    /**
     * 带"真·总时限"的发送
     *
     * 不能只依赖 {@code HttpRequest.timeout()}：实测推理类模型会在真正
     * 开始生成前先回一个响应头（首字节 8.2 秒即到达），此后 JDK 的请求
     * 计时便不再生效，正文可以再传近百秒。所以这里在 Future 层补一道
     * 总时限，覆盖"连接 + 响应头 + 正文"全过程。
     *
     * @param request 已构建的请求
     * @param seconds 整次调用的硬上限，单位秒
     * @return HTTP 响应
     * @throws Exception 超时、网络错误或被中断
     */
    private HttpResponse<String> sendWithHardTimeout(
            HttpRequest request, int seconds) throws Exception {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString());
        try {
            return future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Exception("LLM 调用超时：整次调用超过 "
                    + seconds + " 秒（含正文传输）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new Exception("LLM 调用失败: "
                    + (cause == null
                            ? e.getMessage()
                            : cause.getMessage()),
                    cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new Exception("LLM 调用被中断");
        }
    }

    /**
     * 流式发送（2026-10-07）：按"**空闲**判死"而不是"总时长判死"。
     *
     * <p>这是"模型还在跑就别掐"的正解：非流式只有总时限这一把尺子，
     * 分不出"稳定吐字"和"卡死"，大图任务会被误杀。改成逐帧读之后，
     * 判据变成"连续多久没有新内容"，只要还在出字就一直等。
     *
     * @param body               请求体 JSON（已含 stream:true）
     * @param callTimeoutSeconds 兜底的总时限（防无限挂死），单位秒
     * @return 拼接后的完整正文
     * @throws Exception 非 200、空闲超时、截断、正文为空
     */
    private String sendStreaming(String body, int callTimeoutSeconds)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(callTimeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long startedAt = System.currentTimeMillis();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new Exception("LLM 连接失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("LLM 调用被中断");
        }

        try (InputStream in = response.body()) {
            // 非 200 时正文是普通 JSON 错误体，必须读出来才能分类
            //（智谱的 429 就分两种：1113 余额不足 / 1305 过载）
            if (response.statusCode() != 200) {
                String errBody = new String(
                        in.readAllBytes(), StandardCharsets.UTF_8);
                System.out.println("=== LLM HTTP 状态码: "
                        + response.statusCode());
                System.out.println("=== LLM 原始响应: " + errBody);
                throw new LlmHttpException(response.statusCode(), errBody);
            }

            StringBuilder content = new StringBuilder();
            String finish = collectSse(
                    in, content, callTimeoutSeconds);
            long costMs = System.currentTimeMillis() - startedAt;
            System.out.println("=== LLM 流式完成: 耗时 " + costMs
                    + " ms，正文 " + content.length()
                    + " 字符，finish_reason=" + finish);

            if ("length".equals(finish)) {
                throw new TruncatedResponseException(
                        "LLM 输出被截断(finish_reason=length)，内容不完整");
            }
            if (content.length() == 0) {
                throw new Exception("LLM 流式返回的正文为空"
                        + "（可能只产出了思维链内容）");
            }
            return content.toString();
        }
    }

    /**
     * 逐行读 SSE，边读边刷新"最后收到数据"的时刻。
     *
     * <p>守护线程负责两件事：① 空闲超时（模型卡死）时关流唤醒阻塞中的读；
     * ② 兜底总时限。关流是唯一能中断阻塞 readLine 的手段（JDK 的
     * HttpClient 没有"单次读超时"这种设置）。
     *
     * @return finish_reason（读不到时按 stop 处理）
     */
    private String collectSse(InputStream in, StringBuilder out,
                              int hardSeconds) throws Exception {
        java.util.concurrent.atomic.AtomicLong lastData =
                new java.util.concurrent.atomic.AtomicLong(
                        System.currentTimeMillis());
        long hardDeadline = lastData.get()
                + hardSeconds * 1000L;
        java.util.concurrent.atomic.AtomicBoolean killed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean watchIdle =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        Thread watchdog = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(500);
                    long idleMs = System.currentTimeMillis()
                            - lastData.get();
                    if (idleMs > idleTimeoutSeconds * 1000L) {
                        watchIdle.set(true);
                        killed.set(true);
                        in.close();
                        return;
                    }
                    if (System.currentTimeMillis() > hardDeadline) {
                        killed.set(true);
                        in.close();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 关流本身会抛，忽略
            }
        });
        watchdog.setDaemon(true);
        watchdog.setName("llm-idle-watchdog");
        watchdog.start();

        String finish = "stop";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastData.set(System.currentTimeMillis());
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                finish = mergeChunk(payload, out, finish);
            }
        } catch (IOException e) {
            long idleSec =
                    (System.currentTimeMillis() - lastData.get()) / 1000;
            if (watchIdle.get()) {
                throw new Exception("LLM 空转超时：连续 " + idleSec
                        + " 秒没有任何新内容（模型卡住了，"
                        + "已等到 idle=" + idleTimeoutSeconds + "s）");
            }
            if (killed.get()) {
                throw new Exception("LLM 流式调用超过总时限 "
                        + hardSeconds + " 秒");
            }
            throw new Exception("LLM 流读取失败: " + e.getMessage(), e);
        } finally {
            watchdog.interrupt();
        }
        return finish;
    }

    /**
     * 把一帧 SSE 数据并进正文。
     *
     * <p>只取 {@code delta.content}：推理模型会先吐一大段
     * {@code delta.reasoning_content}（思维链），那是模型"想"的过程，
     * 不是要交付的内容，拼进来会污染 JSON 解析。
     */
    @SuppressWarnings("unchecked")
    private String mergeChunk(String payload, StringBuilder out,
                              String finish) {
        try {
            Map<String, Object> root =
                    objectMapper.readValue(payload, Map.class);
            Object usage = root.get("usage");
            if (usage instanceof Map) {
                Map<String, Object> u = (Map<String, Object>) usage;
                System.out.println("=== Token 用量: prompt="
                        + u.get("prompt_tokens") + ", completion="
                        + u.get("completion_tokens") + ", total="
                        + u.get("total_tokens"));
            }
            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) {
                return finish;
            }
            Map<String, Object> c0 = choices.get(0);
            Object fr = c0.get("finish_reason");
            if (fr != null) {
                finish = fr.toString();
            }
            Object delta = c0.get("delta");
            if (delta instanceof Map) {
                Object t = ((Map<String, Object>) delta).get("content");
                if (t != null) {
                    out.append(t);
                }
                return finish;
            }
            // 少数实现最后一帧给的是 message 而非 delta
            Object msg = c0.get("message");
            if (msg instanceof Map) {
                Object t = ((Map<String, Object>) msg).get("content");
                if (t != null) {
                    out.append(t);
                }
            }
        } catch (Exception ignored) {
            // 单帧解析失败不该毁掉整次调用：跳过这帧，继续读
        }
        return finish;
    }

    /**
     * 从 OpenAI 兼容格式的响应中提取文本
     *
     * 响应格式：
     * {
     *   "choices": [{ "message": { "role": "assistant", "content": "..." } }],
     *   "usage": { "prompt_tokens": 123, "completion_tokens": 456, "total_tokens": 579 }
     * }
     *
     * 成本统计预留：usage 字段先打日志，以后要做成本统计时
     * 把 log 换成存数据库即可，chat() 接口签名不用改
     */
    @SuppressWarnings("unchecked")
    private String extractContent(String rawResponse) throws Exception {
        Map<String, Object> root = objectMapper.readValue(rawResponse, Map.class);

        // ===== 提取 usage（成本统计用，目前只打日志）=====
        Map<String, Object> usage = (Map<String, Object>) root.get("usage");
        if (usage != null) {
            System.out.println("=== Token 用量: prompt=" + usage.get("prompt_tokens")
                    + ", completion=" + usage.get("completion_tokens")
                    + ", total=" + usage.get("total_tokens"));
        }

        // ===== 提取 choices[0].message.content =====
        List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new Exception("LLM 返回中没有 choices 字段，原始响应: " + rawResponse);
        }

        Map<String, Object> choice0 = choices.get(0);

        // 截断检测：模型把内容吐到一半撞上输出上限，内容不完整。
        // 抛专用异常，让路由换"大输出"模型重试 —— 不能当成"非法 JSON"兜底。
        Object finishReason = choice0.get("finish_reason");
        if ("length".equals(finishReason)) {
            throw new TruncatedResponseException(
                    "LLM 输出被截断(finish_reason=length)，内容不完整");
        }

        Map<String, Object> message = (Map<String, Object>) choice0.get("message");
        if (message == null) {
            throw new Exception("LLM 返回中没有 message 字段，原始响应: " + rawResponse);
        }

        Object textObj = message.get("content");
        if (textObj == null || textObj.toString().isBlank()) {
            throw new Exception("LLM 返回的 content 为空，原始响应: " + rawResponse);
        }

        return textObj.toString();
    }
}
