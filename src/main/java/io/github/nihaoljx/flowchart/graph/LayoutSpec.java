package io.github.nihaoljx.flowchart.graph;

/**
 * 布局规格（v2-38）
 *
 * <p>引擎无关的那一半：节点多大、层间距多宽、坐标吸附到哪个网格。
 * 这些量由<b>渲染端</b>决定（Excalidraw 的字号与网格），跟"用哪种
 * 分层算法"没关系 —— 所以抽出来单放，让两个 {@link LayoutEngine}
 * 实现在同一套输入规格下比对。否则比出来的是渲染参数，不是算法。
 *
 * <p>数值与 {@link SceneLayout} 保持一致，来源是 2026-09-18 的浏览器
 * 实测：中文正好 20px/字（= fontSize 20 的 1 em）、拉丁 10px/字。
 */
final class LayoutSpec {

    /** 坐标吸附网格（Excalidraw 默认） */
    static final double GRID = 20.0;

    /** 节点统一高度 */
    static final double NODE_HEIGHT = 60.0;

    /** 节点宽度下限与上限 */
    static final double MIN_NODE_WIDTH = 160.0;
    static final double MAX_NODE_WIDTH = 360.0;

    /** 节点文字两侧留白 */
    static final double NODE_LABEL_PADDING = 24.0;

    /** 单字符宽度估算：中文 1 em，拉丁半个 em */
    static final double CJK_CHAR_WIDTH = 20.0;
    static final double LATIN_CHAR_WIDTH = 10.0;

    /** 主方向层间距下限与同层兄弟间距 */
    static final double LAYER_GAP = 100.0;
    static final double SIBLING_GAP = 80.0;

    /** 边标签两侧留白 */
    static final double EDGE_LABEL_PADDING = 16.0;

    /** 长宽比可接受区间（超区间才考虑换主方向） */
    static final double MIN_ASPECT = 0.5;
    static final double MAX_ASPECT = 2.0;

    private LayoutSpec() {
    }

    /**
     * 估算一段文字的像素宽度。
     *
     * @param label 文字，可为 null
     * @return 估算宽度（px），宁宽勿窄
     */
    static double labelWidth(String label) {
        if (label == null || label.isEmpty()) {
            return 0;
        }
        double width = 0;
        for (int i = 0; i < label.length(); i++) {
            width += label.charAt(i) >= 0x2E80
                    ? CJK_CHAR_WIDTH : LATIN_CHAR_WIDTH;
        }
        return width;
    }

    /**
     * 统一节点宽度：按"最长的标签装得下"取一个值。
     *
     * @param labels 参与摆放的节点标签
     * @return 统一宽度（px）
     */
    static double nodeWidth(Iterable<String> labels) {
        double widest = MIN_NODE_WIDTH;
        for (String label : labels) {
            double needed = labelWidth(label) + NODE_LABEL_PADDING;
            widest = Math.max(widest,
                    Math.min(MAX_NODE_WIDTH, needed));
        }
        return widest;
    }

    /**
     * 主方向层间距：至少要装得下最长的边标签。
     *
     * <p>边在主方向上的可见长度就等于层间距，装不下标签就会被文字
     * 盖满 —— 这是"线上加了文字就看不到线"的直接对策。
     *
     * <p>返回值含两项余量：标签两侧留白 + 一个 {@link #GRID} 的吸附
     * 容差（层间距由"绝对坐标取整到网格"间接决定，相邻两层各带
     * ±10 误差），并向上取整到网格整数倍以保证确定性。
     *
     * @param edgeLabels 与可移动节点相连的边标签
     * @return 层间距（px），已是网格整数倍
     */
    static double mainGapFor(Iterable<String> edgeLabels) {
        double widest = 0;
        for (String label : edgeLabels) {
            widest = Math.max(widest, labelWidth(label));
        }
        double needed = widest + 2 * EDGE_LABEL_PADDING;
        double raw = Math.max(LAYER_GAP, needed + GRID);
        return Math.ceil(raw / GRID) * GRID;
    }

    /**
     * 吸附到网格。
     *
     * @param value 原始坐标
     * @return 最近的网格整数倍
     */
    static double snap(double value) {
        return Math.round(value / GRID) * GRID;
    }

    /**
     * 长宽比是否超出可接受区间。
     *
     * @param width  包围盒宽
     * @param height 包围盒高
     * @return 超出区间返回 true
     */
    static boolean aspectOutOfRange(double width, double height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        double aspect = width / height;
        return aspect < MIN_ASPECT || aspect > MAX_ASPECT;
    }

    /**
     * 长宽比偏离理想区间的程度（对数距离）。
     *
     * <p>用来比较"两个方向哪个更糟"：仅当另一个方向确实更接近
     * 理想区间时才值得换向 —— 否则会把链状图翻得更细长。
     *
     * @param width  包围盒宽
     * @param height 包围盒高
     * @return 偏离量，越小越好
     */
    static double aspectPenalty(double width, double height) {
        if (width <= 0 || height <= 0) {
            return Double.MAX_VALUE;
        }
        double aspect = width / height;
        if (aspect >= MIN_ASPECT && aspect <= MAX_ASPECT) {
            return 0;
        }
        double target = aspect < MIN_ASPECT
                ? MIN_ASPECT : MAX_ASPECT;
        return Math.abs(Math.log(aspect / target));
    }
}
