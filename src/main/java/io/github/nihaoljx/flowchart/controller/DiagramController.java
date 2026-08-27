package io.github.nihaoljx.flowchart.controller;

import io.github.nihaoljx.flowchart.client.LlmProvider;
import io.github.nihaoljx.flowchart.model.DownloadRequest;
import io.github.nihaoljx.flowchart.model.GenerateRequest;
import io.github.nihaoljx.flowchart.model.FlowchartData;
import io.github.nihaoljx.flowchart.model.MindmapData;
import io.github.nihaoljx.flowchart.model.Result;
import io.github.nihaoljx.flowchart.service.DiagramService;
import io.github.nihaoljx.flowchart.service.ParserService;
import io.github.nihaoljx.flowchart.service.PromptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.github.nihaoljx.flowchart.service.UsageService;
import io.github.nihaoljx.flowchart.service.ValidationException;
import io.github.nihaoljx.flowchart.client.stream.ProgressContext;
import io.github.nihaoljx.flowchart.client.stream.ProgressEvent;
import io.github.nihaoljx.flowchart.client.stream.RoutingContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** SSE 生成用的线程池：HTTP 请求线程立即返回 emitter，生成在后台线程跑，进度通过 SseEmitter 推 */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Operation(summary = "健康检查", description = "返回服务是否正常，常用于容器探活")
    @GetMapping("/api/health")
    public Result<Void> health() {
        return Result.success();
    }

    @Operation(summary = "生成图表", description = "传入文字描述 + 图表类型，调用 LLM 生成图表数据、SVG 与 PlantUML 源码")
    @PostMapping("/api/generate")
    public Result<?> generate(@RequestBody GenerateRequest req) {
        try {
            String userText = req.text();
            // record 字段可能为 null（前端没传），这里给默认值
            String type = req.type() != null ? req.type() : "flowchart";
            String format = req.format() != null ? req.format() : "svg";

            if (!llmProvider.isConfigured()) {
                return Result.error(503, "服务未配置：请联系管理员设置 LLM_API_KEY");
            }
            if (userText == null || userText.isBlank()) {
                return Result.error(400, "请输入流程描述");
            }
            if (userText.length() > 2000) {
                return Result.error(400, "描述过长，请精简到 2000 字以内");
            }
            if (!"flowchart".equals(type) && !"mindmap".equals(type) && !"architecture".equals(type)) {
                return Result.error(400, "不支持的图表类型: " + type);
            }

            // ---- 任务37：Mermaid 分支（前端 mermaid.js 渲染，不调 PlantUML）----
            if ("mermaid".equals(format)) {
                Map<String, Object> result = buildMermaid(userText, type);
                return Result.success(result);
            }

            // ---- 原有 PlantUML / SVG 分支 ----
            String prompt = promptService.buildPrompt(userText, type);
            String schema = promptService.loadSchema(type); // 任务33：加载该类型的 JSON Schema
            String llmText = llmProvider.chatStructured(prompt, schema); // 任务33：结构化输出

            String plantUml;
            Map<String, Object> result = new HashMap<>();

            switch (type) {
                case "mindmap":
                    MindmapData mindmap = parserService.parseMindmap(llmText);
                    plantUml = diagramService.buildMindMap(mindmap);
                    result.put("data", mindmap);
                    break;
                case "architecture":
                    FlowchartData arch = parserService.parseArchitecture(llmText);
                    plantUml = diagramService.buildArchitecture(arch);
                    result.put("data", arch);
                    break;
                default:
                    FlowchartData data = parserService.parse(llmText);
                    plantUml = diagramService.buildPlantUml(data);
                    result.put("data", data);
            }

            String svg = diagramService.renderToSvg(plantUml);
            result.put("svg", svg);
            result.put("plantUml", plantUml);
            result.put("type", type);

            return Result.success(result);

        } catch (ValidationException e) {
            // 校验失败：返回 400 + 结构化错误列表 {field, reason, hint}
            return Result.error(400, e.getMessage(), e.getIssues());

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
                                     @RequestParam(value = "format", defaultValue = "svg") String format) {
        // 120 秒超时（比后端实际生成 60s 多留余量）
        SseEmitter emitter = new SseEmitter(120_000L);

        // 后台线程跑生成：HTTP 请求线程立刻把 emitter 返回给浏览器，进度靠它流式推送
        executor.submit(() -> {
            // 把"进度出水口"挂到当前后台线程（ThreadLocal）。
            // Provider 内部 ProgressContext.publish(event) 会调用这个 lambda，把事件写进 SSE。
            ProgressContext.setSink(event -> {
                try {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("type", event.type());
                    payload.put("message", event.message());
                    if (event.provider() != null) payload.put("provider", event.provider());
                    if (event.data() != null) payload.putAll(event.data());
                    emitter.send(SseEmitter.event().name("progress").data(payload));
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            });

            // 把"前端选中的模型"挂到当前请求线程：FallbackLlmProvider 选主模型时会读它
            RoutingContext.set(model);

            try {
                // ---- 参数校验（和 /api/generate 保持一致）----
                if (text == null || text.isBlank()) {
                    sendError(emitter, "请输入流程描述");
                    return;
                }
                if (text.length() > 2000) {
                    sendError(emitter, "描述过长，请精简到 2000 字以内");
                    return;
                }
                if (!"flowchart".equals(type) && !"mindmap".equals(type) && !"architecture".equals(type)) {
                    sendError(emitter, "不支持的图表类型: " + type);
                    return;
                }
                if (!llmProvider.isConfigured()) {
                    sendError(emitter, "服务未配置：请联系管理员设置 LLM_API_KEY");
                    return;
                }

                ProgressContext.publish(ProgressEvent.info("开始生成「" + type + "」…"));

                // 任务37：Mermaid 分支（前端渲染，不调 PlantUML）
                if ("mermaid".equals(format)) {
                    Map<String, Object> result = buildMermaid(text, type);
                    emitter.send(SseEmitter.event().name("done").data(result));
                    emitter.complete();
                    return;
                }

                String prompt = promptService.buildPrompt(text, type);
                String schema = promptService.loadSchema(type); // 任务33：加载该类型的 JSON Schema
                String llmText = llmProvider.chatStructured(prompt, schema); // 走 Cache→Fallback→Provider，内部会推进度

                ProgressContext.publish(ProgressEvent.info("模型已返回，正在解析并渲染…"));

                String plantUml;
                Map<String, Object> result = new HashMap<>();

                switch (type) {
                    case "mindmap":
                        MindmapData mindmap = parserService.parseMindmap(llmText);
                        plantUml = diagramService.buildMindMap(mindmap);
                        result.put("data", mindmap);
                        break;
                    case "architecture":
                        FlowchartData arch = parserService.parseArchitecture(llmText);
                        plantUml = diagramService.buildArchitecture(arch);
                        result.put("data", arch);
                        break;
                    default:
                        FlowchartData data = parserService.parse(llmText);
                        plantUml = diagramService.buildPlantUml(data);
                        result.put("data", data);
                }

                String svg = diagramService.renderToSvg(plantUml);
                result.put("svg", svg);
                result.put("plantUml", plantUml);
                result.put("type", type);

                // 收尾事件：把最终 SVG 推给前端，然后关闭流
                emitter.send(SseEmitter.event().name("done").data(result));
                emitter.complete();

            } catch (ValidationException e) {
                sendError(emitter, e.getMessage());
            } catch (Exception e) {
                System.err.println("SSE 生成失败: " + e.getMessage());
                e.printStackTrace();
                sendError(emitter, "AI 生成失败：" + e.getMessage());
            } finally {
                ProgressContext.clear();  // 必须摘掉 ThreadLocal，避免线程池复用串数据
                RoutingContext.clear();   // 同上：清除"选中的模型"，否则下一个请求误用
            }
        });

        return emitter;
    }

    /** 统一发一条 error 事件并关闭流 */
    private void sendError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", msg)));
            emitter.complete();
        } catch (Exception ignore) {
            emitter.completeWithError(ignore);
        }
    }

    /**
     * 任务37：生成 Mermaid 图表文本
     *
     * 让 LLM 直接产出 mermaid 代码（不生成 PlantUML、不渲染 SVG），
     * 由前端 mermaid.js 在浏览器里渲染。返回的结果 Map 含 mermaid 字段，svg/plantUml 留空占位。
     */
    private Map<String, Object> buildMermaid(String text, String type) throws Exception {
        String prompt = promptService.buildMermaidPrompt(text, type);
        String schema = promptService.loadMermaidSchema();
        String llmText = llmProvider.chatStructured(prompt, schema); // 走 Cache→Fallback→Provider，内部会推进度
        String mermaid = parserService.extractMermaid(llmText);
        Map<String, Object> result = new HashMap<>();
        result.put("mermaid", mermaid);
        result.put("format", "mermaid");
        result.put("type", type);
        result.put("svg", "");
        result.put("plantUml", "");
        return result;
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
    @Operation(summary = "成本统计", description = "返回累计调用次数、Token 总量、估算成本（按模型单价表）")
    @GetMapping("/api/stats")
    public Result<Map<String, Object>> stats() {
        return Result.success(usageService.getStats());
    }
}
