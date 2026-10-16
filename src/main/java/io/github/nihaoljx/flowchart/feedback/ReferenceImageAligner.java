package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.graph.validate.ReferenceSpec;

import java.util.Objects;

/**
 * 参考图结构对齐器（D8，Route3 钩子）：把「生成图 vs 参考图」的匹配
 * 从像素比对纠正为**结构对齐**。
 *
 * <p>为什么不是像素比对：像素奖励不可校验的表象，且对本项目的
 * 手绘渲染风格极端脆弱；结构对齐（必含节点/连线召回）才是可机判、
 * 可复现、可解释（每条缺失都点名）的硬指标。
 *
 * <p>两条用法：
 * <ol>
 *   <li>参考结构已有人工/规则来源 → 直接 {@link #align(String, ReferenceSpec)}；</li>
 *   <li>只有一张参考图/草图 → {@link #align(String, byte[], SketchToSpecExtractor)}
 *       先经视觉提取器转成参考结构再对齐（提取器当前无生产实现，
 *       是 Route3 的后续集成点；提取失败自动降级为无参考度量）。</li>
 * </ol>
 */
public final class ReferenceImageAligner {

    private final FidelityScorer scorer = new FidelityScorer();

    /** 参考结构已知：直接结构对齐 */
    public FidelityReport align(String sceneJson, ReferenceSpec reference) {
        return scorer.score(sceneJson, reference);
    }

    /**
     * 参考是一张图：经提取器转成参考结构再对齐。
     * 提取器缺失或提取失败 → 降级为无参考度量（置信 LOW、封顶），
     * 绝不抛异常打断调用方。
     */
    public FidelityReport align(String sceneJson, byte[] sketch,
                                SketchToSpecExtractor extractor) {
        ReferenceSpec ref = null;
        if (sketch != null && extractor != null) {
            try {
                ref = extractor.extract(sketch, null);
            } catch (RuntimeException e) {
                ref = null; // 提取失败按无参考降级
            }
        }
        return scorer.score(sceneJson, ref);
    }

    /** 便捷判定：这张生成图是否**可信地**匹配了参考（HIGH 置信 + 高分） */
    public boolean matches(FidelityReport report, double threshold) {
        Objects.requireNonNull(report, "report");
        return report.confidence() == FidelityReport.Confidence.HIGH
                && report.overall() >= threshold;
    }
}
