package io.github.nihaoljx.flowchart.client;

/**
 * LLM 提供商统一接口
 *
 * 设计约束：业务代码依赖抽象接口、不依赖具体实现（依赖倒置，见 architecture.md ADR-3）。
 * 不管底层是 Kimi、DeepSeek 还是 Gemini，对外都是同一个 chat() 方法。
 *
 * chat() 返回提取后的纯文本，不是原始 JSON——调用方因此不必关心是哪家 LLM 的响应格式。
 */
public interface LlmProvider {

    /**
     * 发送 prompt 给 LLM，返回提取后的纯文本
     *
     * @param prompt 完整 prompt（模板 + 用户输入）
     * @return LLM 生成的文本内容（不是原始 JSON）
     * @throws Exception 网络错误、API 错误、解析失败等
     */
    String chat(String prompt) throws Exception;

    /**
     * 检查 API Key 是否已配置
     *
     * @return true = 已配置可用，false = 未配置
     */
    boolean isConfigured();
}
