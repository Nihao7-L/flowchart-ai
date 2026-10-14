package io.github.nihaoljx.flowchart.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;

import java.util.List;

/**
 * 工具执行的观察结果（，agent loop 的 Observation）
 *
 * 设计对齐 s01/s02 纪律：observation 必须结构化、有界、能直接
 * 追加回 messages 给模型看。`summary` 是给人与模型看的一句话；
 * `data` 是结构化载荷（JSON）；`issues` 只在"提交了场景但
 * 校验未过"时有值 —— 循环拿它去 `PromptService.buildFixBlock`
 * 生成修正指令，不重复做格式化。
 *
 * @param ok      执行是否达成目的（参数缺失 / 执行异常 / 校验未过都算 false）
 * @param summary 一句话结果（回喂给模型的主要内容）
 * @param data    结构化载荷（可空；序列化后随 summary 一起回喂）
 * @param issues  校验 issue 清单（非校验类结果恒为空列表）
 */
public record Observation(boolean ok, String summary, JsonNode data,
                          List<ValidationIssue> issues) {

    public Observation {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 成功结果（无 issue） */
    public static Observation ok(String summary, JsonNode data) {
        return new Observation(true, summary, data, List.of());
    }

    /** 失败结果：携带 issue（校验类）或只带说明（参数/执行类） */
    public static Observation fail(String summary, JsonNode data,
                                   List<ValidationIssue> issues) {
        return new Observation(false, summary, data, issues);
    }
}
