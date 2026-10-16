package io.github.nihaoljx.flowchart.critique;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 确定性几何批判器（无需浏览器/渲染）：拿到布局后的 Excalidraw 元素，用几何规则
 * 找出「渲染后会难看/读不懂」的结构问题，返回**结构化** issue（每条都点名目标
 * 元素 + 给出 spec 级的改法）。这是给 LLM「装眼睛」的低成本实现：不依赖视觉
 * 模型，先把能机器判定的几何问题兜住。
 *
 * <p>设计铁律（呼应 D6）：反馈必须是**结构性**的（改 spec 重排/加宽/拉开），
 * 绝不返回像素修补指令。
 */
public final class GeometricCritic implements DiagramCritic {

    /** 形状类元素（有包围盒） */
    private static final List<String> SHAPE_TYPES =
            List.of("rectangle", "ellipse", "diamond", "text");

    /** 单字符近似像素宽（Excalidraw 默认 ~16px 字体下的经验值） */
    private static final double CHAR_PX = 7.5;
    /** 节点左右内边距（各算一份） */
    private static final double PAD = 12.0;
    /** 判定「有重叠」的容差：两轴交集都大于该值才算真重叠 */
    private static final double OVERLAP_TOL = 2.0;
    /** 太小的节点（读不清） */
    private static final double TINY_W = 40.0;
    private static final double TINY_H = 20.0;

    @Override
    public List<CritiqueIssue> critique(
            List<Map<String, Object>> elements, String userIntent) {
        List<CritiqueIssue> issues = new ArrayList<>();
        if (elements == null || elements.isEmpty()) {
            return issues;
        }
        List<Box> boxes = new ArrayList<>();
        List<Arrow> arrows = new ArrayList<>();
        for (Map<String, Object> e : elements) {
            String type = asStr(e.get("type"));
            if ("arrow".equals(type)) {
                Arrow a = toArrow(e);
                if (a != null) arrows.add(a);
            } else if (SHAPE_TYPES.contains(type)) {
                Box b = toBox(e);
                if (b != null) boxes.add(b);
            }
        }

        // 1) 重叠
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                Box a = boxes.get(i), b = boxes.get(j);
                double ox = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
                double oy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
                if (ox > OVERLAP_TOL && oy > OVERLAP_TOL) {
                    issues.add(CritiqueIssue.of(a.id, CritiqueIssue.Category.OVERLAP,
                            "节点 " + a.id + " 与 " + b.id + " 相互重叠",
                            "拉开 " + a.id + " 与 " + b.id + " 的间距，或合并二者", 2));
                }
            }
        }
        // 2) 出界（左上为负）
        for (Box b : boxes) {
            if (b.x < -OVERLAP_TOL || b.y < -OVERLAP_TOL) {
                issues.add(CritiqueIssue.of(b.id, CritiqueIssue.Category.OFF_CANVAS,
                        "节点 " + b.id + " 落在画布外（坐标为负）",
                        "把 " + b.id + " 移入画布（坐标 >= 0）", 2));
            }
        }
        // 3) 标签溢出
        for (Box b : boxes) {
            String t = b.text;
            if (t != null && !t.isEmpty() && b.w > 0) {
                double est = t.length() * CHAR_PX;
                if (est > b.w - 2 * PAD) {
                    issues.add(CritiqueIssue.of(b.id, CritiqueIssue.Category.LABEL_OVERFLOW,
                            "节点 " + b.id + " 的文字（" + t.length() + " 字）超出框宽",
                            "加宽 " + b.id + " 或缩写其标签文字", 1));
                }
            }
        }
        // 4) 太小
        for (Box b : boxes) {
            if (b.w < TINY_W || b.h < TINY_H) {
                issues.add(CritiqueIssue.of(b.id, CritiqueIssue.Category.TINY,
                        "节点 " + b.id + " 太小（" + (int) b.w + "x" + (int) b.h + "）不易读",
                        "放大 " + b.id + " 到至少 40x20", 1));
            }
        }
        // 5) 连线交叉（仅统计两端都绑定到节点的箭头，跳过共享端点者）
        for (int i = 0; i < arrows.size(); i++) {
            for (int j = i + 1; j < arrows.size(); j++) {
                Arrow p = arrows.get(i), q = arrows.get(j);
                if (p.start == null || p.end == null
                        || q.start == null || q.end == null) {
                    continue;
                }
                if (p.start.equals(q.start) || p.start.equals(q.end)
                        || p.end.equals(q.start) || p.end.equals(q.end)) {
                    continue;
                }
                Box ps = centerOf(boxes, p.start), pe = centerOf(boxes, p.end);
                Box qs = centerOf(boxes, q.start), qe = centerOf(boxes, q.end);
                if (ps != null && pe != null && qs != null && qe != null
                        && segmentsIntersect(ps, pe, qs, qe)) {
                    issues.add(CritiqueIssue.of(p.id, CritiqueIssue.Category.EDGE_CROSSING,
                            "箭头 " + p.id + " 与 " + q.id + " 交叉",
                            "调整布局走向，避免 " + p.id + " 与 " + q.id + " 交叉", 1));
                }
            }
        }
        return issues;
    }

    // ---- 内部模型与解析 ----

    private static final class Box {
        final String id;
        final double x, y, w, h;
        final String text;

        Box(String id, double x, double y, double w, double h, String text) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.text = text;
        }
    }

    private static final class Arrow {
        final String id;
        final String start;
        final String end;

        Arrow(String id, String start, String end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
    }

    private static Box toBox(Map<String, Object> e) {
        String id = asStr(e.get("id"));
        if (id.isEmpty()) {
            return null;
        }
        return new Box(id, num(e, "x", 0), num(e, "y", 0),
                num(e, "width", 0), num(e, "height", 0), textOf(e));
    }

    private static Arrow toArrow(Map<String, Object> e) {
        String id = asStr(e.get("id"));
        if (id.isEmpty()) {
            return null;
        }
        String s = binding(e, "startBinding");
        String en = binding(e, "endBinding");
        if (s == null || en == null) {
            return null;
        }
        return new Arrow(id, s, en);
    }

    private static String binding(Map<String, Object> e, String key) {
        Object o = e.get(key);
        if (o instanceof Map<?, ?> m) {
            Object el = m.get("elementId");
            if (el instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private static Box centerOf(List<Box> boxes, String id) {
        for (Box b : boxes) {
            if (b.id.equals(id)) {
                return b;
            }
        }
        return null;
    }

    private static double num(Map<?, ?> m, String k, double def) {
        Object v = m.get(k);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return def;
    }

    private static String asStr(Object o) {
        return o instanceof String s ? s : "";
    }

    private static String textOf(Map<?, ?> e) {
        Object t = e.get("text");
        if (t instanceof String s && !s.isEmpty()) {
            return s;
        }
        Object label = e.get("label");
        if (label instanceof Map<?, ?> lm) {
            Object lt = lm.get("text");
            if (lt instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    /** 线段 AB 与 CD 是否相交（端点共线近似） */
    private static boolean segmentsIntersect(Box a, Box b, Box c, Box d) {
        double ax = a.x + a.w / 2, ay = a.y + a.h / 2;
        double bx = b.x + b.w / 2, by = b.y + b.h / 2;
        double cx = c.x + c.w / 2, cy = c.y + c.h / 2;
        double dx = d.x + d.w / 2, dy = d.y + d.h / 2;
        return ccw(ax, ay, bx, by, cx, cy) != ccw(ax, ay, bx, by, dx, dy)
                && ccw(cx, cy, dx, dy, ax, ay) != ccw(cx, cy, dx, dy, bx, by);
    }

    private static int ccw(double ax, double ay, double bx, double by,
                           double cx, double cy) {
        double v = (by - ay) * (cx - bx) - (bx - ax) * (cy - by);
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }
}
