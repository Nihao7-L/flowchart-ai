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
 * <p>权重刻意偏向结构（0.6）：结构错了的图没有任何可取之处；
 * 几何观感问题（重叠/交叉/溢出）占 0.4，是可修复的次级质量。
 */
public final class DiagramScorer {

    /** 结构分权重（节点+连线召回的均值） */
    private static final double W_STRUCTURE = 0.6;
    /** 可读性分权重（几何批判 issue 越少越高） */
    private static final double W_READABILITY = 0.4;

    /** 每条几何 issue 的扣分（可读性分按 issue 数线性衰减） */
    private static final double ISSUE_PENALTY = 0.15;

    /**
     * @param semantic   语义比对报告（无参考要求时 recall 恒 1，不惩罚）
     * @param geometric  几何批判 issue 列表（可为空表）
     * @return 0..1 总分
     */
    public double score(SemanticReport semantic, List<CritiqueIssue> geometric) {
        double structure = semantic == null ? 1.0
                : (semantic.nodeRecall() + semantic.edgeRecall()) / 2.0;
        int issues = geometric == null ? 0 : geometric.size();
        double readability = Math.max(0.0, 1.0 - issues * ISSUE_PENALTY);
        return clamp(W_STRUCTURE * structure + W_READABILITY * readability);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
