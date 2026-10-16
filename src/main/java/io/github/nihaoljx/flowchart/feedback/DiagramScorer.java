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
 * <p>2026-10-16 二次修正（起因：用户贴出项目里那张原版猫图，指出
 * "画成零件关系图的猫"该打 0 分，而系统给了 0.6）——**删掉
 * 「无参考 = 形状覆盖率」这条打分路径**。它错在拿"图内部自洽"
 * 冒充"符合需求"：15 个椭圆被 9 条箭头串起来，覆盖率当然接近 1，
 * 可它离「一只猫」的距离是零分。自洽的废话也是自洽的。
 *
 * <p>现在的口径只有两条，都要求**有参考**才成立：
 * <ul>
 *   <li>结构分 = 必含节点/连线的召回均值（连接正确性硬指标）；</li>
 *   <li>可读性分 = 几何批判 issue 线性扣分（画得整不整齐）。</li>
 * </ul>
 * 无参考时不计算结构分 —— 由 {@link FidelityScorer} 直接给出
 * {@link FidelityReport.Verdict#UNVERIFIABLE}（不给分），
 * 而不是拿一个替代指标顶上。
 *
 * <p>权重刻意偏向结构：结构错了的图没有任何可取之处；几何观感问题
 * （重叠/交叉/溢出）占三成，是可修复的次级质量。
 */
public final class DiagramScorer {

    /** 结构分权重（节点/连线召回） */
    public static final double W_STRUCTURE = 0.7;
    /** 可读性分权重（几何批判 issue 越少越高） */
    public static final double W_READABILITY = 0.3;
    /** 每条几何 issue 的扣分（可读性分按 issue 数线性衰减） */
    private static final double ISSUE_PENALTY = 0.15;

    /**
     * 结构分（0..1）= (节点召回 + 连线召回) / 2。
     *
     * <p>前置条件：{@code semantic} 必须来自**真实的参考结构比对**
     * （{@code SemanticReport.empty(null)} 这类没有参考的报告不要传进来 ——
     * 调用方先判"有没有参考"，没有就别算分）。
     *
     * @param semantic 参考结构比对报告；null 视为 0
     */
    public double structureOf(SemanticReport semantic) {
        if (semantic == null) {
            return 0.0;
        }
        // 防御：空参考（两侧要求数都是 0）时 recall 按「不惩罚」定义恒为 1 ——
        // 直接乘出来就是"白送满分"，正是旧版踩过的坑。这里显式判死：
        // 一条要求都没有 = 没有可比对的东西，结构分只能是 0。
        if (semantic.totalNodes() == 0 && semantic.totalEdges() == 0) {
            return 0.0;
        }
        double nodeRecall = semantic.nodeRecall();
        double edgeRecall = semantic.edgeRecall();
        return clamp01((nodeRecall + edgeRecall) / 2.0);
    }

    /**
     * 可读性分（0..1）：每条几何 issue 线性扣 {@value #ISSUE_PENALTY}。
     * 公开给 {@link FidelityScorer} 复用，保证两套口径一致。
     */
    public double readability(List<CritiqueIssue> geometric) {
        int issues = geometric == null ? 0 : geometric.size();
        return Math.max(0.0, 1.0 - issues * ISSUE_PENALTY);
    }

    /** 合成分：结构分与可读性分加权 */
    public double combine(double structure, double readability) {
        return clamp01(W_STRUCTURE * clamp01(structure)
                + W_READABILITY * clamp01(readability));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
