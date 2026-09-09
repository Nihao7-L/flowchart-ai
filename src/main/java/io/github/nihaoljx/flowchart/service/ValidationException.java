package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.ValidationIssue;

import java.util.List;

/**
 * 数据校验失败异常（任务32新增）
 *
 * 和普通 Exception 的区别：携带结构化错误列表 List<ValidationIssue>，
 * Controller / 前端能拿到"哪里错、怎么改"，而不是一句笼统的"解析失败"。
 *
 * 为什么用 RuntimeException 而不是 Exception？
 * - parse() 等方法签名已经是 throws Exception，抛 RuntimeException 调用方同样能 catch（RuntimeException 是 Exception 子类）
 * - 校验失败是"业务预期错误"而非程序 bug，但为了不强迫调用方写 catch，
 *   用运行时异常更省事——Controller 的 catch (Exception e) 依然能兜住
 *
 * 为什么 message 只写"共 N 个问题"？
 * 具体细节都在 issues 列表里，message 只做摘要；
 * 前端要渲染的是 issues，不是 message。
 *
 * aiSummary 是 LLM 原始回复的截短摘要（≤200 字 + 句子完整）：
 * 用于在校验失败时把"AI 实际说了什么"展示给用户，方便排查"AI 是不是把招呼当聊天了"。
 * 可能为 null（非 LLM 校验失败时无意义）。
 */
public class ValidationException extends RuntimeException {

    private final List<ValidationIssue> issues;
    private final String aiSummary;

    public ValidationException(List<ValidationIssue> issues) {
        this(issues, null);
    }

    public ValidationException(List<ValidationIssue> issues, String aiSummary) {
        super("数据校验未通过，共 " + issues.size() + " 个问题");
        this.issues = issues;
        this.aiSummary = aiSummary;
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }

    public String getAiSummary() {
        return aiSummary;
    }
}
