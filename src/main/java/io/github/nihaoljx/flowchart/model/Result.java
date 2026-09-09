package io.github.nihaoljx.flowchart.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一 API 响应格式
 *
 * 所有接口的返回值都包一层 Result，前端只需要判断 code，
 * 不用每个接口写不同的处理逻辑。
 *
 * 成功：{ "code": 200, "message": "ok", "data": {...} }
 * 失败：{ "code": 400, "message": "请输入流程描述", "data": null }
 */
public class Result<T> {

    private int code;        // 状态码：200 成功，400 参数错误，500 服务器错误
    private String message;  // 提示信息
    private T data;          // 实际数据（泛型，什么类型都行）
    /**
     * LLM 原始回复的截短摘要（≤200 字 + 句子完整），仅在 schema 校验失败时附带，
     * 用于前端折叠区展示"AI 实际说了什么"。@JsonInclude(NON_NULL) 保证成功响应不带这个字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String aiSummary;

    // ===== 构造方法 =====
    private Result(int code, String message, T data) {
        this(code, message, data, null);
    }

    private Result(int code, String message, T data, String aiSummary) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.aiSummary = aiSummary;
    }

    // ===== 工厂方法：成功 =====
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "ok", data);
    }

    // ===== 工厂方法：成功（无数据） =====
    public static <T> Result<T> success() {
        return new Result<>(200, "ok", null);
    }

    // ===== 工厂方法：失败 =====
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    // ===== 工厂方法：失败（带数据，如校验错误详情列表） =====
    public static <T> Result<T> error(int code, String message, T data) {
        return new Result<>(code, message, data);
    }

    // ===== 工厂方法：失败（带 LLM 原始回复摘要，用于 schema 校验失败时透传 AI 实际说的话） =====
    public static <T> Result<T> error(int code, String message, T data, String aiSummary) {
        return new Result<>(code, message, data, aiSummary);
    }


    // ===== Getter（Jackson 需要） =====
    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public String getAiSummary() { return aiSummary; }
}
