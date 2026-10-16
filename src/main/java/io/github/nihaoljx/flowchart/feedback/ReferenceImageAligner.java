package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.graph.validate.ReferenceSpec;

import java.util.Objects;

/**
 * 参考图结构对齐器（D8，Route3 钩子）：把「生成图 vs 参考图」的匹配
 * 从像素比对纠正为**结构对齐**。
 *
 * <p>为什么不是像素比对：像素奖励不可校验的表象，且对本项目的手绘
 * 渲染风格极端脆弱；结构对齐（必含节点/连线召回）才是可机判、可复现、
 * 可解释（每条缺失都点名）的硬指标。
 *
 * <p>两条用法：
 * <ol>
 *   <li>参考结构已有人工/规则来源 → {@link #align(String, String, ReferenceSpec)}；</li>
 *   <li>只有一张参考图/草图 → {@link #align(String, String, byte[],
 *       SketchToSpecExtractor)} 先经视觉提取器转成参考结构再对齐
 *       （提取器当前无生产实现，是 Route3 的后续集成点；提取失败
 *       自动降级为"无参考"结论 —— 此时**不给分**，绝不硬凑一个数）。</li>
 * </ol>
 *
 * <p>2026-10-16 起所有入口都带需求文本：对齐的第一步是判形态
 * （图表需求 vs 绘画需求），形态不符的图，结构召回再高也没有意义。
 */
public final class ReferenceImageAligner {

    private final FidelityScorer scorer = new FidelityScorer();

    /** 参考结构已知：直接结构对齐 */
    public FidelityReport align(String request, String sceneJson,
                                ReferenceSpec reference) {
        return scorer.score(request, sceneJson, reference);
    }

    /**
     * 参考是一张图：经提取器转成参考结构再对齐。
     * 提取器缺失或提取失败 → 降级为"无参考"（不给分），
     * 绝不抛异常打断调用方。
     */
    public FidelityReport align(String request, String sceneJson,
                                byte[] sketch,
                                SketchToSpecExtractor extractor) {
        ReferenceSpec ref = null;
        if (sketch != null && extractor != null) {
            try {
                ref = extractor.extract(sketch, null);
            } catch (RuntimeException e) {
                ref = null; // 提取失败按无参考降级
            }
        }
        return scorer.score(request, sceneJson, ref);
    }

    /** 便捷判定：这张生成图是否**可信地**匹配了参考（有参考且高分） */
    public boolean matches(FidelityReport report, double threshold) {
        Objects.requireNonNull(report, "report");
        return report.verifiable()
                && report.fidelity() >= threshold;
    }
}
