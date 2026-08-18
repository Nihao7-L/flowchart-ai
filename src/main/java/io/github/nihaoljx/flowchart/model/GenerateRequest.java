package io.github.nihaoljx.flowchart.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** 生成图表请求体（用 record 替代 Map，字段自带文档） */
@Schema(description = "生成图表请求")
public record GenerateRequest(
        @Schema(description = "用户的文字描述", example = "用户输入账号密码→系统验证→进入首页")
        String text,

        @Schema(description = "图表类型：flowchart / mindmap / architecture", example = "flowchart")
        String type,

        @Schema(description = "渲染格式：svg / png", example = "svg")
        String format
) {
}
