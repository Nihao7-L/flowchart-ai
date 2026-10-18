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
 *
 * <p>可见性：包私有 —— layout 包的 ElkLayoutEngine 复用端点吸附做不到
 * （Java 没有「同模块内跨包可见」），所以这里必须 public。纪律不变：
 * 无状态、全静态，谁往里塞实例字段谁把巨型类又长回来。
 */
public final class SceneGeometryKit {

    /** 理想视口宽高比（16:9） */
    private static final double VIEWPORT_ASPECT = 16.0 / 9.0;

    /** 走线通道间距（px） */
    private static final double CHANNEL_GAP = 14;

    /** 连线标签基准字号（px） */
    private static final double LABEL_FONT = 16;

    /**
     * 「自己的线是否真切进净框」的内缩量（罚分口径）：1px。
     * 只滤端点/折角贴脸，浅擦边仍计入。
     *
     * <p>只剩罚分器 {@code findLabelSpot} 一个调用方：绑定闸门
     * {@code canBindToArrow} 实测深度内缩救不了折角中点（折线中点几乎
     * 必然坐在折点上，标签盒永远被自己的两段真切穿过，inset 1/3/5/7 全
     * cuts=true），已改为「own 线整体豁免」（见 canBindToArrow javadoc）。
     */
    private static final double CORE_INSET_STRICT = 1;

    /**
     * 「自己的线是否真切进净框」的内缩量（绑定闸门口径）：3px。
     * 折线中点恰是折角时标签框有一半盖住那零点几 px 的折角，是渲染期
     * 结构必然（{@code bindContainerTexts} 把文字居中在折线中点），
     * 内缩 3px 后折角贴脸必被放过，只有自己的线真横穿 6px 以上才否决。
     */
    private static final double CORE_INSET_BIND = 3;

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
     * 线段与轴对齐矩形是否真有交叠（Liang-Barsky 裁剪）：擦边、贴角
     * 不算，必须有一段正长度落在矩形内。
     *
     * @param s   线段 {@code {x1, y1, x2, y2}}（绝对坐标）
     * @param box 矩形 {@code {x1, y1, x2, y2}}
     * @return 交叠返回 true
     */
    static boolean segHitsBox(double[] s, double[] box) {
        double dx = s[2] - s[0];
        double dy = s[3] - s[1];
        double x1 = s[0];
        double y1 = s[1];
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1 - box[0], box[2] - x1, y1 - box[1], box[3] - y1};
        double t0 = 0;
        double t1 = 1;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-12) {
                if (q[i] < 0) {
                    return false;
                }
                continue;
            }
            double r = q[i] / p[i];
            if (p[i] < 0) {
                if (r > t1) {
                    return false;
                }
                if (r > t0) {
                    t0 = r;
                }
            } else {
                if (r < t0) {
                    return false;
                }
                if (r < t1) {
                    t1 = r;
                }
            }
        }
        return t0 < t1;
    }

    /**
     * 矩形被多少条线段穿过，{@code owner} 名下的线段**仅在标签确实避开
     * 它时**豁免：文字没有底色，压在自己的线上照样不可读（2026-10-18
     * 实测：竖边 x=220 的箭头，标签中心线偏 16px 放、半宽 21.6px，
     * 框右缘 225.6 &gt; 220 压线，旧口径无条件豁免后报 0 穿过，标签
     * 就落在了自己的线上）。
     *
     * <p>判定用「标签框内缩 1px 的净框」，且自己的线段必须**真的切进
     * 净框**（交段长度 ≥2px、横贯深度 ≥2px）才取消豁免 —— 端点/折角
     * 贴脸仍算避开，否则竖直线箭头中段的标签必被自己那条线的两个端点
     * 否决，「短标签绑回箭头」全灭；反过来真压线（实测竖边 x=220 的
     * 箭头，标签半宽 21.6px、偏 16px 放，框右缘 225.6 &gt; 220）就照
     * 常计入，与别人的线同权。
     *
     * <p>边标签落位用：文字元素没有底色，被别的线穿过就不可读，而标签
     * 贴着自己的线放是可接受的（ELK {@code edgeLabels.inline} 同口径）。
     *
     * <p>判定基线是<b>外框</b>而不是内缩净框：边标签是文字元素，没有
     * 底色也没有衬边，线只要碰到框沿就与字形相切（前端 {@code
     * sceneAdapter} 同样按外框裁切命中）。但自己的线段有<b>端点/折角
     * 贴脸豁免</b>：正交路由的首段终点常与标签框擦个边（标签框是
     * 「中点 ± 半高」，中点恰是折角时框的一半会盖住折角那零点几
     * px），那不是压线。豁免的边界由 {@link #cutsCore} 给 —— 真真切
     * 进净框 2px 以上才算被穿。
     *
     * <p><b>2026-10-18 修正（实测后）</b>：原先 5 参版的 {@code false}
     * （内缩 3px）想同时服务绑定闸门，但绑定路径上折线中点几乎必然坐
     * 在折点上，标签盒永远被自己的两段真切穿过 —— 实测竖直线 x=220 的
     * 箭头，绑回盒 [205.6,250.0,234.4,270.0]（半宽 14.4，线距两缘都
     * 14.4 &gt; 3），任何内缩都判 cuts=true。绑定闸门 {@code canBindToArrow}
     * 已改为「own 线整体豁免」，这里只留<b>罚分口径</b>（strict=true）
     * 一个调用方：问「哪个候选更干净」，多一个折点贴脸必须让它输给真
     * 干净的候选。
     *
     * @param box    标签外框 {@code {x1, y1, x2, y2}}
     * @param segs   线段集合，每个 {@code {x1, y1, x2, y2}}
     * @param owners 与 {@code segs} 等长的归属标识（箭头的 id）
     * @param owner  当前标签所属箭头 id；其线段仅在真切进净框时取消豁免
     * @return 穿过的线段条数
     */
    static int countSegHits(
            double[] box, List<double[]> segs, List<String> owners,
            String owner) {
        return countSegHits(box, segs, owners, owner, true);
    }

    /**
     * 同 {@link #countSegHits(double[], List, List, String)}，但可指定
     * 自己的线的判定口径。
     *
     * @param strictOwnSegment {@code true} = 罚分口径（自己的线真切进
     *                         净框才计入，折角贴脸豁免）；{@code false}
     *                         = 宽口径（内缩更多）。绑定闸门不走这里，
     *                         见 {@link #countSegHits(double[], List, List, String)}
     *                         的说明与 {@code canBindToArrow}
     */
    static int countSegHits(
            double[] box, List<double[]> segs, List<String> owners,
            String owner, boolean strictOwnSegment) {
        int hits = 0;
        for (int i = 0; i < segs.size(); i++) {
            boolean own = owner != null && owner.equals(owners.get(i));
            if (own && !cutsCore(segs.get(i), box,
                    strictOwnSegment ? CORE_INSET_STRICT : CORE_INSET_BIND)) {
                continue;
            }
            if (segHitsBox(segs.get(i), box)) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * 线段是否**真的切过**标签中心区（内缩 {@code inset} px 的净框，且切
     * 进去的深度至少 2px）：端点/折角贴脸不算压线。
     *
     * <p>之所以要 2px 深度而不是「碰到就算」：竖直线箭头（正交路由最
     * 常见的形态）的首段终点与标签框可能只差零点几 px —— 标签框是
     * 「中点 ± 半高」，中点恰是折点时框的一半会盖住折角那一点。那不
     * 算压线。反过来切进净框 2px 以上的就是真穿字。
     *
     * @param inset 净框相对外框的内缩量；罚分口径 1px、绑定闸门 3px
     */
    private static boolean cutsCore(double[] s, double[] box, double inset) {
        double[] core = {
            box[0] + inset, box[1] + inset, box[2] - inset, box[3] - inset};
        if (core[0] >= core[2] || core[1] >= core[3]) {
            return false;
        }
        // 先确认线段与净框真有正长度交段（擦边/贴角在这里就被滤掉）
        double inside = clippedLength(s, core);
        if (inside < 2) {
            return false;
        }
        // 再要求横贯深度 ≥2px：水平线看 y 落进净框的深度，竖直线看 x。
        // （正交路由只产出轴对齐线段，斜线仅防御性地按 x 判定。）
        boolean horizontal = Math.abs(s[3] - s[1]) < 1e-9;
        double y = horizontal ? Math.min(s[1], s[3]) : Math.min(s[0], s[2]);
        double lo = horizontal ? core[1] : core[0];
        double hi = horizontal ? core[3] : core[2];
        if (y < lo || y > hi) {
            return false;
        }
        double depth = Math.min(y - lo, hi - y);
        return depth >= 2;
    }

    /** 线段落在矩形内的那一段的长度（0 表示不相交） */
    private static double clippedLength(double[] s, double[] core) {
        double dx = s[2] - s[0];
        double dy = s[3] - s[1];
        if (Math.abs(dx) < 1e-12 && Math.abs(dy) < 1e-12) {
            return 0;
        }
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {s[0] - core[0], core[2] - s[0],
            s[1] - core[1], core[3] - s[1]};
        double t0 = 0, t1 = 1;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-12) {
                if (q[i] < 0) {
                    return 0;
                }
                continue;
            }
            double r = q[i] / p[i];
            if (p[i] < 0) {
                if (r > t1) {
                    return 0;
                }
                if (r > t0) {
                    t0 = r;
                }
            } else {
                if (r < t0) {
                    return 0;
                }
                if (r < t1) {
                    t1 = r;
                }
            }
        }
        if (t0 >= t1) {
            return 0;
        }
        return Math.hypot(dx, dy) * (t1 - t0);
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

    // ==================== 形状感知的端点吸附 ====================
    //
    // 背景（2026-10-18 用户 m06890 截图 + 探针实测）：菱形 / 椭圆的节点，
    // 端点是按**外接矩形**算的 —— ELK 的 section start/end 落在外接矩形
    // 边上（它看不到菱形斜边），前端 snapArrowEndsToAnchors 也只把端点
    // 沿当前方向推到 bbox 边。于是"库存是否充足"菱形右下出边与菱形真实
    // 斜边之间留 10~11px 空隙（探针 ek0 实测 10.0、ek1 11.5），视觉上
    // 就是「箭头起点悬空」。
    //
    // 修法：出/入端锚点先按形状收进真实边界 —— 菱形/椭圆取射线与斜边的
    // 交点，矩形保持原值。ELK 路由与 A* 路由两条回写路径都调它，端点
    // 才真的贴着图形。
    //
    // 只做「收进边界」这一件事：不改方向、不延长线段。若端点在形状内部
    // （不应发生，但防御性处理），保持原坐标不动。

    /**
     * 射线 {@code from → to} 与**矩形**边界的交点。
     *
     * <p>用途：ELK 的 section start/end 与箭头 from/to 无关（实测过
     * start 落在目标中心），必须把端点按图形重新贴一次。矩形形状的
     * 端点此前没人管，同样会浮空或长在图形里。
     *
     * <p>退化时返回 {@code from} 原值（无正交点/射线退化）。
     *
     * @param from 射线起点（通常是当前锚点）
     * @param to   射线指向（另一个图形的中心）
     * @param box  矩形 {@code {x, y, w, h}}
     * @return 矩形边上的点 {@code {x, y}}
     */
    public static double[] edgePointOnBox(
            double[] from, double[] to, double[] box) {
        if (from == null || to == null || box == null) {
            return from;
        }
        double w = box[2];
        double h = box[3];
        if (!(w > 0) || !(h > 0)) {
            return from;
        }
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) {
            return from;
        }
        double x0 = box[0];
        double y0 = box[1];
        double x1 = box[0] + w;
        double y1 = box[1] + h;
        // slab 法：射线与矩形四条边求交，取 t ∈ (0,1] 的最小正 t
        // —— from 一般在矩形外，最小正 t 就是「从哪一侧进入矩形」
        double best = -1;
        double t;
        t = slab(dx, dy, from, x0, y0, x1, y0);
        if (t > 0 && (best < 0 || t < best)) {
            best = t;
        }
        t = slab(dx, dy, from, x1, y0, x1, y1);
        if (t > 0 && (best < 0 || t < best)) {
            best = t;
        }
        t = slab(dx, dy, from, x1, y1, x0, y1);
        if (t > 0 && (best < 0 || t < best)) {
            best = t;
        }
        t = slab(dx, dy, from, x0, y1, x0, y0);
        if (t > 0 && (best < 0 || t < best)) {
            best = t;
        }
        if (best < 0) {
            return from;
        }
        return new double[] {from[0] + dx * best, from[1] + dy * best};
    }

    /** 射线与线段所在直线的参数 t（不在线段范围内返 -1） */
    private static double slab(double dx, double dy, double[] from,
            double ax, double ay, double bx, double by) {
        double ex = bx - ax;
        double ey = by - ay;
        double rx = ax - from[0];
        double ry = ay - from[1];
        double negEx = -ex;
        double negEy = -ey;
        double det = dx * negEy - dy * negEx;
        if (Math.abs(det) < 1e-12) {
            return -1;
        }
        double t = (rx * negEy - ry * negEx) / det;
        double u = (dx * ry - dy * rx) / det;
        if (t > 1e-9 && u >= -1e-9 && u <= 1 + 1e-9) {
            return t;
        }
        return -1;
    }

    /**
     * 图形类型是否使用非矩形边界（端点需按真实轮廓收）。
     *
     * @param type 契约里的 type 字段
     * @return 菱形 / 椭圆返回 true
     */
    public static boolean hasSlopedBoundary(String type) {
        return "diamond".equals(type) || "ellipse".equals(type);
    }

    /**
     * 射线 {@code from → to} 与形状边界的交点，按形状真实轮廓算。
     *
     * <p>矩形退回外接矩形口径（与既有行为逐位一致，观感回归零风险）；
     * 椭圆解二次方程；菱形按**四条边的线段求交**。
     *
     * <p>菱形取哪个交点是有讲究的：射线与凸四边形最多两个交点，而
     * 调用方给的射线方向是「端点 → 另一个图形中心」，通常**从形状外面
     * 指向里面**。这时想要的交点是**离 `from` 最远、但仍在该线段内
     * （t &lt; 1）**的那个 —— 也就是「从哪一侧进入形状」，不是离 `from`
     * 最近的「对侧出口」。
     *
     * <p>曾按直觉取「最小正 t」，结果是岔路：起点 (440,340) 正好是菱形
     * 上顶点，四条边里只有 t=1 的下顶点命中，于是端点被推到对面顶点
     * (440,460) —— 形态没变难看，但 10px 空隙仍在。改成「t ∈ (0,1) 取
     * 最大」后，射线从外接矩形上边 (440,340) 指向下方目标中心时，正确
     * 落在上斜边 (440,343.33)，空隙归零（python 复算验证）。
     *
     * <p>退化保护：形状尺寸非正、射线退化为点、无正交点时一律返回
     * {@code to} 原值 —— 宁可留旧几何也不造 NaN。端点在 t≥1 之外
     * （锚点已越过对侧）也返回原值。
     *
     * @param from 射线起点（形状外侧的点，通常就是当前锚点）
     * @param to   射线指向（另一个端点/图形中心）
     * @param box  形状外接矩形 {@code {x, y, w, h}}
     * @param type 形状类型
     * @return 边界上的点 {@code {x, y}}
     */
    public static double[] edgePointOnShape(
            double[] from, double[] to, double[] box, String type) {
        if (from == null || to == null || box == null) {
            return to;
        }
        double w = box[2];
        double h = box[3];
        if (!(w > 0) || !(h > 0)) {
            return to;
        }
        if (!hasSlopedBoundary(type)) {
            return to;
        }
        double cx = box[0] + w / 2;
        double cy = box[1] + h / 2;
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        if (Math.abs(dx) < 1e-9 && Math.abs(dy) < 1e-9) {
            return to;
        }
        if ("ellipse".equals(type)) {
            double a = w / 2;
            double b = h / 2;
            double fx = from[0] - cx;
            double fy = from[1] - cy;
            // (fx+t·dx)²/(w/2)² + (fy+t·dy)²/(h/2)² = 1
            double qa = (dx * dx) / (a * a) + (dy * dy) / (b * b);
            double qb = 2 * (fx * dx / (a * a) + fy * dy / (b * b));
            double qc = (fx * fx) / (a * a) + (fy * fy) / (b * b) - 1;
            double t = smallestPositiveRoot(qa, qb, qc);
            if (t < 0) {
                return to;
            }
            return new double[] {from[0] + dx * t, from[1] + dy * t};
        }
        // diamond：四个顶点 (cx, cy±hh)、(cx±hw, cy) 围成的凸四边形
        // 的四条边，逐条求射线 from + t·(dx,dy) 与线段 a→b 的交点。
        //
        // 联立：
        //   from + t·d = a + u·e   （d = 射线方向，e = 边方向）
        //   ⇒ t·d - u·e = a - from =: r
        // 2x2 方程组，det = d.x·(-e.y) - d.y·(-e.x) = -(d.x·e.y - d.y·e.x)
        // 由克莱姆法则：
        //   t = (r.x·(-e.y) - r.y·(-e.x)) / det
        //   u = (d.x·r.y - d.y·r.x) / det
        // 注意 ray 方向d 与边方向 e 平叉积为 0 时 det=0（平行/共线），跳过。
        double hw = w / 2;
        double hh = h / 2;
        double[][] vertices = {
                {cx, cy - hh}, {cx + hw, cy}, {cx, cy + hh}, {cx - hw, cy},
        };
        double best = -1;
        for (int i = 0; i < 4; i++) {
            double[] a = vertices[i];
            double[] b = vertices[(i + 1) % 4];
            double ex = b[0] - a[0];
            double ey = b[1] - a[1];
            double rx = a[0] - from[0];
            double ry = a[1] - from[1];
            double negEx = -ex;
            double negEy = -ey;
            double det = dx * negEy - dy * negEx;
            if (Math.abs(det) < 1e-12) {
                continue; // 平行或共线
            }
            double t = (rx * negEy - ry * negEx) / det;
            double u = (dx * ry - dy * rx) / det;
            // 只在 (0,1) 内的交点里取**最大**的 t：端点在外、射线指向
            // 另一个图形中心时，「从哪侧进入形状」= 距离 from 最远、且仍
            // 在本线段内的那个交点。取最小正 t 会命中对侧出口（实例见
            // 方法 javadoc 的 (440,340)→(440,460) 退化）。
            if (t > 1e-9 && t < 1 - 1e-9
                    && u >= -1e-9 && u <= 1 + 1e-9
                    && (best < 0 || t > best)) {
                best = t;
            }
        }
        if (best < 0) {
            return to;
        }
        return new double[] {from[0] + dx * best, from[1] + dy * best};
    }

    /** 二次方程最小正根；无正根返回 -1 */
    private static double smallestPositiveRoot(
            double a, double b, double c) {
        if (Math.abs(a) < 1e-12) {
            if (Math.abs(b) < 1e-12) {
                return -1;
            }
            double t = -c / b;
            return t > 1e-9 ? t : -1;
        }
        double disc = b * b - 4 * a * c;
        if (disc < 0) {
            return -1;
        }
        double sq = Math.sqrt(disc);
        double t1 = (-b - sq) / (2 * a);
        double t2 = (-b + sq) / (2 * a);
        double lo = Math.min(t1, t2);
        double hi = Math.max(t1, t2);
        if (lo > 1e-9) {
            return lo;
        }
        return hi > 1e-9 ? hi : -1;
    }
}
