package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.critique.GeometricCritic;
import io.github.nihaoljx.flowchart.graph.validate.ReferenceSpec;
import io.github.nihaoljx.flowchart.graph.validate.SemanticReport;
import io.github.nihaoljx.flowchart.graph.validate.SemanticValidator;

import java.util.List;

/**
 * 结构优先保真度量器（D5）：一张生成图离「对」有多远。
 *
 * <p>度量哲学（对齐行业标尺 + 战略报告 D5 结论）：
 * <ul>
 *   <li>**连接正确性是硬指标** —— 有 {@link ReferenceSpec} 时用节点/连线
 *       召回（IR 可机判，这是本路线对扩散路线的根本优势）；</li>
 *   <li>**无参考时绝不送满分** —— 语义不可验证，结构分退化为形状覆盖率
 *       且总分封顶 {@link DiagramScorer#NO_REF_CAP}（ref_cat 1.0 事故的教训）；</li>
 *   <li>**像素只是辅助** —— 本类不实现像素比对（留待 Route3 混合架构），
 *       可读性用确定性几何批判近似。</li>
 * </ul>
 * 权重：0.7*结构 + 0.3*可读（结构错了的图没有任何可取之处）。
 */
public final class FidelityScorer {

    /** 结构分权重 */
    private static final double W_STRUCTURE = 0.7;
    /** 可读性分权重 */
    private static final double W_READABILITY = 0.3;

    private final SemanticValidator semantic = new SemanticValidator();
    private final GeometricCritic geometric = new GeometricCritic();
    private final DiagramScorer scorer = new DiagramScorer();

    /**
     * 给一张布局后的场景打保真分。
     *
     * @param sceneJson 场景 JSON（信封或裸数组均可）
     * @param ref       参考结构；null = 无参考（总分封顶、置信度 LOW）
     */
    public FidelityReport score(String sceneJson, ReferenceSpec ref) {
        SemanticReport sem = ref == null
                ? SemanticReport.empty(null)
                : semantic.checkAgainstReference(sceneJson, ref);
        double coverage = semantic.shapeCoverage(sceneJson);
        List<io.github.nihaoljx.flowchart.critique.CritiqueIssue> issues =
                geometric.critique(
                        SemanticValidator.parseElements(sceneJson), null);
        double readability = scorer.readability(issues);
        double structural = scorer.structureOf(sem, coverage);
        boolean verifiable = ref != null
                && (sem.totalNodes() > 0 || sem.totalEdges() > 0);
        double overall = W_STRUCTURE * structural + W_READABILITY * readability;
        if (!verifiable) {
            overall = Math.min(overall, DiagramScorer.NO_REF_CAP);
        }
        return new FidelityReport(structural, readability,
                Math.max(0.0, Math.min(1.0, overall)),
                verifiable ? FidelityReport.Confidence.HIGH
                        : FidelityReport.Confidence.LOW);
    }
}
