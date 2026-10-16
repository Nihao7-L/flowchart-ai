package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.graph.validate.ReferenceSpec;

/**
 * 草图→参考结构提取器（Route3 混合架构的集成缝，D8）。
 *
 * <p>文章 Route3 的正解：对「匹配某张具体参考图外观」这个子目标，
 * 扩散模型出草图 + 视觉模型提取结构 + IR 编译出精确可编辑图，
 * 是「扩散审美 + LLM 精确」的合体。本接口就是其中「视觉提取结构」
 * 的位置：输入一张图（用户参考图 / 扩散草图），输出机器可判的
 * {@link ReferenceSpec}（必含节点/连线）——后续的度量与对齐
 * 全部复用结构层既有能力（SemanticValidator / FidelityScorer）。
 *
 * <p>当前项目没有接视觉模型，生产实现是后续工作（需要一个
 * vision-capable LLM 或专用提取服务）。接口先行是为了：
 * ① 集成点明确（换实现不动度量层）；② 测试可以用假提取器锁行为。
 */
public interface SketchToSpecExtractor {

    /**
     * 从图片提取参考结构。
     *
     * @param image 图片字节（png/jpeg）
     * @param hint  提取提示（可选，如「这是一张认证流程图」）
     * @return 提取出的参考结构；提取失败返回 null（调用方按无参考降级）
     */
    ReferenceSpec extract(byte[] image, String hint);
}
