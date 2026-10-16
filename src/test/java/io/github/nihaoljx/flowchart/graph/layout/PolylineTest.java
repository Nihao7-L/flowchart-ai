package io.github.nihaoljx.flowchart.graph.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Polyline.orthogonalizeAndSimplify 的单元测试。
 *
 * 锁死两条观感铁律（对应「线不乱 / 最少拐弯」）：
 * - 输出必须全正交（每段横或竖，无斜线段）；
 * - 共线冗余弯必须被合并（视觉路线不变，拐点最少）。
 */
class PolylineTest {

    private static List<double[]> pts(double[]... ps) {
        return List.of(ps);
    }

    /** 断言整条折线全正交：相邻两点必有一轴坐标相等 */
    private void assertOrthogonal(List<double[]> out) {
        for (int i = 1; i < out.size(); i++) {
            double dx = Math.abs(out.get(i)[0] - out.get(i - 1)[0]);
            double dy = Math.abs(out.get(i)[1] - out.get(i - 1)[1]);
            boolean h = dy < 1e-9;
            boolean v = dx < 1e-9;
            assertTrue(h || v,
                    "段 " + (i - 1) + " 非正交: " + out.get(i - 1)
                            + " -> " + out.get(i));
        }
    }

    @Test
    @DisplayName("斜线段被拆成 L 形直角弯（先横后竖，长边走直线）")
    void diagonalBecomesOrthogonalL() {
        // (0,0) -> (100,80) 斜线：|dx|>=|dy| 先横后竖 → (100,0) 拐点
        List<double[]> in = pts(
                new double[] { 0, 0 },
                new double[] { 100, 80 });
        List<double[]> out = Polyline.orthogonalizeAndSimplify(in);
        assertOrthogonal(out);
        assertEquals(3, out.size());
        assertEquals(100.0, out.get(1)[0], 1e-9);
        assertEquals(0.0, out.get(1)[1], 1e-9);
    }

    @Test
    @DisplayName("纵深的斜线先竖后横")
    void tallDiagonalVerticalFirst() {
        // (0,0) -> (30,-430) 斜线：|dy|>|dx| 先竖后横 → (0,-430) 拐点
        List<double[]> in = pts(
                new double[] { 0, 0 },
                new double[] { 30, -430 });
        List<double[]> out = Polyline.orthogonalizeAndSimplify(in);
        assertOrthogonal(out);
        assertEquals(3, out.size());
        assertEquals(0.0, out.get(1)[0], 1e-9);
        assertEquals(-430.0, out.get(1)[1], 1e-9);
    }

    @Test
    @DisplayName("已正交的折线不被破坏，且首末点保持不动")
    void orthogonalUnchanged() {
        List<double[]> in = pts(
                new double[] { 0, 0 },
                new double[] { 30, 0 },
                new double[] { 30, -430 },
                new double[] { -260, -430 });
        List<double[]> out = Polyline.orthogonalizeAndSimplify(in);
        assertOrthogonal(out);
        assertEquals(in.size(), out.size());
        assertEquals(in.get(0)[0], out.get(0)[0], 1e-9);
        assertEquals(in.get(3)[0], out.get(3)[0], 1e-9);
    }

    @Test
    @DisplayName("共线冗余弯被合并（视觉路线不变、拐点更少）")
    void colinearBendMerged() {
        // 全在同一 y 上的三段横：中间弯冗余，应被删掉
        List<double[]> in = pts(
                new double[] { 0, 0 },
                new double[] { 50, 0 },
                new double[] { 100, 0 });
        List<double[]> out = Polyline.orthogonalizeAndSimplify(in);
        assertOrthogonal(out);
        assertEquals(2, out.size()); // 去掉中点
    }

    @Test
    @DisplayName("真实怪物箭头：钩子+斜线 → 全正交、拐弯收敛")
    void monsterArrowCleaned() {
        // urun_user_p2 里的 f1a9: [[0,0],[30,0],[30,-430],[-260,0]]
        // 末段 (30,-430)->(-260,0) 是斜线，必须被拆直
        List<double[]> in = pts(
                new double[] { 0, 0 },
                new double[] { 30, 0 },
                new double[] { 30, -430 },
                new double[] { -260, 0 });
        List<double[]> out = Polyline.orthogonalizeAndSimplify(in);
        assertOrthogonal(out);
    }

    @Test
    @DisplayName("直线路由补的中点被化简掉（不强行留冗余弯）")
    void straightLineMidpointDropped() {
        // 水平直线：(0,0)->(50,0)->(100,0)
        List<double[]> in = pts(
                new double[] { 0, 0 },
                new double[] { 50, 0 },
                new double[] { 100, 0 });
        List<double[]> out = Polyline.orthogonalizeAndSimplify(in);
        assertEquals(2, out.size());
    }

    @Test
    @DisplayName("点数 < 2 原样返回，不抛异常")
    void tooFewPointsUnchanged() {
        List<double[]> in = pts(new double[] { 0, 0 });
        List<double[]> out = Polyline.orthogonalizeAndSimplify(in);
        assertEquals(1, out.size());
    }

    /**
     * 真实回归：verify_exam_sm 里 5 根「首尾带斜段」的箭头原始 points。
     * 这些斜段出现在首段（start→首弯）与末段（末弯→end），
     * 是 ELK 把「边扎到图形边界的附着点」直接写出、未与中段轴对齐所致。
     * 必须被拆成直角弯、整条全正交。
     */
    @Test
    @DisplayName("真实 verify_exam_sm 5 根首尾斜线箭头 → 全正交")
    void realExamSmFiveDiagonalArrows() {
        double[][][] cases = {
            { { 0, 0 }, { 20, 10 }, { 280, 10 }, { 280, -290 },
                    { 300, -290 }, { 320, -300 } },
            { { 0, 0 }, { 20, 10 }, { 180, 10 }, { 180, -110 },
                    { 200, -110 }, { 220, -120 } },
            { { 0, 0 }, { -20, 10 }, { -200, 10 }, { -200, 190 },
                    { -620, 190 }, { -620, 510 }, { -780, 510 },
                    { -800, 500 } },
            { { 0, 0 }, { -20, 2 }, { -20, 442 }, { -1000, 442 },
                    { -1000, 502 }, { -1020, 502 }, { -1040, 492 } },
            { { 0, 0 }, { 20, 10 }, { 800, 10 }, { 800, -590 },
                    { 820, -590 }, { 820, -610 }, { 840, -610 },
                    { 860, -620 } },
        };
        for (double[][] c : cases) {
            List<double[]> in = pts(c);
            List<double[]> out =
                    Polyline.orthogonalizeAndSimplify(in);
            assertOrthogonal(out);
        }
    }
}
