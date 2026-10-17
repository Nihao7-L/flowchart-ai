package io.github.nihaoljx.flowchart.graph.transform;

import java.util.ArrayList;
import java.util.List;

/**
 * 排版用的纯几何工具箱（从 ScenePlanner 抽出，report 3.2 巨型类拆分）。
 *
 * <p>收在这里的判据：**不碰任何场景数据模型**（Node / Block / Partition /
 * JsonNode 一律不出现），只吃 {@code double}、{@code double[]} 与
 * {@code List<double[]>}。它们的语义是「两个区间怎么摆」「两条线段交不交」
 * 这类可独立推演的数学，与「这块该排在哪」的业务判断无关。
 *
 * <p>抽出的另一个后果是**可单独测试**：这些判据原先埋在 1751 行的
 * ScenePlanner 里只能经整图排版间接验证，现在能直接断言边界。行为与
 * 抽出前逐行等价 —— 只搬位置、不改一处逻辑与常量值。
 *
 * <p>纪律：本类必须保持无状态、全静态。一旦有人往这里塞实例字段或
 * 场景依赖，就等于把巨型类又长了回来。
 */
final class SceneGeometryKit {

    /** 理想视口宽高比（16:9） */
    private static final double VIEWPORT_ASPECT = 16.0 / 9.0;

    /** 走线通道间距（px） */
    private static final double CHANNEL_GAP = 14;

    /** 连线标签基准字号（px） */
    private static final double LABEL_FONT = 16;

    private SceneGeometryKit() {
    }

    /**
     * 视口比例惩罚：离 16:9 越远罚越重（长条图不讨喜）。
     *
     * @param width  排版结果宽
     * @param height 排版结果高
     * @return 离理想宽高比的对数距离；非正尺寸返回 {@link Double#MAX_VALUE}
     */
    static double viewportPenalty(double width, double height) {
        if (width <= 0 || height <= 0) {
            return Double.MAX_VALUE;
        }
        return Math.abs(Math.log((width / height) / VIEWPORT_ASPECT));
    }

    /**
     * 同侧多线扇出时的横向错位量（避免多条线重叠成一束）。
     *
     * @param idx  第几条（0 起）
     * @param cnt  总共几条
     * @param span 可用跨度
     * @return 相对中位的偏移；cnt ≤ 1 时为 0
     */
    static double fanSlide(int idx, int cnt, double span) {
        if (cnt <= 1) {
            return 0;
        }
        double step = Math.min(16, (span - 20) / (cnt + 1));
        step = Math.max(step, 4);
        return (idx - (cnt - 1) / 2.0) * step;
    }

    /**
     * 一条折线与其他已排折线的交叉段数。
     *
     * @param route  待测折线
     * @param routed 已排折线
     * @return 交叉段总数
     */
    static int countCrossings(
            double[][] route, List<double[][]> routed) {
        if (routed.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (double[][] other : routed) {
            for (int i = 0; i + 1 < route.length; i++) {
                for (int j = 0; j + 1 < other.length; j++) {
                    if (segCross(route[i], route[i + 1],
                            other[j], other[j + 1])) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    /**
     * 两条线段是否「算交叉」（正交布线口径，非通用线段求交）。
     *
     * <p>只认水平/垂直两类线段；端点 10px 内的接触一律豁免 ——
     * 同框出线/入线的必然汇聚不是缺陷。
     *
     * @param p1 第一条线段起点
     * @param p2 第一条线段终点
     * @param p3 第二条线段起点
     * @param p4 第二条线段终点
     * @return 判定为交叉返回 true
     */
    static boolean segCross(
            double[] p1, double[] p2, double[] p3, double[] p4) {
        boolean v1 = Math.abs(p1[0] - p2[0]) < 0.5;
        boolean v2 = Math.abs(p3[0] - p4[0]) < 0.5;
        if (v1 && v2) {
            return Math.abs(p1[0] - p3[0]) < 3
                    && Math.max(Math.min(p1[1], p2[1]),
                            Math.min(p3[1], p4[1]))
                    <= Math.min(Math.max(p1[1], p2[1]),
                            Math.max(p3[1], p4[1])) - 4;
        }
        if (!v1 && !v2) {
            return Math.abs(p1[1] - p3[1]) < 3
                    && Math.max(Math.min(p1[0], p2[0]),
                            Math.min(p3[0], p4[0]))
                    <= Math.min(Math.max(p1[0], p2[0]),
                            Math.max(p3[0], p4[0])) - 4;
        }
        double ix;
        double iy;
        if (v1) {
            ix = p1[0];
            iy = p3[1];
        } else {
            ix = p3[0];
            iy = p1[1];
        }
        boolean on1 = ix >= Math.min(p1[0], p2[0])
                && ix <= Math.max(p1[0], p2[0])
                && iy >= Math.min(p1[1], p2[1])
                && iy <= Math.max(p1[1], p2[1]);
        boolean on2 = ix >= Math.min(p3[0], p4[0])
                && ix <= Math.max(p3[0], p4[0])
                && iy >= Math.min(p3[1], p4[1])
                && iy <= Math.max(p3[1], p4[1]);
        if (!on1 || !on2) {
            return false;
        }
        // 端点 10px 内的接触不算交叉（同框出线/入线的必然汇聚）
        double[][] segs = {p1, p2, p3, p4};
        for (int i = 0; i < 4; i += 2) {
            double dx = Math.abs(segs[i][0] - segs[i + 1][0]);
            double dy = Math.abs(segs[i][1] - segs[i + 1][1]);
            double longSide = Math.max(dx, dy);
            if (longSide < 24) {
                continue; // 极短段不参与豁免判定
            }
            // 端点距交点：取线段两端中较近者
            double d = Math.min(
                    Math.hypot(ix - segs[i][0], iy - segs[i][1]),
                    Math.hypot(ix - segs[i + 1][0],
                            iy - segs[i + 1][1]));
            // 豁免仅对"交点位于段端部"成立：投影在端头 10px 内
            double proj = dx > dy ? ix : iy;
            double lo = dx > dy
                    ? Math.min(segs[i][0], segs[i + 1][0])
                    : Math.min(segs[i][1], segs[i + 1][1]);
            double hi = dx > dy
                    ? Math.max(segs[i][0], segs[i + 1][0])
                    : Math.max(segs[i][1], segs[i + 1][1]);
            boolean nearEnd = proj - lo < 10 || hi - proj < 10;
            if (nearEnd && d < 24) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把值夹进区间。
     *
     * @param v        待夹值
     * @param interval 闭区间 {lo, hi}
     * @return 夹取结果
     */
    static double clamp(double v, double[] interval) {
        return Math.max(interval[0],
                Math.min(interval[1], v));
    }

    /**
     * 走线通道候选排序：与 {@code [lo,hi]} 重叠宽者优先；
     * 同宽取离区间中心近者。
     *
     * @param corridors 候选通道（各自是 {lo, hi}）
     * @param lo        参考区间下界
     * @param hi        参考区间上界
     * @return 下标序
     */
    static List<Integer> rankCorridors(
            List<double[]> corridors, double lo, double hi) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < corridors.size(); i++) {
            order.add(i);
        }
        double mid = (lo + hi) / 2;
        order.sort((a, b) -> {
            double oa = overlap(corridors.get(a), lo, hi);
            double ob = overlap(corridors.get(b), lo, hi);
            if (Math.abs(ob - oa) > 1e-9) {
                return Double.compare(ob, oa);
            }
            double ca = (corridors.get(a)[0] + corridors.get(a)[1]) / 2;
            double cb = (corridors.get(b)[0] + corridors.get(b)[1]) / 2;
            return Double.compare(Math.abs(ca - mid),
                    Math.abs(cb - mid));
        });
        return order;
    }

    /**
     * 两个一维区间的重叠长度。
     *
     * @param interval 区间 {lo, hi}
     * @param lo       另一区间下界
     * @param hi       另一区间上界
     * @return 重叠长度，无重叠为 0
     */
    static double overlap(
            double[] interval, double lo, double hi) {
        return Math.max(0,
                Math.min(interval[1], hi)
                        - Math.max(interval[0], lo));
    }

    /**
     * 通道内取一个与已用坐标错开的点。
     *
     * <p>先取理想点，冲突则按 {@link #CHANNEL_GAP} 步长向两侧探测；
     * 通道太窄挤不下就退回理想点（宁叠不越界）。
     *
     * @param interval 可用区间 {lo, hi}
     * @param used     已占用的坐标
     * @param ideal    理想坐标
     * @return {@code {坐标, 槽位号}}；槽位号等于探测次数时表示"没挪动"
     */
    static double[] nextChannel(
            double[] interval, List<Double> used, double ideal) {
        double base = clamp(ideal, interval);
        double[] offsets = {0, CHANNEL_GAP, -CHANNEL_GAP,
            2 * CHANNEL_GAP, -2 * CHANNEL_GAP, 3 * CHANNEL_GAP,
            -3 * CHANNEL_GAP, 4 * CHANNEL_GAP, -4 * CHANNEL_GAP};
        for (int slot = 0; slot < offsets.length; slot++) {
            double cand = base + offsets[slot];
            if (cand < interval[0] || cand > interval[1]) {
                continue;
            }
            boolean clash = false;
            for (double u : used) {
                if (Math.abs(u - cand) < CHANNEL_GAP - 1) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                return new double[] {cand, slot};
            }
        }
        return new double[] {base, offsets.length};
    }

    /**
     * 去掉零长段；两点直线补一个中点（存续 SceneSanitizer 的 ≥3 点口径）。
     *
     * @param route 原始折线
     * @return 规整后的折线
     */
    static double[][] dedupe(double[][] route) {
        List<double[]> pts = new ArrayList<>();
        for (double[] p : route) {
            if (!pts.isEmpty()
                    && Math.abs(pts.get(pts.size() - 1)[0] - p[0]) < 0.5
                    && Math.abs(pts.get(pts.size() - 1)[1] - p[1]) < 0.5) {
                continue;
            }
            pts.add(p);
        }
        if (pts.size() == 2) {
            double[] a = pts.get(0);
            double[] b = pts.get(1);
            pts.add(1, new double[] {(a[0] + b[0]) / 2,
                (a[1] + b[1]) / 2});
        }
        return pts.toArray(new double[0][]);
    }

    /**
     * 保留一位小数（坐标写回 JSON 时的统一口径）。
     *
     * @param value 原值
     * @return 一位小数的值
     */
    static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /**
     * 折线撞到多少个无关矩形（两端矩形豁免，外扩 {@code clear} 容差）。
     *
     * <p>轴对齐线段与矩形的相交就是一维区间的两两相交。容差与
     * {@code assertRoutesAvoidNodes} 的 margin 同口径。
     *
     * @param route   折线（绝对坐标）
     * @param boxes   矩形集合，每个是 {@code {x, y, w, h}}
     * @param skipA   豁免矩形下标（源框），可传 -1
     * @param skipB   豁免矩形下标（目标框），可传 -1
     * @param clear   外扩容差（px）
     * @return 命中个数
     */
    static int countHits(
            double[][] route, List<double[]> boxes,
            int skipA, int skipB, double clear) {
        if (boxes == null) {
            return 0;
        }
        int hits = 0;
        for (int k = 0; k < boxes.size(); k++) {
            if (k == skipA || k == skipB) {
                continue;
            }
            double[] b = boxes.get(k);
            double x1 = b[0] - clear;
            double y1 = b[1] - clear;
            double x2 = b[0] + b[2] + clear;
            double y2 = b[1] + b[3] + clear;
            for (int i = 0; i + 1 < route.length; i++) {
                double sx = Math.min(route[i][0], route[i + 1][0]);
                double ex = Math.max(route[i][0], route[i + 1][0]);
                double sy = Math.min(route[i][1], route[i + 1][1]);
                double ey = Math.max(route[i][1], route[i + 1][1]);
                if (ex >= x1 && sx <= x2 && ey >= y1 && sy <= y2) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * 一维自由区间：给定占用的闭区间，求其并集的补集。
     *
     * <p>落在自由区间里的轴线永不与任何占用相交 —— 走廊的判定基础。
     * 区间外扩到图形包络外 {@code pad}，允许绕外侧走线。
     *
     * @param spans 占用区间，每个是 {@code {lo, hi}}
     * @param margin 每个占用区间两侧各外扩的量
     * @param pad    整体两侧再外扩的量（绕外圈走线的余量）
     * @return 自由区间（各自 {@code {lo, hi}}），按 lo 升序
     */
    static List<double[]> freeIntervals(
            List<double[]> spans, double margin, double pad) {
        List<double[]> occupied = new ArrayList<>(spans.size());
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double[] s : spans) {
            occupied.add(new double[] {s[0] - margin, s[1] + margin});
            lo = Math.min(lo, s[0]);
            hi = Math.max(hi, s[1]);
        }
        occupied.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> free = new ArrayList<>();
        double cursor = lo - pad;
        for (double[] o : occupied) {
            if (o[0] > cursor) {
                free.add(new double[] {cursor, o[0]});
            }
            cursor = Math.max(cursor, o[1]);
        }
        if (hi + pad > cursor) {
            free.add(new double[] {cursor, hi + pad});
        }
        return free;
    }

    /**
     * 框的边缘锚点坐标：{@code alongX} 为 true 时取朝
     * {@code coordinate} 方向的左/右边中点 x，否则取上/下边中点 y。
     *
     * @param box        矩形 {@code {x, y, w, h}}
     * @param coordinate 参考坐标
     * @param alongX     是否沿 x 轴取边
     * @return 锚点坐标
     */
    static double side(double[] box, double coordinate, boolean alongX) {
        if (alongX) {
            return coordinate >= box[0] + box[2] / 2
                    ? box[0] + box[2] : box[0];
        }
        return coordinate >= box[1] + box[3] / 2
                ? box[1] + box[3] : box[1];
    }

    /**
     * 框边缘锚点坐标对：{@code alongX} 为 true 时锚在朝
     * {@code coordinate} 一侧的左/右边（x 固定，y 按滑动量偏移并夹在边内）；
     * 否则为上/下边。
     *
     * @param box        矩形 {@code {x, y, w, h}}
     * @param coordinate 参考坐标
     * @param alongX     是否沿 x 轴取边
     * @param slide      沿边方向的偏移量
     * @return 锚点 {@code {x, y}}
     */
    static double[] sideAnchor(
            double[] box, double coordinate, boolean alongX, double slide) {
        if (alongX) {
            double x = coordinate >= box[0] + box[2] / 2
                    ? box[0] + box[2] : box[0];
            double y = Math.max(box[1] + 8, Math.min(box[1] + box[3] - 8,
                    box[1] + box[3] / 2 + slide));
            return new double[] {x, y};
        }
        double y = coordinate >= box[1] + box[3] / 2
                ? box[1] + box[3] : box[1];
        double x = Math.max(box[0] + 8, Math.min(box[0] + box[2] - 8,
                box[0] + box[2] / 2 + slide));
        return new double[] {x, y};
    }

    /**
     * 估算标签尺寸：中日韩字符按 0.9 字宽、其余按 0.45 字宽。
     *
     * @param content 标签文本
     * @param font    字号（px）
     * @return {@code {宽, 高}}
     */
    static double[] labelSize(String content, double font) {
        double w = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            w += (c >= 0x2E80) ? 0.9 : 0.45;
        }
        double width = Math.max(w * font, 24);
        return new double[] {width, font * 1.25};
    }
}
