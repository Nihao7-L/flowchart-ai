package io.github.nihaoljx.flowchart.graph.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SceneGeometryKit} 边界判据直测。
 *
 * <p>这些判据原先埋在 1933 行的 ScenePlanner 里，只能经整图排版间接
 * 验证；抽出来之后可以直接断言边界，本测试就是「独立可测」这个收益的兑现。
 * 断言口径与抽出前逐字一致 —— 若哪天有人改了常量或比较方向，这里会红。
 */
@DisplayName("SceneGeometryKit 纯几何判据")
class SceneGeometryKitTest {

    @Test
    @DisplayName("viewportPenalty：16:9 得 0，越偏越重，非正尺寸给 MAX_VALUE")
    void viewportPenaltyTracksDistanceFromSixteenNine() {
        double exact = SceneGeometryKit.viewportPenalty(1600, 900);
        assertThat(exact).isEqualTo(0.0, org.assertj.core.data.Offset.offset(1e-12));

        // 32:9 与 8:9 对称（都是离 16:9 一个对数单位的两倍）
        double wide = SceneGeometryKit.viewportPenalty(3200, 900);
        double tall = SceneGeometryKit.viewportPenalty(800, 900);
        assertThat(wide).isEqualTo(tall, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(wide).isGreaterThan(exact);

        // 非正尺寸：无法判断形态，给最大罚
        assertThat(SceneGeometryKit.viewportPenalty(0, 900))
                .isEqualTo(Double.MAX_VALUE);
        assertThat(SceneGeometryKit.viewportPenalty(-1, -1))
                .isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("fanSlide：单条不偏；多条居中对称铺开；步长封顶 16 且不小于 4")
    void fanSlideSpreadsSymmetricWithClampedStep() {
        assertThat(SceneGeometryKit.fanSlide(0, 1, 200)).isEqualTo(0.0);
        assertThat(SceneGeometryKit.fanSlide(5, 0, 200)).isEqualTo(0.0);

        // 3 条、跨度 200：step = min(16, (200-20)/4) = 16 -> -16 / 0 / +16
        assertThat(SceneGeometryKit.fanSlide(0, 3, 200)).isEqualTo(-16.0);
        assertThat(SceneGeometryKit.fanSlide(1, 3, 200)).isEqualTo(0.0);
        assertThat(SceneGeometryKit.fanSlide(2, 3, 200)).isEqualTo(16.0);

        // 跨度小到算出的步长 < 4 时，下限兜到 4（宁可有点叠也不全糊成一束）
        // span 24、cnt 3：(24-20)/4 = 1 -> clamp 到 4
        assertThat(SceneGeometryKit.fanSlide(0, 3, 24)).isEqualTo(-4.0);
        assertThat(SceneGeometryKit.fanSlide(2, 3, 24)).isEqualTo(4.0);
    }

    @Test
    @DisplayName("clamp / overlap / round1：一维区间的三个基础算子")
    void scalarIntervalPrimitives() {
        double[] interval = {10, 20};
        assertThat(SceneGeometryKit.clamp(5, interval)).isEqualTo(10.0);
        assertThat(SceneGeometryKit.clamp(25, interval)).isEqualTo(20.0);
        assertThat(SceneGeometryKit.clamp(15, interval)).isEqualTo(15.0);

        assertThat(SceneGeometryKit.overlap(new double[] {0, 15}, 10, 20))
                .isEqualTo(5.0);
        assertThat(SceneGeometryKit.overlap(new double[] {0, 10}, 10, 20))
                .isEqualTo(0.0);
        // 完全不相交也不得为负
        assertThat(SceneGeometryKit.overlap(new double[] {0, 5}, 10, 20))
                .isEqualTo(0.0);

        assertThat(SceneGeometryKit.round1(1.24)).isEqualTo(1.2);
        assertThat(SceneGeometryKit.round1(1.25)).isEqualTo(1.3);
        assertThat(SceneGeometryKit.round1(-1.25)).isEqualTo(-1.2);
    }

    @Test
    @DisplayName("segCross：十字相交算、端点汇聚不算、平行不重叠不算")
    void segCrossRecognisesOnlyRealCrossings() {
        double[] vTop = {100, 0};
        double[] vBottom = {100, 200};
        double[] hLeft = {0, 100};
        double[] hRight = {200, 100};

        // 标准十字：算
        assertThat(SceneGeometryKit.segCross(vTop, vBottom, hLeft, hRight))
                .isTrue();

        // 交点落在 hLeft 的端点附近（距端点 < 10px 且段长 >= 24）：豁免
        assertThat(SceneGeometryKit.segCross(
                vTop, vBottom, new double[] {100, 100}, hRight)).isFalse();

        // 两条平行竖线、x 差 100：不算
        assertThat(SceneGeometryKit.segCross(
                vTop, vBottom, new double[] {200, 0}, new double[] {200, 200}))
                .isFalse();

        // 共线重叠：同一 x 且纵向重叠超过 4px，算作交叉（叠成一股是缺陷）
        assertThat(SceneGeometryKit.segCross(
                new double[] {100, 0}, new double[] {100, 200},
                new double[] {100, 50}, new double[] {100, 150})).isTrue();
    }

    @Test
    @DisplayName("countCrossings：空集合给 0；按线段两两计数而非按折线计")
    void countCrossingsCountsSegmentPairs() {
        double[][] route = {{0, 100}, {200, 100}};
        assertThat(SceneGeometryKit.countCrossings(route, new ArrayList<>()))
                .isZero();

        // 已排折线有一条竖线穿过 route 的唯一一段 -> 1 次
        List<double[][]> routed = new ArrayList<>();
        routed.add(new double[][] {{100, 0}, {100, 200}});
        assertThat(SceneGeometryKit.countCrossings(route, routed)).isEqualTo(1);

        // 再加一条同位置的竖线 -> 累计 2 次（按线段对计数，不去重）
        routed.add(new double[][] {{100, 0}, {100, 200}});
        assertThat(SceneGeometryKit.countCrossings(route, routed)).isEqualTo(2);
    }

    @Test
    @DisplayName("rankCorridors：重叠宽者优先，同宽取离中心近者")
    void rankCorridorsPrefersWideThenCentral() {
        List<double[]> corridors = new ArrayList<>();
        corridors.add(new double[] {0, 10});      // 与 [40,60] 不重叠
        corridors.add(new double[] {35, 60});     // 重叠 20
        corridors.add(new double[] {45, 55});     // 重叠 10

        List<Integer> order = SceneGeometryKit.rankCorridors(corridors, 40, 60);
        assertThat(order).containsExactly(1, 2, 0);

        // 两条重叠宽度相同（都是 10）时，取中点离区间中心 50 更近的那条
        List<double[]> tie = new ArrayList<>();
        tie.add(new double[] {40, 50});           // 中点 45，距 50 为 5
        tie.add(new double[] {50, 60});           // 中点 55，距 50 为 5 -> 相等
        tie.add(new double[] {20, 30});           // 不重叠
        List<Integer> tieOrder = SceneGeometryKit.rankCorridors(tie, 40, 60);
        assertThat(tieOrder).startsWith(0, 1).endsWith(2);
    }

    @Test
    @DisplayName("nextChannel：无冲突取理想点、有冲突按 14px 错开、挤不下退回理想点")
    void nextChannelOffsetsAroundOccupiedSlots() {
        double[] interval = {0, 100};

        // 空占用 -> 原样返回理想点，槽位 0
        double[] free = SceneGeometryKit.nextChannel(
                interval, new ArrayList<>(), 50);
        assertThat(free[0]).isEqualTo(50.0);
        assertThat(free[1]).isEqualTo(0.0);

        // 50 已被占 -> 挪到 64（+14），槽位 1
        List<Double> used = new ArrayList<>();
        used.add(50.0);
        double[] shifted = SceneGeometryKit.nextChannel(interval, used, 50);
        assertThat(shifted[0]).isEqualTo(64.0);
        assertThat(shifted[1]).isEqualTo(1.0);

        // 走廊窄到放不下任何偏移（[48,52]）且理想点被占 -> 退回理想点，
        // 槽位号 = offsets.length = 9，表示"没挪动"
        List<Double> crowded = new ArrayList<>();
        crowded.add(50.0);
        double[] fallback = SceneGeometryKit.nextChannel(
                new double[] {48, 52}, crowded, 50);
        assertThat(fallback[0]).isEqualTo(50.0);
        assertThat(fallback[1]).isEqualTo(9.0);
    }

    @Test
    @DisplayName("dedupe：去零长段；两点直线补中点成三点")
    void dedupeRemovesZeroLengthAndPadsToThree() {
        // 首尾重复的零长段全部去掉，剩三点原样
        double[][] three = {
            {0, 0}, {0, 0}, {50, 0}, {50, 0}, {100, 0},
        };
        assertThat(SceneGeometryKit.dedupe(three))
                .isEqualTo(new double[][] {{0, 0}, {50, 0}, {100, 0}});

        // 两点 -> 补中点，存活 SceneSanitizer 的 >=3 点口径
        double[][] two = {{0, 0}, {100, 0}};
        assertThat(SceneGeometryKit.dedupe(two))
                .isEqualTo(new double[][] {{0, 0}, {50, 0}, {100, 0}});

        // 容差 0.5：0.4 视为重复、0.6 保留
        double[][] near = {{0, 0}, {0.4, 0}, {0.6, 0}, {100, 0}};
        double[][] cleaned = SceneGeometryKit.dedupe(near);
        assertThat(cleaned).hasDimensions(3, 2);
        assertThat(cleaned[1]).isEqualTo(new double[] {0.6, 0});
    }

    @Test
    @DisplayName("freeIntervals：外扩后取补集，且两侧各留 pad 绕外圈的余量")
    void freeIntervalsAreTheComplementOfOccupied() {
        List<double[]> spans = new ArrayList<>();
        spans.add(new double[] {100, 200});
        List<double[]> free = SceneGeometryKit.freeIntervals(spans, 4, 60);

        // 占用 [100,200] 外扩 4 -> [96,204]；整体再外扩 60 -> [40,260]
        assertThat(free).hasSize(2);
        assertThat(free.get(0)).isEqualTo(new double[] {40, 96});
        assertThat(free.get(1)).isEqualTo(new double[] {204, 260});

        // 两个相邻占用若外扩后仍然相接，中间不留洞
        List<double[]> adjacent = new ArrayList<>();
        adjacent.add(new double[] {100, 200});
        adjacent.add(new double[] {200, 300});
        assertThat(SceneGeometryKit.freeIntervals(adjacent, 4, 60)).hasSize(2);
    }

    @Test
    @DisplayName("side / sideAnchor：按参考坐标挑边，滑动量被夹在边内 8px 处")
    void sideSelectionAndAnchorClamping() {
        double[] box = {10, 20, 100, 60};   // x=10..110, y=20..80

        // alongX：参考坐标在框右半侧取右边，否则取左边
        assertThat(SceneGeometryKit.side(box, 90, true)).isEqualTo(110.0);
        assertThat(SceneGeometryKit.side(box, 90, false)).isEqualTo(80.0);
        assertThat(SceneGeometryKit.side(box, 30, true)).isEqualTo(10.0);
        assertThat(SceneGeometryKit.side(box, 30, false)).isEqualTo(20.0);

        // sideAnchor：无滑动 -> 边中点；滑动超界 -> 夹在端点内 8px
        double[] mid = SceneGeometryKit.sideAnchor(box, 90, true, 0);
        assertThat(mid[0]).isEqualTo(110.0);
        assertThat(mid[1]).isEqualTo(50.0);

        double[] over = SceneGeometryKit.sideAnchor(box, 90, true, 1000);
        assertThat(over[0]).isEqualTo(110.0);
        assertThat(over[1]).isEqualTo(80 - 8);

        double[] under = SceneGeometryKit.sideAnchor(box, 90, true, -1000);
        assertThat(under[1]).isEqualTo(20 + 8);
    }

    @Test
    @DisplayName("countHits：两端矩形豁免、外扩 clear 后仍算命中")
    void countHitsSkipsEndpointsAndRespectsClearance() {
        List<double[]> boxes = new ArrayList<>();
        boxes.add(new double[] {0, 0, 20, 20});      // 0: 源
        boxes.add(new double[] {80, 0, 20, 20});     // 1: 目标
        boxes.add(new double[] {40, 0, 20, 20});     // 2: 挡在中间

        // 从源右缘直穿目标左缘：豁免 0 与 1，命中 2 -> 1
        double[][] route = {{20, 10}, {80, 10}};
        assertThat(SceneGeometryKit.countHits(route, boxes, 0, 1, 5))
                .isEqualTo(1);

        // 不豁免任何矩形时三个都算 -> 3
        assertThat(SceneGeometryKit.countHits(route, boxes, -1, -1, 5))
                .isEqualTo(3);

        // 贴框顶 4px 掠过：clear=5 时仍算命中（与 margin 同口径）
        double[][] graze = {{20, -4}, {80, -4}};
        assertThat(SceneGeometryKit.countHits(graze, boxes, 0, 1, 5))
                .isEqualTo(1);

        // boxes 传 null 直接给 0（原实现的空列表保护）
        assertThat(SceneGeometryKit.countHits(route, null, 0, 1, 5)).isZero();
    }

    @Test
    @DisplayName("labelSize：CJK 0.9 字宽、Latin 0.45 字宽，宽度下限 24，行高 1.25")
    void labelSizeEstimatesCjkWiderThanLatin() {
        double font = 16;

        // 4 个 CJK -> 4 * 0.9 * 16 = 57.6，高 = 16 * 1.25 = 20
        double[] cjk = SceneGeometryKit.labelSize("路由转发", font);
        assertThat(cjk[0]).isEqualTo(57.6);
        assertThat(cjk[1]).isEqualTo(20.0);

        // 4 个 Latin -> 4 * 0.45 * 16 = 28.8
        double[] latin = SceneGeometryKit.labelSize("abcd", font);
        assertThat(latin[0]).isEqualTo(28.8);

        // 单字宽度小于 24 时兜到 24
        double[] short1 = SceneGeometryKit.labelSize("i", font);
        assertThat(short1[0]).isEqualTo(24.0);

        // 字号是参数：8 号字高 10
        assertThat(SceneGeometryKit.labelSize("abcd", 8)[1]).isEqualTo(10.0);
    }
}
