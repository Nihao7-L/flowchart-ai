package io.github.nihaoljx.flowchart.graph.transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 跨模块箭头的 A* 正交路由器（2026-10-15 自 ScenePlanner 拆出独立类，
 * 文件行数超 checkstyle 上限是直接动因，路由关注点独立也是本意）。
 *
 * <p>方案学自 JointJS 的 manhattan 路由器（GitHub: clientio/joint，
 * Apache 2.0）：细网格 A* + 节点障碍图 + 拐弯惩罚；再叠加 libavoid
 * （同厂商业包 @joint/router-avoid 的开源内核思想）式的「已路由边
 * 占用惩罚」—— JointJS 单边路由不管边与边交叉，而我们的 198 处交叉
 * （regress_arch6 实测）恰恰全部来自边与边在走廊里叠成一股。
 *
 * <p>模板候选制（ScenePlanner.routeOne）保留为兜底：A* 网格不可达
 * 或场景异常大时退回。
 */
final class OrthoAStarRouter {

    private OrthoAStarRouter() {
    }

    /** 网格步长（px）：JointJS 默认 10，我们场景大一档用 20（格子少 4 倍、观感足够） */
    private static final int GRID_STEP = 20;

    /** 节点障碍外扩（px）：路由线离框边至少 8px，严于 countHits 的 5px 命中线 */
    private static final double GRID_OBSTACLE_PAD = 8;

    /** 直行一步的代价（= 步长，与 JointJS dir.cost 同构） */
    private static final double COST_STEP = GRID_STEP;

    /**
     * 90° 拐弯代价。
     *
     * <p>2026-10-17 电商架构图实测（用户 m04384 指出「能不用拐弯就
     * 不用」）：原值 {@code step/2 = 10} 与 COST_OCCUPIED=60 严重倒挂，
     * A* 宁愿用 5 个小折（拐弯共付 50）去躲一格 occupied，也不肯 2 折
     * 绕开 —— 每条边实测 4~6 折（H+20,V±10,H+140,V±40,H+20,V∓10），
     * 而理想 L 形只要 1 折。
     *
     * <p>业界量级（子代理调研 b612480d）：
     * <ul>
     *   <li>libavoid {@code segmentPenalty} 默认 10，且官方要求正交路由
     *       必须 &gt; 0，否则出现 staircase 路径（正是本病的抖动形态）；</li>
     *   <li>yFiles {@code OrthogonalEdgeRouter.crossingCost} 默认 1.5、
     *       建议区间 1.0–3.0，语义「宁可转向 1~3 次也不跨一条边」——反过来
     *       说明一次转弯必须显著贵于一步；</li>
     *   <li>ELK layered 在结构化阶段就用 favorStraightEdges /
     *       unnecessaryBendpoints 抑制折点，而非事后补救。</li>
     * </ul>
     *
     * <p>结论：turn:step = 3:1（60），即「多走 3 格直线换掉 1 个折点」
     * 仍然更优，抖动静止小段（JOG_SNAP_TOLERANCE=10px）不再值得走。
     */
    private static final int COST_TURN = 60;

    /**
     * 吸附容差（px）：真实 A* 折线在转完后会被 mergeCollinear 合并共线点，
     * 残留的 |Δy|≤10 / |Δx|≤10 反向小段是「锚点 snap jog」——探针实测
     * PROBE user_center→order_center 得 (160,30)→(180,40)→(320,40)，
     * 那个 V+10 纯粹因为锚点 y=30 不在网格线上、被 colOf/rowOf 量化到
     * 就近网格线 y=40 造成。它不承载任何避让语义，纯粹是噪音折点
     * （用户截图里「一根连线 6 个转折点」的主体）。
     *
     * <p>治理：不在 A* 里做（代价函数管不住连续坐标与网格的偏差），
     * 而在路由回写前统一做 jog elimination —— 见
     * {@link #snapJogs(List)}。
     */
    private static final double JOG_SNAP_TOLERANCE = GRID_OBSTACLE_PAD + 2;

    /**
     * 防绕回惩罚：沿「远离目标」方向前进的每一步额外计费。
     *
     * <p>调研对应物：libavoid {@code reverseDirectionPenalty}（绕障碍
     * 打圈的对症项）、yFiles
     * {@code setMonotonicPathRestriction(MONOTONIC_BOTH)}（从算法上
     * 禁止回退、消灭 bounding box 外的 wrap-around）。这里取软惩罚
     * 而非硬禁止：硬禁止会让短边在密集区无解，而软惩罚仅在确实需要
     * 绕大圈时才让步。
     *
     * <p>量级取 1 个折点（60）：总绕远路（4 步回退 = 80）比 1 折 + 1 格
     * 绕行更贵，但比「必须绕一大圈才能避开所有障碍」的场景仍可达成。
     */
    private static final int COST_BACKTRACK = 3;

    /**
     * 踩到已路由边占用格的代价。
     *
     * <p>调研（b612480d）建议升到 120，但明确要求「必须在 COST_TURN
     * 之后做，否则只是把抖动躲线变成绕远躲线」。本轮先落 3×step（60）
     * + turn 60 的配比 —— 此时躲一格占用至少要付 60，与走 1 折等价，
     * 已能压住抖动；120 留给下一轮按截图复核后再定。
     *
     * <p>注意「叠线更重要」的定性结论（Purchase GD'98：交叉最重要、
     * 折点次之；yFiles crossingCost 1.0–3.0）指的是边与边交叉，而
     * 这里计的是「与已路由边并行重合」——同为边与边关系，优先级必须
     * 高于折点。
     */
    private static final double COST_OCCUPIED = COST_STEP * 3;

    /** 单次 A* 的格子数上限：超出直接放弃走模板兜底（防异常大场景卡死） */
    private static final int GRID_CELL_LIMIT = 40_000;

    /** 四个方向的偏移：0=+x 1=+y 2=-x 3=-y */
    private static final int[] DIR_DX = {1, 0, -1, 0};
    private static final int[] DIR_DY = {0, 1, 0, -1};

    /**
     * 锚点面索引 → 走步方向索引。side 0=左 1=下 2=右 3=上
     * （sideAnchorAt 的口径），DIR 的口径是 0=+x 1=+y 2=-x 3=-y，
     * 两套索引不同 —— 2026-10-15 实测教训：混用后 A* 起步方向冲着
     * 自己框里走，路径全部成了「从框里绕出来」的鬼路。
     */
    private static final int[] SIDE_TO_DIR = {2, 1, 0, 3};

    /**
     * 已路由边的网格占用表：边与边分离的关键（libavoid 的 nudge 思想）。
     * 后路由的边踩到占用格要付重罚，会主动换走廊 —— 「线叠成一坨分不清
     * 谁连谁」的直接治理。
     */
    static final class Grid {
        private final double originX;
        private final double originY;
        private final int cols;
        private final int rows;
        private final boolean[] used;

        Grid(List<ScenePlanner.Node> nodes) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (ScenePlanner.Node n : nodes) {
                minX = Math.min(minX, n.x);
                minY = Math.min(minY, n.y);
                maxX = Math.max(maxX, n.x + n.w);
                maxY = Math.max(maxY, n.y + n.h);
            }
            // 四周留 3 步绕行余量
            originX = Math.floor(minX - 3 * GRID_STEP);
            originY = Math.floor(minY - 3 * GRID_STEP);
            cols = (int) Math.ceil((maxX + 3 * GRID_STEP - originX)
                    / GRID_STEP) + 1;
            rows = (int) Math.ceil((maxY + 3 * GRID_STEP - originY)
                    / GRID_STEP) + 1;
            used = new boolean[cols * rows];
        }

        int colOf(double x) {
            return (int) Math.round((x - originX) / GRID_STEP);
        }

        int rowOf(double y) {
            return (int) Math.round((y - originY) / GRID_STEP);
        }

        double xOf(int col) {
            return originX + col * GRID_STEP;
        }

        double yOf(int row) {
            return originY + row * GRID_STEP;
        }

        boolean inGrid(int col, int row) {
            return col >= 0 && col < cols && row >= 0 && row < rows;
        }

        /**
         * 网格点是否落在任何节点外扩障碍内。端点的框也拦 —— 起终点
         * 由锚点 + 法向一步的 stub 接进框（stub 不走格子检查），A*
         * 格子路径一旦能穿进自己的框，就会走出「从框里绕出来」的鬼路。
         */
        boolean blocked(int col, int row, List<ScenePlanner.Node> nodes) {
            double x = xOf(col);
            double y = yOf(row);
            for (ScenePlanner.Node n : nodes) {
                if (x >= n.x - GRID_OBSTACLE_PAD
                        && x <= n.x + n.w + GRID_OBSTACLE_PAD
                        && y >= n.y - GRID_OBSTACLE_PAD
                        && y <= n.y + n.h + GRID_OBSTACLE_PAD) {
                    return true;
                }
            }
            return false;
        }

        boolean occupied(int col, int row) {
            return used[row * cols + col];
        }

        /** 把一条绝对坐标折线经过的格子标记为已占用 */
        void mark(double[][] route) {
            for (int i = 0; i + 1 < route.length; i++) {
                walkAndMark(route[i], route[i + 1]);
            }
        }

        private void walkAndMark(double[] a, double[] b) {
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double len = Math.abs(dx) + Math.abs(dy);
            int steps = (int) Math.ceil(len / GRID_STEP) + 1;
            for (int i = 0; i <= steps; i++) {
                double ratio = steps == 0 ? 0 : (double) i / steps;
                int col = colOf(a[0] + dx * ratio);
                int row = rowOf(a[1] + dy * ratio);
                if (inGrid(col, row)) {
                    used[row * cols + col] = true;
                }
            }
        }
    }

    /**
     * 为一条跨模块边求 A* 路由（绝对坐标折线，共线已合并），失败回 null。
     *
     * <p>锚点候选 = 源/目标各四边（带扇出滑动偏移），16 个组合各搜一次
     * 取总代价最小 —— JointJS manhattan 的锚点候选 + A* 主体，再加
     * libavoid 式占用惩罚（边与边分离的根治点）。
     */
    static List<double[]> route(
            ScenePlanner.Node f, ScenePlanner.Node t,
            int outIdx, int outCnt, int inIdx, int inCnt,
            List<ScenePlanner.Node> nodes, Grid grid) {
        if ((long) grid.cols * grid.rows > GRID_CELL_LIMIT) {
            return null;
        }
        double[] sSlides = slidePair(outIdx, outCnt);
        double[] eSlides = slidePair(inIdx, inCnt);
        double bestCost = Double.POSITIVE_INFINITY;
        List<double[]> bestPolyline = null;
        for (int sSide = 0; sSide < 4; sSide++) {
            for (double sSlide : sSlides) {
                double[] sa = quantizeAnchor(grid, f, sSide,
                        sideAnchorAt(f, sSide, sSlide));
                for (int eSide = 0; eSide < 4; eSide++) {
                    for (double eSlide : eSlides) {
                        double[] ea = quantizeAnchor(grid, t, eSide,
                                sideAnchorAt(t, eSide, eSlide));
                        List<double[]> path = astarOne(
                                grid, nodes, sa, sSide, ea, eSide, true);
                        if (path == null) {
                            path = astarOne(
                                    grid, nodes, sa, sSide, ea, eSide, false);
                        }
                        if (path == null) {
                            continue;
                        }
                        double cost = pathCost(path);
                        if (cost < bestCost) {
                            bestCost = cost;
                            bestPolyline = path;
                        }
                    }
                }
            }
        }
        return bestPolyline == null
                ? null
                : snapJogs(mergeCollinear(bestPolyline), nodes);
    }

    /**
     * jog elimination：删掉 |Δ| ≤ JOG_SNAP_TOLERANCE 的「去—回」反向小段对。
     *
     * <p>为什么放在这里而不是 A* 代价函数：这类折点的成因是锚点落在
     * 网格线之间（如真实 y=30 被量化到网格 y=40），A* 只看见网格坐标、
     * 对「到锚点还差 10px」一无所知；而出端 20px stub 与入端 20px stub
     * 之间的距离恰好由这些小段承担，几何上必然与 A* 无关地存在。
     *
     * <p>治理口径（业界 jog elimination / ELK unnecessaryBendpoints /
     * libavoid performUnifyingNudgingPreprocessingStep 同向）：把
     * 「横 20 → 竖 10 → 横 20」这种首尾同向、中间反向小段整体抹平，
     * 并把后续折点平移对齐，消除锚点 snap 噪音。
     *
     * <p>阈值取 10px（&lt; 1 个网格步长）：超过 1 格的位移一般承载真实
     * 走廊语义（如错开 8px 障碍外扩框），不能被抹。
     *
     * <p>只治理「首段 stub」与「末段 stub」上的 jog：试点是 cJog 模式
     * （a、b 两点几乎重合的退化对）。中间的 jog（如避让走廊）保留 ——
     * 2026-10-17 实测（m04384 电商图）证明中间 jog 也是绕行主体，但
     * 其阈值 10px 恰好等于锚点容差，误伤的代价高于收益。
     */
    private static List<double[]> snapJogs(List<double[]> pts,
            List<ScenePlanner.Node> nodes) {
        if (pts.size() <= 4) {
            return pts;
        }
        List<double[]> work = new ArrayList<>(pts);
        // 末段 jog：末点 d 与它前两点 b/c 几乎重合 ⇒ d 归一到 b/c 的公共点
        int last = work.size() - 1;
        double[] d = work.get(last);
        double[] c = work.get(last - 1);
        double[] b = work.get(last - 2);
        double tailX = Math.abs(c[0] - d[0]) <= JOG_SNAP_TOLERANCE
                ? c[0] : d[0];
        double tailY = Math.abs(c[1] - d[1]) <= JOG_SNAP_TOLERANCE
                ? c[1] : d[1];
        tailX = Math.abs(b[0] - d[0]) <= JOG_SNAP_TOLERANCE && tailX == c[0]
                ? b[0] : tailX;
        tailY = Math.abs(b[1] - d[1]) <= JOG_SNAP_TOLERANCE && tailY == c[1]
                ? b[1] : tailY;
        if (!(Math.abs(b[0] - tailX) <= JOG_SNAP_TOLERANCE
                && Math.abs(b[1] - tailY) <= JOG_SNAP_TOLERANCE)) {
            return pts;
        }
        b[0] = tailX;
        b[1] = tailY;
        c[0] = tailX;
        c[1] = tailY;
        d[0] = tailX;
        d[1] = tailY;
        work.remove(last - 1);
        work.remove(last - 1);
        // 首段 jog：首点 a 与它后两点 b/c 几乎重合 ⇒ a 归一到 b/c 的公共点
        double[] a2 = work.get(0);
        double[] b2 = work.get(1);
        double[] c2 = work.get(2);
        double headX = Math.abs(b2[0] - a2[0]) <= JOG_SNAP_TOLERANCE
                ? b2[0] : a2[0];
        double headY = Math.abs(b2[1] - a2[1]) <= JOG_SNAP_TOLERANCE
                ? b2[1] : a2[1];
        headX = Math.abs(c2[0] - a2[0]) <= JOG_SNAP_TOLERANCE && headX == b2[0]
                ? c2[0] : headX;
        headY = Math.abs(c2[1] - a2[1]) <= JOG_SNAP_TOLERANCE && headY == b2[1]
                ? c2[1] : headY;
        if (!(Math.abs(c2[0] - headX) <= JOG_SNAP_TOLERANCE
                && Math.abs(c2[1] - headY) <= JOG_SNAP_TOLERANCE)) {
            return pts;
        }
        a2[0] = headX;
        a2[1] = headY;
        b2[0] = headX;
        b2[1] = headY;
        c2[0] = headX;
        c2[1] = headY;
        work.remove(2);
        work.remove(1);
        List<double[]> snapped = mergeCollinear(work);
        return hitsNode(snapped, nodes) ? pts : snapped;
    }

    /**
     * 治抖后的折线是否穿过任何节点框（8px 外扩）。
     * 用于治抖回退：若抹平造成穿框，宁可保留原始折线
     * —— 2026-10-17 实测（m04384 电商图）snapJogs 把「绕 login_module」
     * 的承载折点抹平后直穿框，穿框比多折点伤害更大。
     */
    private static boolean hitsNode(List<double[]> pts,
            List<ScenePlanner.Node> nodes) {
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i);
            double[] e = pts.get(i + 1);
            for (ScenePlanner.Node n : nodes) {
                double px = n.x - 8;
                double py = n.y - 8;
                double qx = n.x + n.w + 8;
                double qy = n.y + n.h + 8;
                boolean horizontal = Math.abs(a[1] - e[1]) < 0.5;
                boolean onBoxA = (Math.abs(a[0] - n.x) < 0.5
                        || Math.abs(a[0] - (n.x + n.w)) < 0.5
                        || Math.abs(a[1] - n.y) < 0.5
                        || Math.abs(a[1] - (n.y + n.h)) < 0.5)
                        && a[0] >= px - 0.5 && a[0] <= qx + 0.5
                        && a[1] >= py - 0.5 && a[1] <= qy + 0.5;
                boolean onBoxB = (Math.abs(e[0] - n.x) < 0.5
                        || Math.abs(e[0] - (n.x + n.w)) < 0.5
                        || Math.abs(e[1] - n.y) < 0.5
                        || Math.abs(e[1] - (n.y + n.h)) < 0.5)
                        && e[0] >= px - 0.5 && e[0] <= qx + 0.5
                        && e[1] >= py - 0.5 && e[1] <= qy + 0.5;
                boolean nearBox = (a[0] >= px - 0.5 && a[0] <= qx + 0.5
                        && a[1] >= py - 0.5 && a[1] <= qy + 0.5
                        && (Math.abs(a[1] - py) <= 0.5
                        || Math.abs(a[1] - qy) <= 0.5
                        || Math.abs(a[0] - px) <= 0.5
                        || Math.abs(a[0] - qx) <= 0.5))
                        || (e[0] >= px - 0.5 && e[0] <= qx + 0.5
                        && e[1] >= py - 0.5 && e[1] <= qy + 0.5
                        && (Math.abs(e[1] - py) <= 0.5
                        || Math.abs(e[1] - qy) <= 0.5
                        || Math.abs(e[0] - px) <= 0.5
                        || Math.abs(e[0] - qx) <= 0.5));
                if (onBoxA || onBoxB || nearBox) {
                    continue;
                }
                if (horizontal && a[1] >= py && a[1] <= qy
                        && Math.max(a[0], e[0]) >= px
                        && Math.min(a[0], e[0]) <= qx) {
                    return true;
                }
                if (!horizontal && a[0] >= px && a[0] <= qx
                        && Math.max(a[1], e[1]) >= py
                        && Math.min(a[1], e[1]) <= qy) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 扇出锚点滑动量对：同边多条线时错开 ±slide，单条退中点。
     * 与 ScenePlanner.fanSlide 的间距口径一致（span=100 时的步长）。
     */
    private static double[] slidePair(int idx, int cnt) {
        double step = Math.min(16, 80.0 / (cnt + 1));
        step = Math.max(step, 4);
        double slide = Math.abs((idx - (cnt - 1) / 2.0) * step);
        if (slide < 0.5) {
            return new double[] {0};
        }
        return idx < (cnt - 1) / 2.0
                ? new double[] {-slide, 0}
                : new double[] {0, slide};
    }

    /**
     * 端口沿边量化：只把锚点沿它所在的那条边吸附到最近的网格线，
     * 法向坐标精确留在节点边界上（Wybrow 正交可见图的端口约定，
     * 也是 libavoid 端口必须落在 shape 边界上的同一口径）。
     *
     * <p>2026-10-17 实测教训（用户口径「箭头到最后还要折一下」）：
     * 不量化时锚点常落在两条网格线之间（真实 y=66、网格 60/80），
     * A* 只认网格坐标、对「离锚点还差 6px」一无所知，末端必然补出
     * 「横 20 → 竖 6 → 横 20」的补偿折弯。量化后锚点与网格对齐，
     * 首尾 stub 天然是直的。
     *
     * <p>吸附后若越出该边（留 8px 给框角圆头）就放弃量化、退回原锚点
     * —— 宁可留折弯也不把线接到框角外面。
     */
    private static double[] quantizeAnchor(
            Grid grid, ScenePlanner.Node n, int side, double[] a) {
        boolean alongX = side == 0 || side == 2; // 左/右边：量化 y
        double snapped = alongX
                ? grid.yOf(grid.rowOf(a[1]))
                : grid.xOf(grid.colOf(a[0]));
        double lo = (alongX ? n.y : n.x) + 8;
        double hi = (alongX ? n.y + n.h : n.x + n.w) - 8;
        if (snapped < lo - 1e-9 || snapped > hi + 1e-9) {
            return a;
        }
        return alongX
                ? new double[] {a[0], snapped}
                : new double[] {snapped, a[1]};
    }

    /**
     * 指定边的锚点：side 0=左 1=下 2=右 3=上，返回 {x, y}，
     * 滑动量沿边铺开并夹在边内（留 8px 给框角圆头）。
     */
    private static double[] sideAnchorAt(
            ScenePlanner.Node n, int side, double slide) {
        double cx = n.x + n.w / 2;
        double cy = n.y + n.h / 2;
        switch (side) {
            case 0: // 左边，法向 (-1,0)，沿边滑动 = y 偏移
                return new double[] {n.x,
                        clamp(n.y + 8, n.y + n.h - 8, cy + slide)};
            case 1: // 下边，法向 (0,+1)
                return new double[] {
                        clamp(n.x + 8, n.x + n.w - 8, cx + slide),
                        n.y + n.h};
            case 2: // 右边，法向 (+1,0)
                return new double[] {n.x + n.w,
                        clamp(n.y + 8, n.y + n.h - 8, cy + slide)};
            case 3: // 上边，法向 (0,-1)
                return new double[] {
                        clamp(n.x + 8, n.x + n.w - 8, cx + slide),
                        n.y};
            default:
                return null;
        }
    }

    private static double clamp(double lo, double hi, double v) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 一个 (源锚点, 目标锚点) 组合的 A* 搜索，返回含锚点的折线。
     *
     * <p>straightStub=true 时强制「两端 stub 必须是直的」（R3）：起点
     * 第一步只能沿法向外走；进入终点格的那一步必须与到达方向同向
     * （连续两步同向 + 锚点已沿边量化 ⇒ 末段 40px 内不可能出现折点）。
     * 贴框绕行的组合会因此搜不到解，调用方按组合回退到 false 再搜一次。
     */
    private static List<double[]> astarOne(
            Grid grid, List<ScenePlanner.Node> nodes,
            double[] sa, int sSide, double[] ea, int eSide,
            boolean straightStub) {
        int sDir = SIDE_TO_DIR[sSide];
        int eDir = SIDE_TO_DIR[eSide];
        int sCol = grid.colOf(sa[0]) + DIR_DX[sDir];
        int sRow = grid.rowOf(sa[1]) + DIR_DY[sDir];
        // 网格终点 = 目标锚点法向外一步；到达方向 = 法向反向（朝框走）
        int eCol = grid.colOf(ea[0]) + DIR_DX[eDir];
        int eRow = grid.rowOf(ea[1]) + DIR_DY[eDir];
        if (!grid.inGrid(sCol, sRow) || !grid.inGrid(eCol, eRow)) {
            return null;
        }
        if (grid.blocked(sCol, sRow, nodes)
                || grid.blocked(eCol, eRow, nodes)) {
            return null;
        }
        int cols = grid.cols;
        int rows = grid.rows;
        int total = cols * rows * 4;
        double[] dist = new double[total];
        int[] prev = new int[total];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        // state = (row * cols + col) * 4 + dir
        int startState = (sRow * cols + sCol) * 4 + sDir;
        int goalState = (eRow * cols + eCol) * 4 + (eDir + 2) % 4;
        dist[startState] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<>(
                (a, b) -> Double.compare(a[0], b[0]));
        open.add(new double[] {heuristic(grid, sCol, sRow, ea),
                startState});
        int loops = 0;
        while (!open.isEmpty() && loops < 30_000) {
            double[] top = open.poll();
            int state = (int) top[1];
            int col = (state / 4) % cols;
            int row = (state / 4) / cols;
            if (top[0] > dist[state]
                    + heuristic(grid, col, row, ea) + 1e-9) {
                continue; // 过期条目（f = dist + h，dist 改进过则 f 偏大）
            }
            if (state == goalState) {
                break;
            }
            loops++;
            int dir = state % 4;
            double base = dist[state];
            for (int nd = 0; nd < 4; nd++) {
                if (nd == (dir + 2) % 4) {
                    continue; // 禁止掉头
                }
                if (straightStub && state == startState && nd != sDir) {
                    continue; // 出端 stub 必须直着出去
                }
                int nCol = col + DIR_DX[nd];
                int nRow = row + DIR_DY[nd];
                if (straightStub && nCol == eCol && nRow == eRow
                        && nd == (eDir + 2) % 4 && dir != nd) {
                    continue; // 入端最后两步同向，框前不留折弯
                }
                if (!grid.inGrid(nCol, nRow)
                        || grid.blocked(nCol, nRow, nodes)) {
                    continue;
                }
                double step = COST_STEP
                        + (nd == dir ? 0 : COST_TURN)
                        + (grid.occupied(nCol, nRow) ? COST_OCCUPIED : 0)
                        + backtrack(grid, col, row, nCol, nRow, ea)
                                * COST_BACKTRACK;
                int nState = (nRow * cols + nCol) * 4 + nd;
                double candidate = base + step;
                if (candidate < dist[nState] - 1e-9) {
                    dist[nState] = candidate;
                    prev[nState] = state;
                    open.add(new double[] {
                            candidate + heuristic(grid, nCol, nRow, ea),
                            nState});
                }
            }
        }
        if (Double.isInfinite(dist[goalState])) {
            return null;
        }
        // 回溯格子路径
        List<int[]> cells = new ArrayList<>();
        for (int s = goalState; s >= 0; s = prev[s]) {
            cells.add(new int[] {(s / 4) % cols, (s / 4) / cols});
            if (s == startState) {
                break;
            }
        }
        Collections.reverse(cells);
        List<double[]> polyline = new ArrayList<>();
        polyline.add(new double[] {sa[0], sa[1]});
        for (int[] c : cells) {
            polyline.add(new double[] {grid.xOf(c[0]), grid.yOf(c[1])});
        }
        polyline.add(new double[] {ea[0], ea[1]});
        return polyline;
    }

    /** 曼哈顿启发函数（可采纳：真实代价 ≥ 直线距离） */
    private static double heuristic(
            Grid grid, int col, int row, double[] target) {
        return Math.abs(grid.xOf(col) - target[0])
                + Math.abs(grid.yOf(row) - target[1]);
    }

    /**
     * 回退判定：这一步是否沿着「远离目标」的方向前进（可采纳，
     * 不会高估真实代价 —— 目标锚点 ea 固定，越靠近它距离只会越小）。
     *
     * <p>口径：比较两端点到目标 ea 的曼哈顿距离，只惩罚「变远」的
     * 那一步。值 = 1（远 1 格）或 0，配合 COST_BACKTRACK=3  ⇒
     * 绕一大圈（4 步回退 = 12）远贵于 1 折（60）之外的任何直线绕行，
     * 但真需要「先横跨再回头」的场景仍可达成（不是硬禁止）。
     */
    private static int backtrack(
            Grid grid, int col, int row, int nCol, int nRow, double[] ea) {
        double ex = ea[0];
        double ey = ea[1];
        double before = Math.abs(grid.xOf(col) - ex)
                + Math.abs(grid.yOf(row) - ey);
        double after = Math.abs(grid.xOf(nCol) - ex)
                + Math.abs(grid.yOf(nRow) - ey);
        return after > before + 1e-9 ? 1 : 0;
    }

    /** 折线总代价：长度 + 拐弯数（占用代价已在搜索期计过，不重复计） */
    private static double pathCost(List<double[]> path) {
        double cost = 0;
        for (int i = 1; i < path.size(); i++) {
            double[] a = path.get(i - 1);
            double[] b = path.get(i);
            cost += Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
            if (i >= 2) {
                double[] p = path.get(i - 2);
                boolean straight =
                        (Math.abs(p[0] - a[0]) < 0.5)
                                == (Math.abs(a[0] - b[0]) < 0.5)
                        && (Math.abs(p[1] - a[1]) < 0.5)
                                == (Math.abs(a[1] - b[1]) < 0.5);
                if (!straight) {
                    cost += COST_TURN;
                }
            }
        }
        return cost;
    }

    /** 合并共线相邻段：A* 逐格路径 → 干净的少量拐点折线 */
    private static List<double[]> mergeCollinear(List<double[]> pts) {
        if (pts.size() <= 2) {
            return pts;
        }
        List<double[]> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            double[] a = out.get(out.size() - 1);
            double[] b = pts.get(i);
            double[] c = pts.get(i + 1);
            boolean sameX = Math.abs(a[0] - b[0]) < 0.5
                    && Math.abs(b[0] - c[0]) < 0.5;
            boolean sameY = Math.abs(a[1] - b[1]) < 0.5
                    && Math.abs(b[1] - c[1]) < 0.5;
            if (!sameX && !sameY) {
                out.add(b);
            }
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }
}
