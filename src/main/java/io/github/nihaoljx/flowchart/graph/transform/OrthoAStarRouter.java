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

    /** 90° 拐弯代价（JointJS：penalties[90] = step/2） */
    private static final double COST_TURN = COST_STEP / 2;

    /** 踩到已路由边占用格的代价：远大于拐弯 —— 宁可多拐两次弯也不叠线 */
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
                double[] sa = sideAnchorAt(f, sSide, sSlide);
                for (int eSide = 0; eSide < 4; eSide++) {
                    for (double eSlide : eSlides) {
                        double[] ea = sideAnchorAt(t, eSide, eSlide);
                        List<double[]> path = astarOne(
                                grid, nodes, sa, sSide, ea, eSide);
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
        return bestPolyline == null ? null : mergeCollinear(bestPolyline);
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

    /** 一个 (源锚点, 目标锚点) 组合的 A* 搜索，返回含锚点的折线 */
    private static List<double[]> astarOne(
            Grid grid, List<ScenePlanner.Node> nodes,
            double[] sa, int sSide, double[] ea, int eSide) {
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
                int nCol = col + DIR_DX[nd];
                int nRow = row + DIR_DY[nd];
                if (!grid.inGrid(nCol, nRow)
                        || grid.blocked(nCol, nRow, nodes)) {
                    continue;
                }
                double step = COST_STEP
                        + (nd == dir ? 0 : COST_TURN)
                        + (grid.occupied(nCol, nRow) ? COST_OCCUPIED : 0);
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
