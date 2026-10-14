package io.github.nihaoljx.flowchart.llm;

import java.util.List;

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
     * 发送 prompt 给 LLM，并把"整次调用"限制在指定时限内
     *
     * 为什么需要这个重载：`HttpRequest.timeout()` 的计时在收到
     * 响应首字节后就停止，而推理类模型会先回一个响应头、再花上百秒
     * 生成正文——于是配置里的固定超时形同虚设（实测：
     * 配置 60 秒，实际跑满 97.7 秒仍未被中断）。
     *
     * 实现方必须按"连接 + 响应头 + 正文"全过程计时，调用方才能
     * 按剩余时间预算收紧单轮上限，避免总耗时顶穿 SSE 通道上限。
     *
     * @param prompt         完整 prompt（模板 + 用户输入）
     * @param timeoutSeconds 整次调用的硬上限，单位秒
     * @return LLM 生成的文本内容（不是原始 JSON）
     * @throws Exception 网络错误、API 错误、超时、解析失败等
     */
    String chat(String prompt, int timeoutSeconds)
            throws Exception;

    /**
     * 发送多消息给 LLM —— system / user 角色由此真正生效
     *
 * 为什么需要它：`chat(String, int)` 只能发一条消息，而上下文组装
     * 需要把「固定不变的规则」与「每轮变化的内容」分成 system / user 两条，
     * 让模型分得清哪些是规则、哪些是本次的内容。
     *
 * 上面两个 `String` 重载**保留不动**（等价于"只有一条 user 消息"）：
     * M13 网关加固（重试 / token 埋点）与它正交，可后续叠加。
     *
     * @param messages       有序消息列表（通常 system 在前、user 在后）
     * @param timeoutSeconds 整次调用的硬上限，单位秒
     * @return LLM 生成的文本内容（不是原始 JSON，与旧重载语义一致）
     * @throws Exception 网络错误、API 错误、超时、解析失败等
     */
    String chat(List<Message> messages, int timeoutSeconds)
            throws Exception;

    /**
     * 检查 API Key 是否已配置
     *
     * @return true = 已配置可用，false = 未配置
     */
    boolean isConfigured();
}
