package io.github.nihaoljx.flowchart.feedback;

/**
 * 结构优先保真度量报告（D5）。
 *
 * @param structural  结构分 0..1（有参考=召回均值（覆盖率下修）；无参考=形状覆盖率）
 * @param readability 可读性分 0..1（几何批判 issue 线性扣分）
 * @param overall     总分 0..1 = 0.7*结构 + 0.3*可读；无参考时封顶
 *                    {@link DiagramScorer#NO_REF_CAP}
 * @param confidence  置信度：有参考 HIGH（连接正确性可机判）；无参考 LOW
 *                    （语义不可验证，分数只是几何/连通性证据）
 */
public record FidelityReport(double structural, double readability,
                             double overall, Confidence confidence) {

    public enum Confidence { HIGH, LOW }

    /** 无参考要求时的便捷判定：报告是否可信地表达了「图是好的」 */
    public boolean verifiable() {
        return confidence == Confidence.HIGH;
    }
}
