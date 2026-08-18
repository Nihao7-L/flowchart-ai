package io.github.nihaoljx.flowchart.client;

/**
 * LLM 提供商统一接口
 *
 * 面试考点：依赖倒转原则（DIP）——业务代码依赖抽象接口，不依赖具体实现
 * 不管底层是 Kimi、DeepSeek 还是 Gemini，对外都是同一个 chat() 方法
 *
 * 参考文档里的对应概念：
 * - 这就是 "Provider Adapter 统一封装" 的最简版
 * - chat() 返回提取后的纯文本，不是原始 JSON
 *   这样 ParserService 不用关心是哪个 LLM 返回的
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
