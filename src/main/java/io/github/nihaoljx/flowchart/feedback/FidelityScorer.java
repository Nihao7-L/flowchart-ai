package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.critique.GeometricCritic;
import io.github.nihaoljx.flowchart.graph.validate.ReferenceSpec;
import io.github.nihaoljx.flowchart.graph.validate.RequestIntent;
import io.github.nihaoljx.flowchart.graph.validate.SemanticReport;
import io.github.nihaoljx.flowchart.graph.validate.SemanticValidator;

import java.util.List;
import java.util.Map;

/**
 * 保真度量器（D5）：一张生成图离「对」有多远。
 *
 * <p>度量哲学（2026-10-16 事故后定稿）：
 *
 * <ol>
 *   <li><b>先判形态，再判质量。</b>需求是「画一张图」还是「画一个形象」，
 *       决定了输出该长什么样。需求要一只猫、交出零件关系图，这是
 *       {@link FidelityReport.Verdict#FORM_MISMATCH}（0 分），
 *       后面再干净也没有分可给 —— 形态不对的图，质量无从谈起。</li>
 *   <li><b>没有参考就不给分。</b>语义保真必须对着一个标准答案才能机判。
 *       没有参考就老实报 {@link FidelityReport.Verdict#UNVERIFIABLE}
 *       （{@code fidelity = null}），而不是拿「形状覆盖率」顶上去 ——
 *       旧版就是这么给猫打出 0.6 的。</li>
 *   <li><b>如实拒绝 ≠ 画得差。</b>输出带【AI 备注】说明「我是图解助手、
 *       不画写实图像」时，结论是 {@link FidelityReport.Verdict#REFUSED}
 *       （不给分）：这不是一次失败的生成，是用可信的方式兑现了能力边界。
 *       把它与「交假图冒充」分开记，飞轮选优才不会去惩罚诚实。</li>
 *   <li><b>像素只是辅助。</b>本类不做像素比对；可读性用确定性几何批判
 *       近似，它衡量「整不整齐」，永远不被说成「像不像」。</li>
 * </ol>
 *
 * <p>权重：{@code 0.7*结构 + 0.3*可读}（见 {@link DiagramScorer}）。
 */
public final class FidelityScorer {

    private final SemanticValidator semantic = new SemanticValidator();
    private final GeometricCritic geometric = new GeometricCritic();
    private final DiagramScorer scorer = new DiagramScorer();

    /**
     * 给一张布局后的场景打保真分（无需求文本的旧入口）。
     *
     * <p>没有需求文本 ⇒ 形态判定无从谈起，等价于 {@link RequestIntent#UNKNOWN}
     * （不拦、只按参考结构判）。
     *
     * @param sceneJson 场景 JSON（信封或裸数组均可）
     * @param ref       参考结构；null = 无参考（不给分）
     */
    public FidelityReport score(String sceneJson, ReferenceSpec ref) {
        return score(null, sceneJson, ref);
    }

    /**
     * 全参入口：需求 + 场景 + 参考结构。
     *
     * @param request   用户需求原文；null = 未知（跳过形态判定）
     * @param sceneJson 场景 JSON（信封或裸数组均可）
     * @param ref       参考结构（必含节点/连线）；null = 无参考
     * @return 报告；{@code fidelity} 为 null 表示本次不给分
     */
    public FidelityReport score(String request, String sceneJson,
                                ReferenceSpec ref) {
        List<Map<String, Object>> elements =
                SemanticValidator.parseElements(sceneJson);
        double readability =
                scorer.readability(geometric.critique(elements, null));

        // 1) 形态判定先行：需求不属于图表类 ⇒ 图表结构不可能满足它
        if (RequestIntent.classify(request).isNonDiagram()) {
            boolean honest =
                    SemanticValidator.declaresLimitation(sceneJson);
            return honest
                    ? new FidelityReport(
                            FidelityReport.Verdict.REFUSED, null,
                            readability,
                            "输出是能力边界说明（【AI 备注】如实拒绝）："
                                    + "这不是画得差，是系统没有冒充；"
                                    + "保真分不适用")
                    : new FidelityReport(
                            FidelityReport.Verdict.FORM_MISMATCH, 0.0,
                            readability,
                            "需求属于绘画/构图类（非图表），输出却是"
                                    + "节点连线图表结构且未声明能力边界"
                                    + " ⇒ 形态不符，0 分（内部再自洽也不加分）");
        }

        // 2) 无参考结构 ⇒ 语义不可机判，不给分（0 分与不给分是两件事）
        if (isEmptyReference(ref)) {
            return new FidelityReport(
                    FidelityReport.Verdict.UNVERIFIABLE, null,
                    readability,
                    "无参考结构 ⇒ 语义保真不可机判，本次不给分。"
                            + "（历史错误：此处曾用「形状覆盖率」顶替保真分"
                            + "并封顶 0.6，把一张零件关系图判成 0.6 分）");
        }

        // 3) 有参考 ⇒ 节点/连线召回才是硬指标
        SemanticReport sem =
                semantic.checkAgainstReference(sceneJson, ref);
        double structure = scorer.structureOf(sem);
        double overall = scorer.combine(structure, readability);
        return new FidelityReport(
                FidelityReport.Verdict.VERIFIED, overall, readability,
                String.format(
                        "参考结构召回：节点 %d/%d、连线 %d/%d"
                                + " ⇒ 结构分 %.2f；几何可读性 %.2f",
                        sem.foundNodes(), sem.totalNodes(),
                        sem.foundEdges(), sem.totalEdges(),
                        structure, readability));
    }

    private static boolean isEmptyReference(ReferenceSpec ref) {
        return ref == null
                || (ref.requiredNodes().isEmpty()
                        && ref.requiredEdges().isEmpty());
    }
}
