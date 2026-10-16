package io.github.nihaoljx.flowchart.graph.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * 折线几何后处理：把一条折线（相对坐标，首点通常为 [0,0]）规整成
 * ① 全正交（每段横或竖，斜线段拆成 L 形直角弯）② 最少拐弯（去重复点、
 * 合并共线冗余弯）。
 *
 * 只动中段路由，首末点（锚点）不动 —— 这样配合前端
 * {@code sceneAdapter.snapArrowEndsToAnchors} 把首末点吸附到图形边缘后，
 * 整条线仍是正交的，不会出现「斜线段 / 钩子 + 斜线」那种怪形状。
 *
 * 用法：在 {@code ElkLayoutEngine.writeBackEdgeRoutes} 把 route 写回箭头
 * points 之前调用，覆盖所有生成的箭头（submit_spec 路径坐标全由布局引擎算）。
 *
 * 为什么不放在前端：后端拥有完整坐标，是「线形真相」的唯一来源；前端只负责
 * 把首末点吸附到图形边缘、保留中段。把几何规整放在后端，规则有一处、可单测、
 * 不依赖渲染器行为。
 */
public final class Polyline {

    /** 坐标比较容差（route 已 round1， duplicates 同一数量级内即可判等） */
    private static final double EPS = 1e-6;

    private Polyline() {
    }

    /**
     * @param route 相对坐标折线，首点通常为 [0,0]
     * @return 规整后的折线（同首点）；点数 < 2 时原样返回
     */
    public static List<double[]> orthogonalizeAndSimplify(
            List<double[]> route) {
        if (route == null || route.size() < 2) {
            return route;
        }
        // 转绝对坐标处理，最后再减回首点
        double ox = route.get(0)[0];
        double oy = route.get(0)[1];
        List<double[]> abs = new ArrayList<>();
        for (double[] p : route) {
            abs.add(new double[] { p[0] - ox, p[1] - oy });
        }
        List<double[]> ortho = splitDiagonals(abs);
        List<double[]> simple = simplify(ortho);
        List<double[]> out = new ArrayList<>();
        for (double[] p : simple) {
            out.add(new double[] { p[0] + ox, p[1] + oy });
        }
        return out;
    }

    /**
     * 斜线段 → 拆成 L 形直角弯。选择「先横后竖 / 先竖后横」以让长边走直线更自然：
     * {@code |dx| >= |dy|} 先横后竖，否则先竖后横。
     */
    private static List<double[]> splitDiagonals(
            List<double[]> pts) {
        List<double[]> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            double[] a = out.get(out.size() - 1);
            double[] b = pts.get(i);
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            if (Math.abs(dx) > EPS && Math.abs(dy) > EPS) {
                // 斜线：插一个直角拐点。两个候选拐点：
                //   C1=(b.x,a.y) 先横后竖；C2=(a.x,b.y) 先竖后横。
                // 默认按长边走直线（|dx|>=|dy| 先横后竖）；但若入段
                // （prev→a）与某候选的首个子段共轴，则改选另一个 ——
                // 否则首子段会与入段原路折返、把刻意绕行塌成直线
                // （实测 monsterArrow [[0,0],[30,0],[30,-430],[-260,0]]：
                //  竖入段 + 先竖后横 ⇒ 拐点落回 (30,0) ⇒ 整段抵消成直线）。
                boolean horizFirst = Math.abs(dx) >= Math.abs(dy);
                if (out.size() >= 2) {
                    double[] prev = out.get(out.size() - 2);
                    boolean incomingHoriz =
                            Math.abs(prev[1] - a[1]) < EPS;
                    boolean incomingVert =
                            Math.abs(prev[0] - a[0]) < EPS;
                    if (incomingHoriz) {
                        horizFirst = false; // 入段水平 ⇒ 先横会折返，改先竖
                    } else if (incomingVert) {
                        horizFirst = true;  // 入段竖直 ⇒ 先竖会折返，改先横
                    }
                }
                double[] corner = horizFirst
                        ? new double[] { b[0], a[1] }   // 先横到 b.x，再竖到 b.y
                        : new double[] { a[0], b[1] };  // 先竖到 b.y，再横到 b.x
                out.add(corner);
            }
            out.add(b);
        }
        return out;
    }

    /**
     * 去重复点 + 合并共线冗余弯：中间点若与相邻点重合，或前后两段同为横/同为竖
     * （即三点共线），则该弯冗余，删除它 —— 视觉路线不变，仅少一个拐点。
     */
    private static List<double[]> simplify(List<double[]> pts) {
        if (pts.size() <= 2) {
            return pts;
        }
        List<double[]> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            double[] prev = out.get(out.size() - 1);
            double[] p = pts.get(i);
            double[] next = pts.get(i + 1);
            if (near(prev, p) || near(p, next)) {
                continue;
            }
            String ax1 = axis(prev, p);
            String ax2 = axis(p, next);
            if (ax1 != null && ax1.equals(ax2)) {
                continue; // 共线，冗余弯
            }
            out.add(p);
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    private static boolean near(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < EPS
                && Math.abs(a[1] - b[1]) < EPS;
    }

    /** 段方向：横 {@code "H"} / 竖 {@code "V"} / 斜 {@code null} */
    private static String axis(double[] a, double[] b) {
        double dx = Math.abs(a[0] - b[0]);
        double dy = Math.abs(a[1] - b[1]);
        if (dy < EPS) {
            return "H";
        }
        if (dx < EPS) {
            return "V";
        }
        return null;
    }
}
