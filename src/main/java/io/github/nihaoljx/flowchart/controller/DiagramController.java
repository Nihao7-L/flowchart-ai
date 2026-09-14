package io.github.nihaoljx.flowchart.controller;

import io.github.nihaoljx.flowchart.model.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图表接口
 *
 * 现状：只剩健康检查。旧的 /api/generate 与 /api/download 已在 M1 前置代码清算中删除——
 * 目标架构下后端不渲染图片，坐标与渲染由前端白板兜底（见 architecture.md ADR-4）；
 * PlantUML 依赖也已一并从 pom 移除。
 *
 * M1（v2-8 ~ v2-11）将在此补入 POST /api/chat（SSE），承载"文字 → 图表 IR"的主链路。
 */
@Tag(name = "图表接口", description = "健康检查；文字 → 图表 IR 的生成接口（/api/chat）由 M1 补入")
@RestController
public class DiagramController {

    @Operation(summary = "健康检查", description = "返回服务是否正常，常用于容器探活")
    @GetMapping("/api/health")
    public Result<Void> health() {
        return Result.success();
    }
}
