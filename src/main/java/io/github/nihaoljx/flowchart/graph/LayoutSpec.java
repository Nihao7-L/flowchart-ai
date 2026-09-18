package io.github.nihaoljx.flowchart.graph;

import java.util.ArrayList;
import java.util.List;

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

    /** 主方向层间距下限与同层兄弟间距下限（实际值由 mainGapFor / siblingGapFor 按标签放宽） */
    static final double LAYER_GAP = 100.0;
    static final double SIBLING_GAP = 80.0;

    /** 分区排版：模块之间的通道宽度（比层间距更宽，跨模块边的标签落在这条通道里） */
    static final double MODULE_GAP = 200.0;

    /** 分区排版：frame 包围其成员时四周留白 */
    static final double MODULE_PADDING = 40.0;

    /** 分区排版：frame 标题占的高度（标题画在框上沿之上） */
    static final double FRAME_TITLE = 40.0;

    /**
     * 渲染端给"框内文字"留的四周留白。
     *
     * <p>数值抄自 Excalidraw 的 {@code BOUND_TEXT_PADDING}（constants.ts）——
     * 它同时参与"文字可用宽度"与"容器该多大"两个公式，抄错一个就全错。
     */
    static final double BOUND_TEXT_PADDING = 5.0;

    /**
     * 单行文字的渲染高度 = fontSize 20 × lineHeight 1.25。
     *
     * <p>浏览器实测（{@code probe_frontend_geom.py}）：所有标签元素都是 25px
     * 一行，与 fontSize 无关地恒定 —— 因为契约里字号只有一个值。
     */
    static final double LINE_HEIGHT = 25.0;

    /** 文字可用宽/高的形状修正所依赖的类型名 */
    static final String TYPE_ELLIPSE = "ellipse";
    static final String TYPE_DIAMOND = "diamond";

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
     * 同层兄弟间距：与 {@link #mainGapFor} 对称 —— 也要装得下最长的边标签。
     *
     * <p>同层相邻的两个节点之间，连线在主方向上没有长度（两个节点在同一
     * 条层线上），标签落在两节点边缘之间那段空隙的正中。空隙不够宽，
     * 标签就横跨到相邻节点上 —— 与层间距不够宽是同一类病，解法也必须
     * 一致，否则就是"一个动态放大、一个写死"，治了竖向漏了横向。
     *
     * <p>修的正是这个真缺陷（2026-09-18 实测发现）：`SIBLING_GAP` 原本
     * 写死 80，只够 4 个汉字（4×20），而"支付请求路由"6 字 = 120px，
     * 必然压到邻居身上。返回值含与 {@link #mainGapFor} 相同的两项余量
     * （标签两侧留白 + 一个网格容差），并向上取整到网格整数倍。
     *
     * @param edgeLabels 与可移动节点相连的边标签
     * @return 同层间距（px），已是网格整数倍
     */
    static double siblingGapFor(Iterable<String> edgeLabels) {
        double widest = 0;
        for (String label : edgeLabels) {
            widest = Math.max(widest, labelWidth(label));
        }
        double needed = widest + 2 * EDGE_LABEL_PADDING;
        double raw = Math.max(SIBLING_GAP, needed + GRID);
        return Math.ceil(raw / GRID) * GRID;
    }

    /**
     * 文字在某个形状里能用的最大宽度。
     *
     * <p>镜像 Excalidraw 的 {@code getBoundTextMaxWidth}：椭圆与菱形不是
     * 矩形，内接的最大矩形分别只有 {@code round(w/2*√2)} 与
     * {@code round(w/2)} 宽，其余形状就是"宽减两侧留白"。
     *
     * @param type  图形类型（rectangle / ellipse / diamond）
     * @param width 容器宽度
     * @return 文字可用宽度（px）
     */
    static double maxTextWidth(String type, double width) {
        if (TYPE_ELLIPSE.equals(type)) {
            return Math.round(width / 2 * Math.sqrt(2))
                    - BOUND_TEXT_PADDING * 2;
        }
        if (TYPE_DIAMOND.equals(type)) {
            return Math.round(width / 2) - BOUND_TEXT_PADDING * 2;
        }
        return width - BOUND_TEXT_PADDING * 2;
    }

    /**
     * 文字在某个形状里能用的最大高度（镜像
     * {@code getBoundTextMaxHeight}，与宽度版本同构）。
     *
     * @param type   图形类型
     * @param height 容器高度
     * @return 文字可用高度（px）
     */
    static double maxTextHeight(String type, double height) {
        if (TYPE_ELLIPSE.equals(type)) {
            return Math.round(height / 2 * Math.sqrt(2))
                    - BOUND_TEXT_PADDING * 2;
        }
        if (TYPE_DIAMOND.equals(type)) {
            return Math.round(height / 2) - BOUND_TEXT_PADDING * 2;
        }
        return height - BOUND_TEXT_PADDING * 2;
    }

    /**
     * 装下一段文字所需的容器尺寸（镜像
     * {@code computeContainerDimensionForBoundText}）。
     *
     * <p>这是"容器被文字撑大"的<b>唯一</b>公式：菱形一次撑两倍
     * （内接矩形只有一半宽），椭圆按 √2 反推，其余就是加两侧留白。
     *
     * @param dimension 文字的实际宽或高
     * @param type      图形类型
     * @return 容器应有的宽或高（px）
     */
    static double containerDimensionFor(
            double dimension, String type) {
        double text = Math.ceil(dimension);
        double padding = BOUND_TEXT_PADDING * 2;
        if (TYPE_ELLIPSE.equals(type)) {
            return Math.round((text + padding) / Math.sqrt(2) * 2);
        }
        if (TYPE_DIAMOND.equals(type)) {
            return 2 * (text + padding);
        }
        return text + padding;
    }

    /**
     * 按可用宽度折行（显式换行符优先，其次在空格处断，最后才断词）。
     *
     * <p>折行结果只用来<b>估高</b>，所以宁可断得碎一点：行数偏多只会让
     * 容器偏高，而偏高是安全的（渲染端只撑大、不缩小）；反过来偏矮就会
     * 让渲染端把容器撑高，布局算好的层间距当场失效。
     *
     * @param label    标签文字，可为 null
     * @param maxWidth 单行可用宽度（px）
     * @return 折好的行（至少一行）
     */
    static List<String> wrap(String label, double maxWidth) {
        List<String> lines = new ArrayList<>();
        if (label == null || label.isEmpty()) {
            lines.add("");
            return lines;
        }
        double limit = Math.max(CJK_CHAR_WIDTH, maxWidth);
        for (String paragraph : label.split("\n", -1)) {
            wrapParagraph(paragraph, limit, lines);
        }
        return lines;
    }

    private static void wrapParagraph(
            String text, double limit, List<String> lines) {
        StringBuilder line = new StringBuilder();
        double width = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            double charWidth = labelWidth(String.valueOf(ch));
            if (width + charWidth > limit && line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
                width = 0;
            }
            line.append(ch);
            width += charWidth;
        }
        lines.add(line.toString());
    }

    /**
     * 一个图形的渲染尺寸 = 渲染端最终会画多大。
     *
     * <p>为什么布局必须自己算这一步：Excalidraw 会在渲染时把"文字装不下"
     * 的容器<b>撑大</b>，而布局是按契约里的尺寸算层间距的 —— 两边不一致，
     * 排好的图当场重叠。2026-09-18 实测（题 2，44 图形）：两个 100×170 /
     * 100×220 的菱形按"60 高"排层，纵向重叠 10px 与 60px。
     *
     * <p>做法是把渲染端的规则原样搬过来，再<b>把结果写回契约</b>
     * （见 {@code ElkLayoutEngine#writeBack}）：渲染端拿到"已经装得下"的
     * 尺寸就不会再撑，布局算的层间距即最终层间距。
     *
     * <p>度量是保守的（中文 20px/字、拉丁 10px/字，实测分别≈20 与
     * 9.2~9.6）—— 拉丁偏宽会折出更多行、估出更大的高度，宁可偏大。
     *
     * @param type  图形类型
     * @param label 标签文字
     * @param width 统一节点宽度（由 {@link #nodeWidth} 给出）
     * @return {宽, 高}，已向上取整到网格
     */
    static double[] nodeSize(
            String type, String label, double width) {
        double textHeight = wrap(
                label, maxTextWidth(type, width)).size() * LINE_HEIGHT;
        double height = Math.max(NODE_HEIGHT,
                textHeight > maxTextHeight(type, NODE_HEIGHT)
                        ? containerDimensionFor(textHeight, type)
                        : NODE_HEIGHT);
        return new double[] {
            ceilToGrid(width), ceilToGrid(height),
        };
    }

    /** 向上取整到网格（尺寸只许偏大，不许偏小） */
    static double ceilToGrid(double value) {
        return Math.ceil(value / GRID) * GRID;
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
