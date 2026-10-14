package io.github.nihaoljx.flowchart.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.nihaoljx.flowchart.graph.layout.LayoutSpec;

/**
 * 布局规格测试（v2-38 起的规格层，2026-09-18 加"渲染尺寸"一节）
 *
 * <p>守的是与<b>渲染端</b>的约定：{@link LayoutSpec#nodeSize} 必须给出
 * "Excalidraw 最终会画多大"。数字不是推出来的，是浏览器实测抓的
 * （{@code workbuddyFlow/tools/e2e/probe_frontend_geom.py}）：
 * <ul>
 *   <li>菱形 120×60 + 四个字一行的标签 → 渲染成 <b>220</b> 高；</li>
 *   <li>菱形 100×80 + 三行 → 渲染成 <b>170</b> 高；</li>
 *   <li>矩形 120×60 + 拉丁长标签 → 渲染成 <b>85</b> 高。</li>
 * </ul>
 *
 * <p>之所以要盯住这些数字：布局按它们算层间距，渲染端若临时把容器撑高，
 * 排好的图当场重叠（2026-09-18 真实 LLM 电池题 2 实测两处重叠）。
 *
 * <p>中文标签用 Unicode 转义写（{@code \u5141} 等），本文件保持纯 ASCII ——
 * 编辑器改写中文字节的坑踩过一次就够了。
 */
class LayoutSpecTest {

    /** 允许缺货预订？（7 个汉字 = 140px） */
    private static final String CJK_LONG =
            "\u5141\u8bb8\u7f3a\u8d27\u9884\u8ba2\uff1f";

    /** 库存充足？（5 个汉字 = 100px） */
    private static final String CJK_SHORT =
            "\u5e93\u5b58\u5145\u8db3\uff1f";

    @Test
    @DisplayName("短标签：矩形保持下限尺寸，不无谓变大")
    void shortLabelKeepsFloorSize() {
        double[] size = LayoutSpec.nodeSize(
                "rectangle", "start", 160);
        assertEquals(160, size[0], 1e-9);
        assertEquals(60, size[1], 1e-9);
    }

    @Test
    @DisplayName("菱形把文字装不下时撑两倍高（浏览器实测 220）")
    void diamondGrowsToTwiceTheWrappedText() {
        // 120 宽的菱形：可用文字宽 = round(60) - 10 = 50 → 每行 2 个汉字
        // → 4 行 = 100 高 → 2 × (100 + 10) = 220
        double[] size = LayoutSpec.nodeSize(
                "diamond", CJK_LONG, 120);
        assertEquals(220, size[1], 1e-9,
                "菱形高度应等于浏览器实测值（渲染端撑大公式）");
    }

    @Test
    @DisplayName("菱形至少留一行文字的高度（60 高的菱形装不下 25px 文字）")
    void diamondNeverShorterThanOneLine() {
        double[] size = LayoutSpec.nodeSize(
                "diamond", CJK_SHORT, 160);
        assertTrue(size[1] >= 70,
                "菱形高度应 ≥ 70，实际 " + size[1]);
    }

    @Test
    @DisplayName("尺寸一律向上取整到网格：宁大勿小")
    void sizesAreCeiledToGrid() {
        double[] size = LayoutSpec.nodeSize(
                "diamond", CJK_SHORT, 100);
        // 公式给 170（= 2 × (75 + 10)），比实测的 170 只多不少
        assertTrue(size[1] >= 170,
                "高度不得小于公式值，实际 " + size[1]);
        assertEquals(0, size[1] % LayoutSpec.GRID, 1e-9);
        assertEquals(0, size[0] % LayoutSpec.GRID, 1e-9);
    }

    @Test
    @DisplayName("可用宽度：椭圆/菱形按内接矩形收窄，其余只减留白")
    void maxTextWidthFollowsShape() {
        assertEquals(150, LayoutSpec.maxTextWidth("rectangle", 160), 1e-9);
        assertEquals(Math.round(80 * Math.sqrt(2)) - 10,
                LayoutSpec.maxTextWidth("ellipse", 160), 1e-9);
        assertEquals(70, LayoutSpec.maxTextWidth("diamond", 160), 1e-9);
    }

    @Test
    @DisplayName("折行：显式换行符必须保留")
    void wrapKeepsExplicitNewlines() {
        List<String> lines = LayoutSpec.wrap("a\nbb\nccc", 1000);
        assertEquals(3, lines.size());
        assertEquals("bb", lines.get(1));
    }

    @Test
    @DisplayName("折行对拉丁偏保守（估计偏窄 → 行数偏多 → 只高不矮）")
    void wrapIsConservativeForLatin() {
        // 浏览器把这段文字折成 3 行（实测 85 高）；估计值必须 ≥ 3 行
        List<String> lines = LayoutSpec.wrap(
                "a much longer latin label here", 110);
        assertTrue(lines.size() >= 3,
                "行数应不少于实测的 3，实际 " + lines.size());
    }

    @Test
    @DisplayName("空标签：仍给出下限尺寸，不出现 0")
    void emptyLabelStillHasSize() {
        double[] size = LayoutSpec.nodeSize("rectangle", "", 160);
        assertEquals(160, size[0], 1e-9);
        assertEquals(60, size[1], 1e-9);
        assertEquals(1, LayoutSpec.wrap(null, 100).size());
    }
}
