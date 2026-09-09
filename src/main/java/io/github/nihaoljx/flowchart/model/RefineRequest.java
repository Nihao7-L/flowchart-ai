package io.github.nihaoljx.flowchart.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 画布编辑回流 Refine 请求体（任务45）
 *
 * 与生成接口的区别：生成接口吃"文字描述"，refine 吃"当前已经画好的图 + 一句修改意图"。
 * graphJson 就是人在画布上看到的图（可能已经被拖过、连过、改过标签），
 * instruction 是"帮我加一个验证码步骤""把支付分支改成异步"这类自然语言指令。
 *
 * 设计：v1 整个 graph 回流（简单可靠，不丢人工编辑）；
 *      后端把 graphJson 序列化成 JSON 塞进 prompt，让 LLM 输出"修改后的完整 graphJson"。
 */
@Schema(description = "画布编辑回流 Refine 请求")
public record RefineRequest(
        @Schema(description = "图表类型：flowchart / mindmap / architecture", example = "flowchart")
        String type,

        @Schema(description = "当前画布上的图（含人工编辑），作为 Refine 的输入基线")
        GraphJson graphJson,

        @Schema(description = "修改意图，如：在登录后加一个验证码校验步骤", example = "加一个验证码步骤")
        String instruction,

        @Schema(description = "指定模型：mimo / kimi", example = "mimo")
        String model,

        @Schema(description = "渲染格式：svg / mermaid", example = "svg")
        String format,

        @Schema(description = "是否启用 RAG 检索增强", example = "false")
        Boolean useRag,

        @Schema(description = "是否启用 Tool Calling 路由", example = "false")
        Boolean useTool,

        @Schema(description = "会话 ID（任务40：多轮记忆）。首次不传或为空，由后端建新会话；后续 refine 带上以延续上下文", example = "")
        String sessionId
) {
}
