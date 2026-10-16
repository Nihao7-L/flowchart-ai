package io.github.nihaoljx.flowchart.critique;

import java.util.List;

/**
 * 一次视觉自纠的汇总报告。
 *
 * @param issues   所有批判器汇总的结构化问题
 * @param guidance 可直接回喂给 LLM 的引导文本（点名元素 + spec 级改法）
 */
public record CritiqueReport(List<CritiqueIssue> issues, String guidance) {

    /** 是否还需要再来一轮修正 */
    public boolean needsFix() {
        return !issues.isEmpty();
    }

    public static CritiqueReport empty() {
        return new CritiqueReport(List.of(), "");
    }
}
