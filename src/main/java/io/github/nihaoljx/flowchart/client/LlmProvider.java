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
     * 结构化输出：发送 prompt + JSON Schema，让模型在生成阶段贴合契约
     *
     * 任务33新增。对应业界概念（面试考点）：
     * - JSON Mode（语法层）：response_format 保证返回合法 JSON
     * - JSON Schema（契约层）：字段、类型、枚举、必填、额外属性由 Schema 描述
     * - Structured Outputs（生成约束层）：strict:true 是 token 级约束解码（CFG），
     *   采样阶段就过滤非法 token，字段缺失/类型错乱/额外字段在生成时被掐死
     *
     * default 方法的意义：接口演进不破坏现有实现——
     * 老实现（如未来的另一个 Provider）不重写这个方法也能编译运行，
     * 只是退回普通 chat()，没有硬约束。这就是"默认方法"的向后兼容价值。
     *
     * @param prompt     完整 prompt（模板 + 用户输入）
     * @param schemaJson JSON Schema 字符串（描述输出结构契约）
     * @return LLM 生成的文本内容（受 Schema 约束的 JSON）
     * @throws Exception 网络错误、API 错误、解析失败等
     */
    default String chatStructured(String prompt, String schemaJson) throws Exception {
        // 默认实现：退回普通 chat()——不支持的 Provider 也能工作，只是没有硬约束
        return chat(prompt);
    }

    /**
     * 检查 API Key 是否已配置
     *
     * @return true = 已配置可用，false = 未配置
     */
    boolean isConfigured();

    /**
     * 返回该 Provider 的可读名字（如 mimo / kimi），用于前端进度展示
     * default 方法：老实现不重写也能编译，只是返回通用名。
     */
    default String name() {
        return "provider";
    }
}
