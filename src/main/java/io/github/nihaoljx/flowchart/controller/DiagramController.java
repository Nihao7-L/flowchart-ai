package io.github.nihaoljx.flowchart.controller;

import io.github.nihaoljx.flowchart.client.LlmGateway;
import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.service.AgentPlanner;
import io.github.nihaoljx.flowchart.service.TraceService;
import io.github.nihaoljx.flowchart.model.DownloadRequest;
import io.github.nihaoljx.flowchart.model.GenerateRequest;
import io.github.nihaoljx.flowchart.model.GraphJson;
import io.github.nihaoljx.flowchart.model.RefineRequest;
import io.github.nihaoljx.flowchart.model.MindmapData;
import io.github.nihaoljx.flowchart.model.Result;
import io.github.nihaoljx.flowchart.model.Session;
import io.github.nihaoljx.flowchart.service.GraphValidator;
import io.github.nihaoljx.flowchart.service.DiagramService;
import io.github.nihaoljx.flowchart.service.GraphJsonService;
import io.github.nihaoljx.flowchart.service.ParserService;
import io.github.nihaoljx.flowchart.service.PromptService;
import io.github.nihaoljx.flowchart.service.RagService;
import io.github.nihaoljx.flowchart.service.SessionService;
import io.github.nihaoljx.flowchart.service.ToolExecutor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import io.github.nihaoljx.flowchart.service.UsageService;
import io.github.nihaoljx.flowchart.service.ValidationException;
import io.github.nihaoljx.flowchart.client.stream.ClientDisconnectedException;
import io.github.nihaoljx.flowchart.client.stream.ProgressContext;
import io.github.nihaoljx.flowchart.client.stream.ProgressEvent;
import io.github.nihaoljx.flowchart.client.stream.ProgressSink;
import io.github.nihaoljx.flowchart.client.stream.RoutingContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图表相关接口
 */
@Tag(name = "图表接口", description = "文字 → 流程图 / 思维导图 / 架构图 的生成与下载")
@RestController
public class DiagramController {

    @Autowired private PromptService promptService;
    @Autowired private LlmProvider llmProvider;
    @Autowired private ParserService parserService;
    @Autowired private DiagramService diagramService;
    @Autowired private UsageService usageService;
    @Autowired private RagService ragService;
    @Autowired private ToolExecutor toolExecutor;
    @Autowired private SessionService sessionService;
    /** 任务49：端到端生成验证 */
    @Autowired private GraphValidator graphValidator;
    /** 任务50：LLM Gateway（收口 routing + audit + budget） */
    @Autowired private LlmGateway llmGateway;
    /** 任务51：可观测性（调用链追踪 + 指标看板） */
    @Autowired private TraceService traceService;
    /** 任务41：Agent 规划与自主修正 */
    @Autowired private AgentPlanner agentPlanner;

    /** 任务53：Loop Engineering 最大修正轮数（超过则降级返回最后一次结果） */
    private static final int MAX_LOOP_ROUNDS = 3;

    /** 任务43：Graph JSON 转换中心（思维导图展平 + Mermaid 导出） */
    @Autowired private GraphJsonService graphJsonService;

    /** SSE 生成用的线程池：HTTP 请求线程立即返回 emitter，生成在后台线程跑，进度通过 SseEmitter 推 */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Operation(summary = "健康检查", description = "返回服务是否正常，常用于容器探活")
    @GetMapping("/api/health")
    public Result<?> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("llmConfigured", llmProvider.isConfigured());
        status.put("ragEnabled", ragService.isEnabled());
        status.put("sessionStore", sessionService.currentStore());
        return Result.success(status);
    }

    /** 任务50：网关状态（provider / budget / 调用次数） */
    @Operation(summary = "LLM 网关状态", description = "返回 LLM 网关的运行状态与配置摘要")
    @GetMapping("/api/gateway/status")
    public Result<?> gatewayStatus() {
        return Result.success(llmGateway.getStatus());
    }

    /** 任务50：审计日志（最近 200 条调用记录） */
    @Operation(summary = "LLM 调用审计日志", description = "返回最近的 LLM 调用记录（方法/耗时/token/成功失败）")
    @GetMapping("/api/gateway/audit")
    public Result<?> gatewayAudit() {
        return Result.success(llmGateway.getAuditLog());
    }

    /** 任务51：可观测性指标（总请求数/失败率/平均耗时/步骤统计） */
    @Operation(summary = "可观测性指标", description = "返回请求级 Trace 汇总：总请求数、失败率、平均耗时、按步骤统计")
    @GetMapping("/api/metrics")
    public Result<?> metrics() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("usage", usageService.getStats());
        data.put("traces", traceService.getMetrics());
        data.put("gateway", llmGateway.getStatus());
        return Result.success(data);
    }

    /** 任务41：Agent 模式（自主规划多步骤生成） */
    @Operation(summary = "Agent 模式生成", description = "LLM 自主规划多步骤完成任务：检索→生成→校验→修正，循环直到通过")
    @PostMapping("/api/agent")
    public Result<?> agent(@RequestBody GenerateRequest req) {
        try {
            String userText = req.text();
            String type = req.type() != null ? req.type() : "flowchart";
            boolean useRag = Boolean.TRUE.equals(req.useRag());
            boolean useTool = Boolean.TRUE.equals(req.useTool());

            ParamError err = validateParams(userText, type);
            if (err != null) return Result.error(err.code(), err.msg());

            AgentPlanner.AgentResult result = agentPlanner.execute(userText, type, useRag, useTool);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("graphJson", result.graphJson());
            data.put("svg", result.svg());
            data.put("mermaid", result.mermaid());
            data.put("agentSummary", result.summary());
            data.put("type", type);
            return Result.success(data);

        } catch (Exception e) {
            return Result.error(500, "Agent 执行失败：" + e.getMessage());
        }
    }

    /**
     * 任务40：会话详情（历史对话 + 当前图）。
     * 前端「历史记录」点击某条会话时调它：拿 messages 恢复对话气泡、拿 currentGraphJson 恢复画布。
     * 会话过期/不存在返回 404，前端据此把该条从历史列表里清掉。
     */
    @Operation(summary = "会话详情", description = "返回指定会话的历史消息与当前图（Redis 持久化，重启不丢）")
    @GetMapping("/api/session/{sessionId}")
    public Result<?> getSession(@PathVariable String sessionId) {
        Session s = sessionService.get(sessionId);
        if (s == null) return Result.error(404, "会话不存在或已过期");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("messages", s.getMessages());
        data.put("currentGraphJson", s.getCurrentGraphJson());
        data.put("store", sessionService.currentStore());
        return Result.success(data);
    }

    /**
     * 任务46：会话历史列表。
     * 返回所有会话的 id + lastActive，按活跃时间降序排列。
     * 前端可据此展示历史列表，不必仅依赖 localStorage。
     */
    @Operation(summary = "会话历史列表", description = "返回所有会话的摘要（id + 活跃时间），按活跃时间降序")
    @GetMapping("/api/history")
    public Result<?> listHistory() {
        var summaries = sessionService.listAll();
        List<Map<String, Object>> items = new ArrayList<>();
        for (var s : summaries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.sessionId());
            m.put("lastActive", s.lastActive());
            items.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessions", items);
        data.put("total", items.size());
        data.put("store", sessionService.currentStore());
        return Result.success(data);
    }

    @Operation(summary = "生成图表", description = "传入文字描述 + 图表类型，调用 LLM 生成图表数据、SVG 与 PlantUML 源码")
    @PostMapping("/api/generate")
    public Result<?> generate(@RequestBody GenerateRequest req) {
        try {
            String userText = req.text();
            // record 字段可能为 null（前端没传），这里给默认值
            String type = req.type() != null ? req.type() : "flowchart";
            String format = req.format() != null ? req.format() : "svg";

            ParamError err = validateParams(userText, type);
            if (err != null) return Result.error(err.code(), err.msg());

            // ---- 任务38+B：RAG 检索增强（带元数据回传前端）----
            boolean useRag = req.useRag() != null && req.useRag();
            String context = "";
            RagService.RagResult ragResult = null;
            if (useRag) {
                if (!ragService.isEnabled()) {
                    return Result.error(503, "RAG 未配置：请在 application.yml 设置 llm.embedding.api-key（硅基流动免费 key）");
                }
                ragResult = ragService.retrieveWithMeta(userText);
                context = ragResult.context();
                if (ragResult.degraded()) {
                    System.out.println("⚠️ RAG 检索降级：embedding 调用失败，本次生成未使用知识库");
                }
            }

            // ---- 任务39：Tool Calling 模式（大厂能力版，LLM 自己决定调工具，结果作为素材并入 prompt）----
            // RAG 与 Tool 都作为素材注入 context，二者互不排斥；
            // 之前非流式分支在这里直接 return 跳过了 RAG，导致"知识库+工具"同时开时 RAG 被静默丢弃，已修复。
            if (Boolean.TRUE.equals(req.useTool())) {
                String extraContext = toolExecutor.collectContext(userText);
                if (extraContext != null && !extraContext.isBlank()) {
                    context += extraContext; // 与流式接口行为保持一致：RAG 文本在前，工具结果追加在后
                }
            }

            Map<String, Object> result = doGenerate(userText, type, format, context, ragResult);
            return Result.success(result);

        } catch (ValidationException e) {
            // 校验失败：返回 400 + 结构化错误列表 {field, reason, hint} + LLM 原始摘要（如有）
            return Result.error(400, e.getMessage(), e.getIssues(), e.getAiSummary());

        } catch (Exception e) {
            System.err.println("生成图表失败: " + e.getMessage());
            e.printStackTrace();
            // 顺手优化：500 时也带上具体原因，方便排查（原来是笼统的"请换一种描述试试"）
            return Result.error(500, "AI 生成失败：" + e.getMessage());
        }

    }

    @Operation(summary = "生成图表（SSE 实时进度）", description = "通过 Server-Sent Events 实时推送重试/切换/Token 用量等进度，结束时推送最终结果 SVG")
    @GetMapping(value = "/api/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@RequestParam("text") String text,
                                     @RequestParam(value = "type", defaultValue = "flowchart") String type,
                                     @RequestParam(value = "model", required = false) String model,
                                     @RequestParam(value = "format", defaultValue = "svg") String format,
                                     @RequestParam(value = "useRag", defaultValue = "false") boolean useRag,
                                     @RequestParam(value = "useTool", defaultValue = "false") boolean useTool,
                                     @RequestParam(value = "sessionId", required = false) String sessionId) {
        // 260 秒超时（比后端流式生成/推理耗时多留余量，避免慢模型在 120s 被掐断而前端提前 abort）
        SseEmitter emitter = new SseEmitter(260_000L);
        // 客户端断开 / 异步超时 / 发送异常时置 true：之后的推送一律跳过，避免流式下每帧刷一条"已忽略"错误
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        // 后台线程跑生成：HTTP 请求线程立刻把 emitter 返回给浏览器，进度靠它流式推送
        executor.submit(() -> {
            // 把"进度出水口"挂到当前后台线程（ThreadLocal）。
            // Provider 内部 ProgressContext.publish(event) 会调用这个 lambda，把事件写进 SSE。
            ProgressContext.setSink(new ProgressSink() {
                @Override
                public void emit(ProgressEvent event) {
                    // 已断开/已完成：直接跳过，不再尝试发送（否则流式下每帧都会刷一条"已忽略"错误）
                    if (closed.get()) return;
                    try {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("type", event.type());
                        payload.put("message", event.message());
                        if (event.provider() != null) payload.put("provider", event.provider());
                        if (event.data() != null) payload.putAll(event.data());
                        emitter.send(SseEmitter.event().name("progress").data(payload));
                    } catch (Exception e) {
                        closed.set(true);
                        // 客户端断开 / emitter 超时：只记一条（之后的帧直接跳过），不要 completeWithError
                        System.err.println("SSE 进度推送中断（客户端已断开或超时，已停止后续推送）: " + e.getMessage());
                    }
                }
                @Override
                public boolean isClosed() {
                    // 任务40：把"客户端是否已断开"暴露给底层流式读取循环，用于中断生成
                    return closed.get();
                }
            });

            // 把"前端选中的模型"挂到当前请求线程：FallbackLlmProvider 选主模型时会读它
            RoutingContext.set(model);

            try {
                // ---- 参数校验（和 /api/generate 保持一致：顺序统一为 配置→非空→长度→类型）----
                if (!llmProvider.isConfigured()) {
                    sendError(emitter, closed, "服务未配置：请联系管理员设置 LLM_API_KEY");
                    return;
                }
                if (text == null || text.isBlank()) {
                    sendError(emitter, closed, "请输入流程描述");
                    return;
                }
                if (text.length() > 2000) {
                    sendError(emitter, closed, "描述过长，请精简到 2000 字以内");
                    return;
                }
                if (!"flowchart".equals(type) && !"mindmap".equals(type) && !"architecture".equals(type)) {
                    sendError(emitter, closed, "不支持的图表类型: " + type);
                    return;
                }

                // ---- 任务40：会话记忆（无 sessionId 则建新会话）----
                // 注意：本方法体在 executor.submit 的 lambda 内，lambda 捕获的方法参数 sessionId 必须 effectively final，
                // 因此不能在 lambda 内对 sessionId 重新赋值，改用新局部变量 sid 承接解析后的会话 id。
                final String sid = (sessionId == null || sessionId.isBlank()) ? sessionService.create() : sessionId;

                // ---- 任务38+B：RAG 检索增强（带元数据回传前端）----
                String context = "";
                RagService.RagResult ragResult = null;
                if (useRag) {
                    if (!ragService.isEnabled()) {
                        sendError(emitter, closed, "RAG 未配置：请在 application.yml 设置 llm.embedding.api-key");
                        return;
                    }
                    ragResult = ragService.retrieveWithMeta(text);
                    context = ragResult.context();
                    if (ragResult.degraded()) {
                        ProgressContext.publish(ProgressEvent.info("⚠️ 知识库检索失败，已降级为不使用知识库继续生成"));
                    }
                }

                // 任务39：Tool Calling（LLM 自己决定调工具，结果作为素材并入 prompt）
                if (useTool) {
                    ProgressContext.publish(ProgressEvent.info("🔧 正在让 AI 决定调用哪些工具获取素材…"));
                    String extra = toolExecutor.collectContext(text);
                    if (extra != null && !extra.isBlank()) {
                        context += extra;
                        ProgressContext.publish(ProgressEvent.info("✅ 已获取工具结果并并入素材（图将基于工具返回内容生成）"));
                    } else {
                        ProgressContext.publish(ProgressEvent.info("💡 AI 判断无需调用工具，直接生成"));
                    }
                }

                ProgressContext.publish(ProgressEvent.info("开始生成「" + type + "」…"));

                // 任务37：Mermaid 分支（前端渲染，不调 PlantUML）
                if ("mermaid".equals(format)) {
                    Map<String, Object> result = buildMermaid(text, type, context, ragResult);
                    // 任务40：回填会话（Mermaid 无 graphJson 基线）+ 回传 sessionId
                    sessionService.addUserMessage(sid, text);
                    sessionService.addAssistantGraph(sid, "生成「" + type + "」", null);
                    result.put("sessionId", sid);
                    safeSend(emitter, closed, "done", result);
                    return;
                }

                ProgressContext.publish(ProgressEvent.info("📝 正在构建图表生成 prompt…"));
                String prompt = promptService.buildPrompt(text, type, context);
                String schema = promptService.loadSchema(type); // 任务33：加载该类型的 JSON Schema
                // 任务39+：真·流式——边读边把模型的思考增量（reasoning_content）实时推前端，返回累计的完整 content
                ProgressContext.publish(ProgressEvent.info("🤖 正在让大模型生成图表数据…"));
                String llmText = llmProvider.chatStructuredStream(prompt, schema); // 走 Cache→Fallback→Provider，内部流式推送思考

                ProgressContext.publish(ProgressEvent.info("✅ 模型已返回，正在解析 JSON 并渲染图表…"));

                Map<String, Object> result = new HashMap<>();
                String plantUml = parseAndRender(type, llmText, result);

                // 任务53：Loop Engineering —— 验证 → 不通过则自动修正 → 再验证
                int loopRound = 0;
                String currentPrompt = prompt;
                while (loopRound < MAX_LOOP_ROUNDS) {
                    GraphJson currentGraph = result.get("graphJson") instanceof GraphJson g ? g : null;
                    if (currentGraph == null) break; // 无图可验证，跳出

                    var issues = graphValidator.validate(currentGraph, type);
                    if (issues.isEmpty()) {
                        break; // 验证通过
                    }

                    loopRound++;
                    ProgressContext.publish(ProgressEvent.info("🔄 验证发现 " + issues.size() + " 个问题，正在第 " + loopRound + " 轮自动修正…"));

                    if (loopRound >= MAX_LOOP_ROUNDS) {
                        ProgressContext.publish(ProgressEvent.info("⚠️ 已达最大修正轮数（" + MAX_LOOP_ROUNDS + "），返回最后一次结果"));
                        break;
                    }

                    // 构建修正 prompt 并重新调用 LLM
                    String correctionPrompt = promptService.buildCorrectionPrompt(currentPrompt, llmText, issues);
                    ProgressContext.publish(ProgressEvent.info("🤖 正在让模型修正（第 " + loopRound + " 轮）…"));
                    llmText = llmProvider.chatStructuredStream(correctionPrompt, schema);

                    ProgressContext.publish(ProgressEvent.info("🔄 正在重新解析并渲染…"));
                    result.clear();
                    plantUml = parseAndRender(type, llmText, result);
                }

                if (loopRound > 0) {
                    ProgressContext.publish(ProgressEvent.info("✅ 经过 " + loopRound + " 轮修正，图表已通过验证"));
                }

                finalizeResult(result, plantUml, type, ragResult);

                // 任务40：回填会话（用户原始需求 + AI 产出的图）+ 回传 sessionId
                sessionService.addUserMessage(sid, text);
                Object genGraph = result.get("graphJson");
                GraphJson resultGraph = genGraph instanceof GraphJson ? (GraphJson) genGraph : null;
                sessionService.addAssistantGraph(sid, "生成「" + type + "」", resultGraph);
                result.put("sessionId", sid);

                // 收尾事件：把最终 SVG 推给前端，然后关闭流
                safeSend(emitter, closed, "done", result);

            } catch (ValidationException e) {
                // FixA：校验失败把具体 issues 列表也发给前端（原来只发"共 N 个问题"摘要，看不到错在哪）
                Map<String, Object> errData = new HashMap<>();
                errData.put("message", e.getMessage());
                errData.put("issues", e.getIssues());
                // 透传 LLM 原始回复截短摘要（≤200 字 + 句子完整），前端折叠区展示"AI 实际说了什么"
                if (e.getAiSummary() != null) errData.put("aiSummary", e.getAiSummary());
                sendError(emitter, closed, errData);
            } catch (ClientDisconnectedException e) {
                // 用户主动停止 / 客户端断开：静默结束，不刷错误日志、不补发 error 事件
                System.out.println("生成已中断（客户端断开）");
            } catch (Exception e) {
                System.err.println("SSE 生成失败: " + e.getMessage());
                e.printStackTrace();
                sendError(emitter, closed, "AI 生成失败：" + e.getMessage());
            } finally {
                ProgressContext.clear();  // 必须摘掉 ThreadLocal，避免线程池复用串数据
                RoutingContext.clear();   // 同上：清除"选中的模型"，否则下一个请求误用
            }
        });

        return emitter;
    }

    @Operation(summary = "画布编辑回流 Refine（SSE 实时进度）", description = "接收当前画布上的图 + 修改指令，让 LLM 在人工编辑基础上输出修改后的完整图，实时推送进度")
    @PostMapping(value = "/api/refine/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter refineStream(@RequestBody RefineRequest req) {
        // 260 秒超时（与生成接口一致）
        SseEmitter emitter = new SseEmitter(260_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));

        executor.submit(() -> {
            ProgressContext.setSink(new ProgressSink() {
                @Override
                public void emit(ProgressEvent event) {
                    if (closed.get()) return;
                    try {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("type", event.type());
                        payload.put("message", event.message());
                        if (event.provider() != null) payload.put("provider", event.provider());
                        if (event.data() != null) payload.putAll(event.data());
                        emitter.send(SseEmitter.event().name("progress").data(payload));
                    } catch (Exception e) {
                        closed.set(true);
                        System.err.println("SSE 进度推送中断（refine）: " + e.getMessage());
                    }
                }
                @Override
                public boolean isClosed() {
                    return closed.get();
                }
            });
            RoutingContext.set(req.model());

            try {
                // ---- 参数校验 ----
                if (!llmProvider.isConfigured()) {
                    sendError(emitter, closed, "服务未配置：请联系管理员设置 LLM_API_KEY");
                    return;
                }
                GraphJson current = req.graphJson();
                if (current == null || current.getNodes() == null || current.getNodes().isEmpty()) {
                    sendError(emitter, closed, "当前图为空，无法 Refine（请先生成一张图）");
                    return;
                }
                String instruction = req.instruction();
                if (instruction == null || instruction.isBlank()) {
                    sendError(emitter, closed, "请输入修改指令，例如：加一个验证码步骤");
                    return;
                }
                // 任务40：会话记忆（无 sessionId 则建新会话，否则沿用）
                String sessionId = req.sessionId();
                if (sessionId == null || sessionId.isBlank()) {
                    sessionId = sessionService.create();
                }
                if (instruction.length() > 2000) {
                    sendError(emitter, closed, "指令过长，请精简到 2000 字以内");
                    return;
                }
                String type = req.type() != null ? req.type() : "flowchart";
                if (!"flowchart".equals(type) && !"mindmap".equals(type) && !"architecture".equals(type)) {
                    sendError(emitter, closed, "不支持的图表类型: " + type);
                    return;
                }

                // ---- 任务38+B：RAG 检索增强（与生成接口一致）----
                String context = "";
                RagService.RagResult ragResult = null;
                if (Boolean.TRUE.equals(req.useRag())) {
                    if (!ragService.isEnabled()) {
                        sendError(emitter, closed, "RAG 未配置：请在 application.yml 设置 llm.embedding.api-key");
                        return;
                    }
                    ragResult = ragService.retrieveWithMeta(instruction);
                    context = ragResult.context();
                    if (ragResult.degraded()) {
                        ProgressContext.publish(ProgressEvent.info("⚠️ 知识库检索失败，已降级为不使用知识库继续 Refine"));
                    }
                }

                // 任务39：Tool Calling（与生成接口一致）
                if (Boolean.TRUE.equals(req.useTool())) {
                    ProgressContext.publish(ProgressEvent.info("🔧 正在让 AI 决定调用哪些工具获取素材…"));
                    String extra = toolExecutor.collectContext(instruction);
                    if (extra != null && !extra.isBlank()) {
                        context += extra;
                        ProgressContext.publish(ProgressEvent.info("✅ 已获取工具结果并并入素材（图将基于工具返回内容修改）"));
                    } else {
                        ProgressContext.publish(ProgressEvent.info("💡 AI 判断无需调用工具，直接修改"));
                    }
                }

                ProgressContext.publish(ProgressEvent.info("🔧 正在按你的指令修改「" + type + "」…"));

                // ---- 复用整套 harness：refine prompt + 流式思考 + 解析 + 渲染 ----
                // 任务40：从会话取历史指令（用户之前的修改记录），拼进 refine prompt 帮助理解指代
                String history = sessionService.buildHistoryText(sessionId);
                String prompt = promptService.buildRefinePrompt(current, type, instruction, context, history);
                String schema = promptService.loadSchema("refine");   // 通用宽松 graph schema
                ProgressContext.publish(ProgressEvent.info("🤖 正在让大模型修改图表…"));
                String llmText = llmProvider.chatStructuredStream(prompt, schema);

                ProgressContext.publish(ProgressEvent.info("✅ 模型已返回，正在解析并渲染图表…"));

                Map<String, Object> result = new HashMap<>();
                String plantUml = refineParseAndRender(type, llmText, result);

                // 任务53：Loop Engineering —— 验证 → 不通过则自动修正 → 再验证
                int loopRound = 0;
                String currentPrompt = prompt;
                while (loopRound < MAX_LOOP_ROUNDS) {
                    GraphJson currentGraph = result.get("graphJson") instanceof GraphJson g ? g : null;
                    if (currentGraph == null) break;

                    var issues = graphValidator.validate(currentGraph, type);
                    if (issues.isEmpty()) {
                        break;
                    }

                    loopRound++;
                    ProgressContext.publish(ProgressEvent.info("🔄 验证发现 " + issues.size() + " 个问题，正在第 " + loopRound + " 轮自动修正…"));

                    if (loopRound >= MAX_LOOP_ROUNDS) {
                        ProgressContext.publish(ProgressEvent.info("⚠️ 已达最大修正轮数（" + MAX_LOOP_ROUNDS + "），返回最后一次结果"));
                        break;
                    }

                    String correctionPrompt = promptService.buildCorrectionPrompt(currentPrompt, llmText, issues);
                    ProgressContext.publish(ProgressEvent.info("🤖 正在让模型修正（第 " + loopRound + " 轮）…"));
                    llmText = llmProvider.chatStructuredStream(correctionPrompt, schema);

                    ProgressContext.publish(ProgressEvent.info("🔄 正在重新解析并渲染…"));
                    result.clear();
                    plantUml = refineParseAndRender(type, llmText, result);
                }

                if (loopRound > 0) {
                    ProgressContext.publish(ProgressEvent.info("✅ 经过 " + loopRound + " 轮修正，图表已通过验证"));
                }

                finalizeResult(result, plantUml, type, ragResult);

                // 任务40：回填会话（本次修改指令 + AI 产出的图）+ 回传 sessionId
                sessionService.addUserMessage(sessionId, instruction);
                Object rfGraph = result.get("graphJson");
                GraphJson refineGraph = rfGraph instanceof GraphJson ? (GraphJson) rfGraph : null;
                sessionService.addAssistantGraph(sessionId, "修改「" + type + "」", refineGraph);
                result.put("sessionId", sessionId);

                safeSend(emitter, closed, "done", result);

            } catch (ValidationException e) {
                Map<String, Object> errData = new HashMap<>();
                errData.put("message", e.getMessage());
                errData.put("issues", e.getIssues());
                if (e.getAiSummary() != null) errData.put("aiSummary", e.getAiSummary());
                sendError(emitter, closed, errData);
            } catch (ClientDisconnectedException e) {
                System.out.println("refine 已中断（客户端断开）");
            } catch (Exception e) {
                System.err.println("SSE refine 失败: " + e.getMessage());
                e.printStackTrace();
                sendError(emitter, closed, "AI 修改失败：" + e.getMessage());
            } finally {
                ProgressContext.clear();
                RoutingContext.clear();
            }
        });

        return emitter;
    }

    /** refine 专用解析渲染：refine 输出恒为平铺 GraphJson（任何类型），用宽松解析 + 类型安全渲染 */
    private String refineParseAndRender(String type, String llmText, Map<String, Object> result) throws Exception {
        try {
            GraphJson g = parserService.parseGraph(llmText);
            result.put("graphJson", g);
            // 渲染分流：流程图用带 start/end/decision 的 PlantUML；架构图/思维导图用通用的边式 PlantUML
            // （buildPlantUml 必须从 start 节点递归，架构/思维导图没有 start 会 NPE，故走 buildArchitecture）
            if ("flowchart".equals(type)) {
                return diagramService.buildPlantUml(g);
            }
            return diagramService.buildArchitecture(g);
        } catch (ValidationException e) {
            String summary = truncateAtSentence(llmText, 200);
            throw new ValidationException(e.getIssues(), summary);
        }
    }

    /** 安全发送事件并关闭流：emitter 已完成时静默跳过（客户端断开/超时等场景） */
    private void safeSend(SseEmitter emitter, AtomicBoolean closed, String eventName, Object data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            emitter.complete();
        } catch (IllegalStateException e) {
            closed.set(true);
            // emitter 已被超时、客户端断开、或进度推送提前关闭 —— 只记一次
            System.err.println("SSE 发送 " + eventName + " 时 emitter 已关闭: " + e.getMessage());
        } catch (Exception e) {
            closed.set(true);
            System.err.println("SSE 发送 " + eventName + " 失败: " + e.getMessage());
            try { emitter.completeWithError(e); } catch (Exception ignore) {}
        }
    }

    /** 统一发一条 error 事件并关闭流 */
    private void sendError(SseEmitter emitter, AtomicBoolean closed, String msg) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", msg)));
            emitter.complete();
        } catch (Exception ignore) {
            closed.set(true);
            // emitter 已完成时静默跳过
            System.err.println("SSE 发送 error 事件失败: " + ignore.getMessage());
        }
    }

    /** FixA：error 事件可携带结构化数据（如校验 issues 列表），前端能显示具体错在哪 */
    private void sendError(SseEmitter emitter, AtomicBoolean closed, Map<String, Object> data) {
        if (closed.get()) return;
        try {
            emitter.send(SseEmitter.event().name("error").data(data));
            emitter.complete();
        } catch (Exception ignore) {
            closed.set(true);
            System.err.println("SSE 发送 error 事件失败: " + ignore.getMessage());
        }
    }

    /** 参数校验：返回 null 表示通过；否则含 HTTP 状态码与错误信息（非流式 503 vs 400 在此区分） */
    private record ParamError(int code, String msg) {}
    private ParamError validateParams(String text, String type) {
        if (!llmProvider.isConfigured()) return new ParamError(503, "服务未配置：请联系管理员设置 LLM_API_KEY");
        if (text == null || text.isBlank()) return new ParamError(400, "请输入流程描述");
        if (text.length() > 2000) return new ParamError(400, "描述过长，请精简到 2000 字以内");
        if (!"flowchart".equals(type) && !"mindmap".equals(type) && !"architecture".equals(type)) return new ParamError(400, "不支持的图表类型: " + type);
        return null;
    }

    /** 按类型解析 LLM 文本并构建对应图（任务39 抽出，doGenerate / generateStream 共用，消除重复） */
    private String parseAndRender(String type, String llmText, Map<String, Object> result) throws Exception {
        try {
            return switch (type) {
                case "mindmap" -> {
                    MindmapData m = parserService.parseMindmap(llmText);
                    // 思维导图：嵌套树 → 平铺 GraphJson（唯一对外契约）
                    result.put("graphJson", graphJsonService.fromMindmap(m));
                    yield diagramService.buildMindMap(m);
                }
                case "architecture" -> {
                    GraphJson a = parserService.parseArchitecture(llmText);
                    result.put("graphJson", a);
                    yield diagramService.buildArchitecture(a);
                }
                default -> {
                    GraphJson d = parserService.parse(llmText);
                    result.put("graphJson", d);
                    yield diagramService.buildPlantUml(d);
                }
            };
        } catch (ValidationException e) {
            // schema 校验失败：截 LLM 原始回复（≤200 字 + 句子完整），附在异常上
            // 让前端折叠区展示"AI 实际说了什么"，避免"组件列表为空"这种生硬报错
            String summary = truncateAtSentence(llmText, 200);
            throw new ValidationException(e.getIssues(), summary);
        }
    }

    /** 渲染 SVG 并补齐 result 的公共字段（svg/plantUml/type/mermaid/ragResult） */
    private void finalizeResult(Map<String, Object> result, String plantUml, String type, RagService.RagResult ragResult) throws IOException {
        String svg = diagramService.renderToSvg(plantUml);
        result.put("svg", svg);
        result.put("plantUml", plantUml);
        result.put("type", type);
        // 任务43：GraphJson → Mermaid（后端直接转换，不依赖 LLM）
        if (result.get("graphJson") instanceof GraphJson g) {
            result.put("mermaid", graphJsonService.toMermaid(g));
        }
        putRagResult(result, ragResult);
    }

    /**
     * 任务39：抽出核心出图逻辑，useTool / useRag 共用
     * @param ragResult 检索命中（可为 null），用于回传前端引用面板
     */
    private Map<String, Object> doGenerate(String text, String type, String format,
                                           String context, RagService.RagResult ragResult) throws Exception {
        if ("mermaid".equals(format)) {
            return buildMermaid(text, type, context, ragResult);
        }
        String prompt = promptService.buildPrompt(text, type, context);
        String schema = promptService.loadSchema(type); // 任务33：加载该类型 JSON Schema
        String llmText = llmProvider.chatStructured(prompt, schema); // 任务33：结构化输出

        Map<String, Object> result = new HashMap<>();
        String plantUml = parseAndRender(type, llmText, result);
        finalizeResult(result, plantUml, type, ragResult);
        return result;
    }

    /**
     * 任务37：生成 Mermaid 图表文本
     *
     * 让 LLM 直接产出 mermaid 代码（不生成 PlantUML、不渲染 SVG），
     * 由前端 mermaid.js 在浏览器里渲染。返回的结果 Map 含 mermaid 字段，svg/plantUml 留空占位。
     */
    private Map<String, Object> buildMermaid(String text, String type, String context, RagService.RagResult ragResult) throws Exception {
        String prompt = promptService.buildMermaidPrompt(text, type, context);
        String schema = promptService.loadMermaidSchema();
        String llmText = llmProvider.chatStructuredStream(prompt, schema); // 任务40：mermaid 也走流式，支持中断 + 思考流
        try {
            String mermaid = parserService.extractMermaid(llmText);
            Map<String, Object> result = new HashMap<>();
            result.put("mermaid", mermaid);
            result.put("format", "mermaid");
            result.put("type", type);
            result.put("svg", "");
            result.put("plantUml", "");
            putRagResult(result, ragResult);
            return result;
        } catch (ValidationException e) {
            // mermaid 抽取失败：截 LLM 原始回复，附在异常上透传给前端
            String summary = truncateAtSentence(llmText, 200);
            throw new ValidationException(e.getIssues(), summary);
        }
    }

    /** 把 RAG 检索结果统一塞进 result Map（任务38-B + 任务47 titlePath） */
    private void putRagResult(Map<String, Object> result, RagService.RagResult ragResult) {
        if (ragResult == null) return;
        result.put("ragContext", ragResult.context());
        result.put("ragSources", ragResult.hits().stream().map(RagService.RagHit::source).distinct().toList());
        result.put("ragDegraded", ragResult.degraded());
        result.put("ragScores", ragResult.hits().stream().map(h -> Math.round(h.score() * 1000.0) / 1000.0).toList());
        result.put("ragHits", ragResult.hits().stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", h.source());
            m.put("score", Math.round(h.score() * 1000.0) / 1000.0);
            m.put("content", h.content());
            if (h.titlePath() != null && !h.titlePath().isBlank()) {
                m.put("titlePath", h.titlePath());
            }
            return m;
        }).toList());
    }

    @Operation(summary = "下载图表", description = "传入 PlantUML 源码 + 格式，按需渲染 svg/png 返回二进制文件")
    @PostMapping("/api/download")
    public ResponseEntity<byte[]> download(@RequestBody DownloadRequest req) {
        try {
            String plantUml = req.plantUml();
            String format = req.format() != null ? req.format() : "png";

            byte[] fileBytes;
            String contentType;
            String filename;

            if ("svg".equals(format)) {
                fileBytes = diagramService.renderToSvg(plantUml).getBytes("UTF-8");
                contentType = "image/svg+xml";
                filename = "flowchart.svg";
            } else {
                fileBytes = diagramService.renderToPng(plantUml);
                contentType = "image/png";
                filename = "flowchart.png";
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(fileBytes.length);

            return ResponseEntity.ok().headers(headers).body(fileBytes);

        } catch (Exception e) {
            System.err.println("下载渲染失败: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
    @Operation(summary = "上传文档到知识库（RAG）", description = "上传 txt/md 文档，切片并向量化存入内存向量库；之后生成时传 useRag=true 即可检索增强")
    @PostMapping("/api/upload")
    public Result<?> upload(@RequestParam("file") MultipartFile file) {
        if (!ragService.isEnabled()) {
            return Result.error(503, "RAG 未配置：请在 application.yml 设置 llm.embedding.api-key（硅基流动免费 key）");
        }
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            int chunks = ragService.ingest(text, file.getOriginalFilename());
            return Result.success(Map.of("chunks", chunks, "source", file.getOriginalFilename()));
        } catch (Exception e) {
            System.err.println("文档入库失败: " + e.getMessage());
            e.printStackTrace();
            return Result.error(500, "文档入库失败：" + e.getMessage());
        }
    }

    @Operation(summary = "成本统计", description = "返回累计调用次数、Token 总量、估算成本（按模型单价表）")
    @GetMapping("/api/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(usageService.getStats());
    }

    /**
     * 把 LLM 原始回复截到 ≤maxLen 个字符，且保证句子完整（不截到半句话）。
     * 句子结束符：中英文 .。！!？? + 双换行
     * 找不到合适结束符时硬截到 maxLen（保底，避免返回空）。
     *
     * 设计：先 strip 去掉首尾空白，从头扫到 maxLen，记录最后一个句子结束符位置，
     * 截到该位置 + 1 字符再补 "…" 后缀。
     */
    static String truncateAtSentence(String text, int maxLen) {
        if (text == null) return "";
        String trimmed = text.strip();
        if (trimmed.length() <= maxLen) return trimmed;
        int cut = -1;
        for (int i = 0; i < maxLen; i++) {
            char c = trimmed.charAt(i);
            // 双换行视为段落结束
            if (c == '\n' && i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '\n') {
                cut = i + 2;
                continue;
            }
            if (c == '。' || c == '.' || c == '！' || c == '!' || c == '？' || c == '?') {
                cut = i + 1;
            }
        }
        if (cut <= 0) cut = maxLen;
        return trimmed.substring(0, cut) + "…";
    }
}
