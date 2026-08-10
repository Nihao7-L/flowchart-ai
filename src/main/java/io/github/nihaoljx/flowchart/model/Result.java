package io.github.nihaoljx.flowchart.model;

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

    // ===== 构造方法 =====
    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
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

    // ===== Getter（Jackson 需要） =====
    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
}
