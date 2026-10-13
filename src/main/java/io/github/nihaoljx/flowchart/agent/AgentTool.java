package io.github.nihaoljx.flowchart.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 领域工具接口（v2-55，learn-claude-code s02 的工具协议）
 *
 * <p>四件套纪律：工具只做"读状态 / 收场景 / 查契约"这类领域动作，
 * <b>不提供</b> Bash / 文件等通用工具 —— 这是垂直生成管线，工具越多
 * 模型迷路概率与 token 成本都涨（计划 v2-55 第二节）。
 *
 * <p>实现要求：{@link #execute} 必须自己兜住业务异常（返回
 * {@link Observation#fail}），抛出去的异常会被 {@link ToolRegistry}
 * 兜成通用失败 —— 不让单次工具失败打穿整个循环。
 */
public interface AgentTool {

    /** 工具名（ snake_case，注册表键，须唯一） */
    String name();

    /** 给模型看的一句话说明（写清"什么时候该调我"） */
    String description();

    /**
     * 参数定义（JSON Schema 的 properties/required 层）。
     * 进请求体 tools 字段时由 {@link ToolRegistry#schemas()} 统一包壳。
     */
    Map<String, Object> parametersSchema();

    /**
     * 执行工具。
     *
     * @param args 模型给的参数（JSON 对象；缺失/畸形由工具自己报）
     * @param ctx  本次 run 的共享上下文
     * @return 结构化观察结果
     * @throws Exception 只允许基础设施级异常外抛（注册表会兜）
     */
    Observation execute(JsonNode args, ToolContext ctx) throws Exception;
}
