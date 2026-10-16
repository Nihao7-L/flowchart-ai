package io.github.nihaoljx.flowchart.feedback;

/**
 * 保真度量报告（D5）。
 *
 * <p>2026-10-16 重写。旧版是 {@code (structural, readability, overall, confidence)}
 * 四个 double —— 它的问题不在算错，而在**在无法判定的场合也硬给一个数字**：
 * 「画一只猫」交出零件关系图，报告写 0.6 分、置信 LOW。用户当场否决：
 * 「这是一个猫吗，你就给 0.6，你简直是在乱打分」。
 *
 * <p>新版把「不可判定」变成一等结论：{@link #fidelity} 可以为 null
 * （{@code null} = 不给分，而不是 0 分）。**0 分与"不给分"是两件事**——
 * 前者是"我确定它不合格"，后者是"我没有依据判断"。把后者写成数字，
 * 就是用一个看起来很科学的数掩盖"其实没测"，这是度量里最坏的作弊。
 *
 * @param verdict    结论类别（决定 fidelity 是否适用），见 {@link Verdict}
 * @param fidelity   保真分 0..1；仅 {@code VERIFIED}（有参考可机判）
 *                   与 {@code FORM_MISMATCH}（形态不符，确定 0 分）有值，
 *                   其余为 null = 不给分
 * @param readability 可读性分 0..1（几何批判 issue 线性扣分）——
 *                   它衡量"画得整不整齐"，与"画对了没有"无关，任何
 *                   结论下都照给（诊断信息）
 * @param reason     人话解释：为什么是这个结论（点名到具体证据）
 */
public record FidelityReport(Verdict verdict, Double fidelity,
                             double readability, String reason) {

    /**
     * 结论类别。
     *
     * <p>刻意把「如实拒绝」({@link #REFUSED}) 与「交假图冒充」
     * ({@link #FORM_MISMATCH}) 分开：两者对参考图的保真都是零，
     * 但前者可信、后者是事故。数据飞轮选优时，只有后者该被当成坏样本。
     */
    public enum Verdict {

        /** 有参考结构，节点/连线召回可机判 ⇒ 分数有效 */
        VERIFIED,

        /** 无参考结构 ⇒ 语义不可验证，**不给分**（不是 0 分） */
        UNVERIFIABLE,

        /** 需求类别与输出形态不符且未声明边界（如"画猫"交了零件关系图）⇒ 0 分 */
        FORM_MISMATCH,

        /** 输出是「能力边界说明」（【AI 备注】如实拒绝）⇒ 保真不适用，不给分 */
        REFUSED
    }

    /** 是否有分数。false = 本次无法判定/不适用（不要把 null 当 0 用） */
    public boolean scored() {
        return fidelity != null;
    }

    /** 是否可信地表达了「这张图符合参考」（有参考 + 有分） */
    public boolean verifiable() {
        return verdict == Verdict.VERIFIED && scored();
    }

    /** 打印/日志用的一行结论：{@code VERIFIED 0.62} / {@code UNVERIFIABLE N/A} */
    public String display() {
        return verdict + " " + (scored()
                ? String.format("%.2f", fidelity) : "N/A");
    }
}
