package io.github.nihaoljx.flowchart.controller;

import io.github.nihaoljx.flowchart.client.AiApiClient;
import io.github.nihaoljx.flowchart.model.FlowchartData;
import io.github.nihaoljx.flowchart.model.Result;
import io.github.nihaoljx.flowchart.service.DiagramService;
import io.github.nihaoljx.flowchart.service.ParserService;
import io.github.nihaoljx.flowchart.service.PromptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 流程图相关接口
 *
 * /api/health  → 健康检查
 * /api/generate → 核心：文字 → 流程图数据
 */
@RestController
public class DiagramController {

    // Spring 自动注入（@Autowired 告诉 Spring 帮我找这些 Bean 塞进来）
    @Autowired private PromptService promptService;
    @Autowired private AiApiClient aiApiClient;
    @Autowired private ParserService parserService;
    @Autowired private DiagramService diagramService;  // ← 新增


    /**
     * 健康检查接口
     * GET /api/health
     */
    @GetMapping("/api/health")
    public Result<Void> health() {
        return Result.success();  // 返回 {"code":200,"message":"ok","data":null}
    }

    /**
     * 核心接口：接收用户文字，返回流程图数据
     *
     * 请求体 JSON：
     * { "text": "用户输入账号密码 → 系统验证 → 成功进入首页，失败提示错误" }
     *
     * 响应 JSON：
     * { "success": true, "data": { "title": "...", "nodes": [...], "edges": [...] } }
     * 或
     * { "success": false, "error": "错误原因" }
     *
     * @param body 请求体，用 Map 接收（简单场景不想建专门的请求类）
     * @return 流程图数据 或 错误信息
     */
    @PostMapping("/api/generate")
    public Result<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        try {
            String userText = body.get("text");
            if (userText == null || userText.isBlank()) {
                return Result.error(400, "请输入流程描述");
            }
            if (userText.length() > 2000) {
                return Result.error(400, "描述过长，请精简到 2000 字以内");
            }

            String prompt = promptService.buildPrompt(userText);
            String llmResponse = aiApiClient.chat(prompt);
            FlowchartData data = parserService.parse(llmResponse);

            // 任务7：JSON → PlantUML 语法
            String plantUml = diagramService.buildPlantUml(data);

            // 任务8：PlantUML → SVG 渲染
            String svg = diagramService.renderToSvg(plantUml);

            // 打包返回：flowchart 数据 + SVG
            Map<String, Object> result = Map.of(
                    "flowchart", data,
                    "svg", svg
            );

            return Result.success(result);

        } catch (Exception e) {
            // 日志记录真实错误（排查用），前端只给用户友好的提示
            System.err.println("生成流程图失败: " + e.getMessage());
            e.printStackTrace();
            return Result.error(500, "AI 生成失败，请换一种描述试试");
        }
    }

}
