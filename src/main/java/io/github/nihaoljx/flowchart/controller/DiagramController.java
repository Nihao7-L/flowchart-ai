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

import java.util.HashMap;
import java.util.Map;

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

    @Operation(summary = "健康检查", description = "返回服务是否正常，常用于容器探活")
    @GetMapping("/api/health")
    public Result<Void> health() {
        return Result.success();
    }

    @Operation(summary = "生成图表", description = "传入文字描述 + 图表类型，调用 LLM 生成图表数据、SVG 与 PlantUML 源码")
    @PostMapping("/api/generate")
    public Result<Map<String, Object>> generate(@RequestBody GenerateRequest req) {
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

            String prompt = promptService.buildPrompt(userText, type);
            String llmText = llmProvider.chat(prompt);

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

        } catch (Exception e) {
            System.err.println("生成图表失败: " + e.getMessage());
            e.printStackTrace();
            return Result.error(500, "AI 生成失败，请换一种描述试试");
        }
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
}
