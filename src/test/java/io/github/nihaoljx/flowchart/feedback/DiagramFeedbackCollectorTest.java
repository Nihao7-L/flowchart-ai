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
 */
class DiagramFeedbackCollectorTest {

    @TempDir
    Path tempDir;

    private final DiagramScorer scorer = new DiagramScorer();

    // ---- DiagramScorer ----

    @Test
    @DisplayName("打分：全召回 + 零几何问题 = 满分（有参考）")
    void perfectScore() {
        SemanticReport perfect = new SemanticReport(java.util.List.of(), 2, 2, 1, 1);
        double s = scorer.score(perfect, 1.0, List.of());
        assertEquals(1.0, s, 1e-9);
    }

    @Test
    @DisplayName("打分：结构分权重 0.6、每条几何 issue 扣 0.15*0.4")
    void weightedScore() {
        SemanticReport half = new SemanticReport(List.of(), 1, 2, 1, 2);
        double structure = 0.5;
        double s = scorer.score(half, 1.0, List.of());
        assertEquals(0.6 * structure + 0.4 * 1.0, s, 1e-9);
        double withIssue = scorer.score(half, 1.0, List.of(
                CritiqueIssue.of("a", CritiqueIssue.Category.OVERLAP, "m", "s", 2)));
        assertEquals(0.6 * structure + 0.4 * 0.85, withIssue, 1e-9);
    }

    @Test
    @DisplayName("打分修正：无参考时用覆盖率作结构分，且总分封顶 0.6")
    void noReferenceUsesCoverageAndCaps() {
        // 覆盖率 1 + 零几何问题：封顶 0.6（语义不可验证不配满分）
        assertEquals(DiagramScorer.NO_REF_CAP,
                scorer.score(SemanticReport.empty(null), 1.0, List.of()), 1e-9);
        // 猫式场景：半数形状零连线 → 覆盖率 0.5 → 裸分 0.7 被封顶压到 0.6
        double catLike = scorer.score(SemanticReport.empty(null), 0.5, List.of());
        assertEquals(DiagramScorer.NO_REF_CAP, catLike, 1e-9);
    }

    @Test
    @DisplayName("打分修正：有参考但形状半数孤立 → 覆盖率下修结构分")
    void referenceButDisconnectedShapes() {
        SemanticReport perfectRecall = new SemanticReport(List.of(), 2, 2, 1, 1);
        // 召回 1.0 但覆盖率 0.4 → 结构分取 min(1.0, 0.4) = 0.4
        double s = scorer.score(perfectRecall, 0.4, List.of());
        assertEquals(0.6 * 0.4 + 0.4 * 1.0, s, 1e-9);
        assertTrue(s < 1.0);
    }

    // ---- DiagramFeedbackCollector ----

    @Test
    @DisplayName("收集器：未配置目录 = 关闭，record 返回 null 且零 I/O")
    void disabledByDefault() {
        var collector = new DiagramFeedbackCollector(" ");
        assertFalse(collector.enabled());
        assertNull(collector.record("req", "{\"elements\":[]}", 0.9, List.of()));
    }

    @Test
    @DisplayName("收集器：写入 JSONL 且能读回、bestOf 按分排序")
    void recordAndBestOf() throws IOException {
        var collector = new DiagramFeedbackCollector(tempDir.toString());
        Path f1 = collector.record("画登录流程",
                "{\"elements\":[{\"id\":\"a\"}]}", 0.72,
                List.of("重叠 a-b", "溢出 c"));
        assertNotNull(f1);
        assertTrue(Files.exists(f1));
        collector.record("画部署图", "{\"elements\":[{\"id\":\"b\"}]}", 0.95, List.of());
        collector.record("画时序", "{\"elements\":[{\"id\":\"c\"}]}", 0.31, List.of());

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
        // 把「目录」指向一个普通文件，createDirectories 必然失败
        Path asFile = tempDir.resolve("not-a-dir");
        try {
            Files.writeString(asFile, "x");
        } catch (IOException ignored) {
            // 忽略
        }
        var collector = new DiagramFeedbackCollector(asFile.toString());
        assertTrue(collector.enabled());
        assertNull(collector.record("req", "{\"elements\":[]}", 0.5, List.of()));
    }

    @Test
    @DisplayName("收集器：null/空场景直接拒绝记录")
    void nullSceneRejected() throws IOException {
        var collector = new DiagramFeedbackCollector(tempDir.toString());
        assertNull(collector.record("req", null, 0.5, List.of()));
        assertNull(collector.record("req", "  ", 0.5, List.of()));
        assertEquals(0, collector.readAll().size());
    }
}
