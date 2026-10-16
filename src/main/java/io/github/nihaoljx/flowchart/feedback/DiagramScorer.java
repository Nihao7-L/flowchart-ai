package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.critique.CritiqueIssue;
import io.github.nihaoljx.flowchart.graph.validate.SemanticReport;

import java.util.List;

/**
 * 图打分器（D4 数据飞轮的裁判）：把「结构语义 + 几何可读性」合成一个
 * 0..1 的分。规则硬指标为主（IR 可校验是本路线的核心优势），
 * 不依赖视觉模型 —— 分数必须**可复现**，这样 best-of-N 选优与
 * 后续蒸馏数据集才有稳定标签。
 *
 * <p>结构分的两个来源（2026-10-16 修正，起因是 ref_cat 无参考打 1.0
 * 被用户否决 ——「这是猫吗就给满分」）：
 * <ul>
 *   <li>有参考结构：节点/连线召回的均值（连接正确性硬指标）；</li>
 *   <li>无参考结构：**形状覆盖率**（有连线挂靠/被 frame 统辖的形状占比）。
 *       零连线的一堆形状必须被打下来，不能白送满分。</li>
 * </ul>
 * 并且：**无参考时总分封顶 {@value #NO_REF_CAP}** —— 语义不可验证的
 * 图，无论多干净都配不上高分（置信度低的分数必须显式打折）。
 *
 * <p>权重刻意偏向结构（0.6）：结构错了的图没有任何可取之处；
 * 几何观感问题（重叠/交叉/溢出）占 0.4，是可修复的次级质量。
 */
public final class DiagramScorer {

    /** 结构分权重（召回或覆盖率） */
    private static final double W_STRUCTURE = 0.6;
    /** 可读性分权重（几何批判 issue 越少越高） */
    private static final double W_READABILITY = 0.4;
    /** 每条几何 issue 的扣分（可读性分按 issue 数线性衰减） */
    private static final double ISSUE_PENALTY = 0.15;
    /** 无参考结构时的总分封顶：语义不可验证 = 置信度低，必须打折 */
    public static final double NO_REF_CAP = 0.6;

    /**
     * 结构分（0..1）：有参考 = 召回均值（覆盖率作下修项）；
     * 无参考 = 形状覆盖率。公开给 {@link FidelityScorer} 复用同口径。
     */
    public double structureOf(SemanticReport semantic, double coverage) {
        boolean hasRef = semantic != null
                && (semantic.totalNodes() > 0 || semantic.totalEdges() > 0);
        double structure = hasRef
                ? (semantic.nodeRecall() + semantic.edgeRecall()) / 2.0
                : clamp01(coverage);
        // 有参考时覆盖率仍作为下修项：召回满格但形状孤立 ≠ 好结构
        if (hasRef) {
            structure = Math.min(structure, clamp01(coverage));
        }
        return clamp01(structure);
    }

    /**
     * @param semantic   语义比对报告（可为 empty）
     * @param coverage   形状覆盖率 0..1（无参考时作结构分；有参考时仍参与
     *                   结构分下修 —— 全召回但半数形状孤立同样是坏结构）
     * @param geometric  几何批判 issue 列表（可为空表）
     * @return 0..1 总分；无参考时最高 {@value #NO_REF_CAP}
     */
    public double score(SemanticReport semantic, double coverage,
                        List<CritiqueIssue> geometric) {
        boolean hasRef = semantic != null
                && (semantic.totalNodes() > 0 || semantic.totalEdges() > 0);
        double structure = structureOf(semantic, coverage);
        double score = W_STRUCTURE * structure
                + W_READABILITY * readability(geometric);
        if (!hasRef) {
            score = Math.min(score, NO_REF_CAP);
        }
        return clamp01(score);
    }

    /**
     * 可读性分（0..1）：每条几何 issue 线性扣 {@value #ISSUE_PENALTY}。
     * 公开给 {@link FidelityScorer} 复用，保证两套口径一致。
     */
    public double readability(List<CritiqueIssue> geometric) {
        int issues = geometric == null ? 0 : geometric.size();
        return Math.max(0.0, 1.0 - issues * ISSUE_PENALTY);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
