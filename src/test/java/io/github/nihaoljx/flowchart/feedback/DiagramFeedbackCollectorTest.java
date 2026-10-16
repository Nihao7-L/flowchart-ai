package io.github.nihaoljx.flowchart.feedback;

import io.github.nihaoljx.flowchart.critique.CritiqueIssue;
import io.github.nihaoljx.flowchart.graph.validate.SemanticReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D4 数据飞轮基础单测：打分数学 + 收集器落盘/读取/bestOf/安全性。
 *
 * <p>2026-10-16 重写：DiagramScorer 不再有「无参考封顶 0.6」路径，
 * 也不再把"形状覆盖率"当结构分。结构分 = 必含节点/连线召回均值，
 * 且空参考（两侧要求数都为 0）显式判 0 —— 这正是旧版给猫打 0.6 的坑。
 */
class DiagramFeedbackCollectorTest {

    @TempDir
    Path tempDir;

    private final DiagramScorer scorer = new DiagramScorer();

    // ---- DiagramScorer ----

    @Test
    @DisplayName("打分：全召回 + 零几何问题 = 满分（有参考）")
    void perfectScore() {
        SemanticReport perfect = new SemanticReport(List.of(), 2, 2, 1, 1);
        double s = scorer.combine(scorer.structureOf(perfect),
                scorer.readability(List.of()));
        assertEquals(1.0, s, 1e-9);
    }

    @Test
    @DisplayName("打分：结构分权重 0.7、每条几何 issue 扣 0.15*0.3")
    void weightedScore() {
        SemanticReport half = new SemanticReport(List.of(), 1, 2, 1, 2);
        // nodeRecall=0.5, edgeRecall=0.5 ⇒ 结构分 (0.5+0.5)/2 = 0.5
        double structure = scorer.structureOf(half);
        assertEquals(0.5, structure, 1e-9);
        double clean = scorer.combine(structure, scorer.readability(List.of()));
        assertEquals(0.7 * structure + 0.3 * 1.0, clean, 1e-9);
        double withIssue = scorer.combine(structure, scorer.readability(List.of(
                CritiqueIssue.of("a", CritiqueIssue.Category.OVERLAP, "m", "s", 2))));
        assertEquals(0.7 * structure + 0.3 * 0.85, withIssue, 1e-9);
    }

    @Test
    @DisplayName("【反回归】空参考（无要求）结构分必须 0，绝不白送满分")
    void emptyReferenceStructureZero() {
        // 旧版：empty(null) 的 recall 按「不惩罚」定义恒为 1 ⇒ 结构分 1.0
        // 这正是 cat 事故里"零参考被封顶 0.6"的上游错误
        assertEquals(0.0, scorer.structureOf(SemanticReport.empty(null)), 1e-9);
        assertEquals(0.0,
                scorer.structureOf(new SemanticReport(List.of(), 0, 0, 0, 0)), 1e-9);
    }

    @Test
    @DisplayName("打分：部分召回 ⇒ 结构分按节点/连线召回均值下修")
    void partialRecallScoresLow() {
        // 要求 2 节点命中 1、1 条边命中 0 ⇒ 节点召回 0.5、连线召回 0
        SemanticReport partial = new SemanticReport(List.of(), 1, 2, 0, 1);
        double structure = scorer.structureOf(partial);
        assertEquals((0.5 + 0.0) / 2.0, structure, 1e-9);
        assertTrue(structure < 0.5);
    }

    // ---- DiagramFeedbackCollector ----

    @Test
    @DisplayName("收集器：未配置目录 = 关闭，record 返回 null 且零 I/O")
    void disabledByDefault() {
        var collector = new DiagramFeedbackCollector(" ");
        assertFalse(collector.enabled());
        assertNull(collector.record("req", "{\"elements\":[]}",
                new FidelityReport(FidelityReport.Verdict.UNVERIFIABLE, null, 1.0, "x"),
                List.of()));
    }

    @Test
    @DisplayName("收集器：写入 JSONL 且能读回、bestOf 按分排序")
    void recordAndBestOf() throws IOException {
        var collector = new DiagramFeedbackCollector(tempDir.toString());
        Path f1 = collector.record("画登录流程",
                "{\"elements\":[{\"id\":\"a\"}]}",
                new FidelityReport(FidelityReport.Verdict.VERIFIED, 0.72, 1.0, "r1"),
                List.of("重叠 a-b", "溢出 c"));
        assertNotNull(f1);
        assertTrue(Files.exists(f1));
        collector.record("画部署图", "{\"elements\":[{\"id\":\"b\"}]}",
                new FidelityReport(FidelityReport.Verdict.VERIFIED, 0.95, 1.0, "r2"),
                List.of());
        collector.record("画时序", "{\"elements\":[{\"id\":\"c\"}]}",
                new FidelityReport(FidelityReport.Verdict.VERIFIED, 0.31, 1.0, "r3"),
                List.of());

        List<DiagramFeedbackCollector.ScoredScene> all = collector.readAll();
        assertEquals(3, all.size());
        List<DiagramFeedbackCollector.ScoredScene> top = collector.bestOf(2);
        assertEquals(2, top.size());
        assertEquals(0.95, top.get(0).score(), 1e-9);
        assertEquals(0.72, top.get(1).score(), 1e-9);
        assertTrue(top.get(1).scene().contains("\"a\""));
    }

    @Test
    @DisplayName("收集器：目录不可写时安全返回 null（绝不打断生成）")
    void unwritableDirSafe() {
        Path asFile = tempDir.resolve("not-a-dir");
        try {
            Files.writeString(asFile, "x");
        } catch (IOException ignored) {
            // 忽略
        }
        var collector = new DiagramFeedbackCollector(asFile.toString());
        assertTrue(collector.enabled());
        assertNull(collector.record("req", "{\"elements\":[]}",
                new FidelityReport(FidelityReport.Verdict.VERIFIED, 0.5, 1.0, "x"),
                List.of()));
    }

    @Test
    @DisplayName("收集器：null/空场景直接拒绝；不给分的样本不参与 bestOf")
    void nullSceneRejectedAndUnscoredExcluded() throws IOException {
        var collector = new DiagramFeedbackCollector(tempDir.toString());
        assertNull(collector.record("req", null,
                new FidelityReport(FidelityReport.Verdict.UNVERIFIABLE, null, 1.0, "x"),
                List.of()));
        assertNull(collector.record("req", "  ",
                new FidelityReport(FidelityReport.Verdict.REFUSED, null, 1.0, "x"),
                List.of()));
        assertEquals(0, collector.readAll().size());

        // 一条 VERIFIED 0.9 + 一条 FORM_MISMATCH 0.0（有分）+ 一条 UNVERIFIABLE（不给分）
        collector.record("画流程图", "{\"elements\":[{\"id\":\"a\"}]}",
                new FidelityReport(FidelityReport.Verdict.VERIFIED, 0.9, 1.0, "ok"),
                List.of());
        collector.record("画猫", "{\"elements\":[{\"id\":\"b\"}]}",
                new FidelityReport(FidelityReport.Verdict.FORM_MISMATCH, 0.0, 1.0,
                        "绘画需求交了零件图"),
                List.of());
        collector.record("画风景", "{\"elements\":[{\"id\":\"c\"}]}",
                new FidelityReport(FidelityReport.Verdict.UNVERIFIABLE, null, 1.0,
                        "无参考不可判定"),
                List.of());

        List<DiagramFeedbackCollector.ScoredScene> all = collector.readAll();
        assertEquals(3, all.size(), "三类样本都落盘");
        List<DiagramFeedbackCollector.ScoredScene> top = collector.bestOf(10);
        assertEquals(2, top.size(), "不给分的 UNVERIFIABLE 不参与排序");
        assertTrue(top.stream()
                .allMatch(DiagramFeedbackCollector.ScoredScene::scored));
    }
}
