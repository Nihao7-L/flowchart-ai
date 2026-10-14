package io.github.nihaoljx.flowchart.graph.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 几何体检器：只测量，不改一个坐标。
 *
 * 为什么需要它：后端采用 LLM 原生坐标
 * （`scenePlanner.claimFramesOnly` 明写"不改动任何几何"），而
 * GraphValidator 只审结构合法性（类型白名单 / required / 自环 /
 * points 形状 / label 非空 / 宽高 > 0），没有任何一环会看"连线是否穿过
 * 无关图形""两条线是否缠在一起""标签有没有地方站"。实测那张 22 图形 /
 * 21 箭头的电商架构图里 19 对连线交叉、18 处穿身，全部静默通过 ——
 * 结构合法即放行。
 *
 * 为什么不是"后端帮它摆"：后端重排已被否掉
 * （「尺寸抹平 + 竖排重排」会把图收成一根窄条），且模型看不见渲染结果、
 * 由代码替它改坐标风险更高。所以这里只做体检：把量出来的事实翻译成
 * ValidationIssue，塞进既有 issues 列表，由已经存在的
 * 「校验 → 修正轮」管道带回给 LLM，让它自己重新摆。
 * 后端在这里的角色是卷尺，不是手。
 *
 * 判定口径（当前生效）：
 *
 * - 障碍物只认"有名字 / 被引用的框"。只有当图形
 *       带 label 或 被某条 arrow 绑定时才算障碍物：
 *       "有名字的框"和"连线接得到的框"才构成"不该被穿过去"的语义。
 *       插画里的图形彼此叠压是造型，不是缺陷（如猫的耳朵/眼睛不会报穿身）。
 *       分区容器（见 isContainer）不算墙——把整列框起来的浅色底板
 *       是背景，连线在自家分区里走不算穿身。
 *
 * - 无 points 的箭头按前端口径算，不按中心连线算。
 *       前端 `withArrowGeometry` 的纪律是
 *       `hasXY && hasPoints` 原样透传、否则取两端包围盒朝向对方的
 *       边缘交点再连直线。中心连线比真实落笔长半个图形，会把
 *       "贴着边走"的正常连线误报成穿身。
 *
 * - 阈值按比例。三条判据都是"绝对数兜底 + 占比达标"双条件
 *       （见 `CROSSING_MIN/RATIO` 等）：小图按绝对数会漏报、
 *       大图会误报，故引入占比门槛。
 *
 * 端点内插：模型给已绑定箭头写
 * `points` 时，会把端点算成"目标图形的中心"而不是"边缘"（它看不见
 * 画布，纯靠脑算）。渲染层原样透传这种坐标 ⇒ 箭头插进框里、正好压住
 * 框内 label。这类"显式给了坐标但端点落在绑定图形内部"的情况单列一条，
 * 因为它与"连线穿过了别人的框"是两种病、改法也不同。
 *
 * 任何异常一律吞掉并返回空列表：体检是锦上添花，不能变成新的
 * 故障点（与 SceneSanitizer / SceneDeduper 同一条纪律）。
 */
@Component
public class GeometryAudit {

    private static final Logger LOG =
            LoggerFactory.getLogger(GeometryAudit.class);

    /** 有包围盒的图形类型（几何判定只认它们） */
    private static final Set<String> SHAPE_TYPES =
            Set.of("rectangle", "ellipse", "diamond");

    /** 连线交叉：绝对数兜底 */
    public static final int CROSSING_MIN = 3;

    /** 连线交叉：占比门槛（交叉对数 / 连线数） */
    public static final double CROSSING_RATIO = 0.33;

    /** 连线穿身：绝对数兜底 */
    public static final int THROUGH_MIN = 3;

    /** 连线穿身：占比门槛（穿身处数 / 连线数） */
    public static final double THROUGH_RATIO = 0.25;

    /** 标签被压：绝对数兜底 */
    public static final int LABEL_MIN = 2;

    /** 标签被压：占比门槛 */
    public static final double LABEL_RATIO = 0.20;

    /** 每类几何问题最多点名几条（避免修正 prompt 过长） */
    private static final int NAMED_CAP = 8;

    /** 标签宽度估算用的每字宽度（4 个汉字实测约 64px） */
    private static final double LABEL_CHAR_WIDTH = 16;

    /** bbox 内缩量：擦边不算穿身，避免把"贴着边缘走"误报 */
    private static final double SHRINK = 4;

    /** 端点离边界多远才算"插进去了"（小于它算正常吸附） */
    private static final double INSET_TOLERANCE = 3;

    /** Liang-Barsky 用的四个边界方向数 */
    private static final int EDGES = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 体检一份场景 JSON。
     *
     * @param json 校验前的场景 JSON（与 GraphValidator 看到的是同一份）
     * @return 0 或 1 条 ValidationIssue；永不抛异常
     */
    public List<ValidationIssue> audit(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode elements = root.get("elements");
            if (elements == null || !elements.isArray()) {
                return List.of();
            }

            // ---- 第一遍：图形（带 isLabeled 标记）、箭头 ----
            Map<String, Box> boxes = new LinkedHashMap<>();
            List<JsonNode> arrows = new ArrayList<>();
            for (JsonNode element : elements) {
                String id = element.path("id").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                String type = element.path("type").asText("");
                if (SHAPE_TYPES.contains(type)) {
                    double x = element.path("x").asDouble(Double.NaN);
                    double y = element.path("y").asDouble(Double.NaN);
                    double w = element.path("width").asDouble(Double.NaN);
                    double h = element.path("height").asDouble(Double.NaN);
                    if (isPositive(w) && isPositive(h)
                            && Double.isFinite(x) && Double.isFinite(y)) {
                        boxes.put(id, new Box(x, y, w, h, isLabeled(element),
                                labelText(element)));
                    }
                } else if ("arrow".equals(type)) {
                    arrows.add(element);
                }
            }
            // 只有 arrow 算"连线"：line 的契约定位是造型原语（伪 3D 拼面 /
            // 手绘轮廓），它互相穿过、盖住图形都是画面本来的样子。
            if (arrows.isEmpty()) {
                return List.of();
            }

            // ---- 第二遍：路径 + 端点集合 ----
            List<Path> paths = new ArrayList<>();
            Set<String> boundIds = new HashSet<>();
            for (JsonNode arrow : arrows) {
                Path path = pathOf(arrow, boxes);
                if (path != null) {
                    paths.add(path);
                    boundIds.addAll(path.ends());
                }
            }
            if (paths.isEmpty()) {
                return List.of();
            }

            // ---- 障碍物 = 节点图形 − 分区容器 ----
            // 节点 = 带 label 或 被箭头绑定；分区容器 = 包围盒吞下了别的节点
            // （仪表盘的"门禁/停车/…"浅色底板）。连线在自家分区里走是正常的，不算穿身。
            Map<String, Box> obstacles = new LinkedHashMap<>();
            for (Map.Entry<String, Box> entry : boxes.entrySet()) {
                boolean node = entry.getValue().labeled()
                        || boundIds.contains(entry.getKey());
                if (node && !isContainer(entry.getKey(), entry.getValue(), boxes)) {
                    obstacles.put(entry.getKey(), entry.getValue());
                }
            }

            int crossings = countCrossings(paths);
            int through = countThrough(paths, obstacles);
            int pressed = countPressedLabels(paths, obstacles);
            int inset = countInsetEndpoints(paths, boxes);

            int n = paths.size();

            // 三、逐类点名：先按阈值决定"值不值得报"（防小图噪声），
            // 一旦要报就把具体 offender 逐个点名 + 给局部改法，
            // 让修正轮局部改，而不是整张重摆。
            List<ValidationIssue> result = new ArrayList<>();

            // 3.1 连线交叉：点名每一对交叉的连线
            if (crossings >= CROSSING_MIN && crossings >= n * CROSSING_RATIO) {
                int named = 0;
                for (int i = 0; i < paths.size() && named < NAMED_CAP; i++) {
                    for (int j = i + 1;
                            j < paths.size() && named < NAMED_CAP; j++) {
                        Path a = paths.get(i), b = paths.get(j);
                        if (pathsCross(a, b)) {
                            double[] pt = firstIntersection(a, b);
                            String at = pt == null ? ""
                                    : String.format(" 于 (%.0f,%.0f)",
                                            pt[0], pt[1]);
                            result.add(new ValidationIssue(
                                    "geometry.crossing",
                                    "连线 " + arrowDesc(a) + " 与连线 "
                                            + arrowDesc(b) + " 交叉" + at,
                                    "请让 " + a.id() + " 改用折线（多写几个 points）"
                                            + "从 " + b.id() + " 的一侧绕开，"
                                            + "不要两点直连"));
                            named++;
                        }
                    }
                }
                if (crossings > named) {
                    result.add(new ValidationIssue("geometry.crossing",
                            "另有 " + (crossings - named)
                                    + " 对连线交叉未逐一列出",
                            "同样用折线绕开"));
                }
            }

            // 3.2 连线穿身：点名每一处"穿过无关图形"的连线
            if (through >= THROUGH_MIN && through >= n * THROUGH_RATIO) {
                int named = 0;
                outer:
                for (Path path : paths) {
                    for (Map.Entry<String, Box> entry
                            : obstacles.entrySet()) {
                        if (named >= NAMED_CAP) {
                            break outer;
                        }
                        if (path.ends().contains(entry.getKey())) {
                            continue;
                        }
                        if (touches(path, entry.getValue())) {
                            result.add(new ValidationIssue(
                                    "geometry.through",
                                    "连线 " + arrowDesc(path)
                                            + " 穿过了无关图形 "
                                            + boxDesc(entry.getKey(),
                                            entry.getValue()),
                                    "让 " + path.id() + " 绕开 "
                                            + entry.getKey()
                                            + "，或贴着它的边缘走"));
                            named++;
                        }
                    }
                }
                if (through > named) {
                    result.add(new ValidationIssue("geometry.through",
                            "另有 " + (through - named)
                                    + " 处穿身未逐一列出",
                            "同样让对应连线绕开被穿图形"));
                }
            }

            // 3.3 标签被压
            if (pressed >= LABEL_MIN && pressed >= n * LABEL_RATIO) {
                int named = 0;
                for (Path path : paths) {
                    if (named >= NAMED_CAP) {
                        break;
                    }
                    String label = path.label();
                    if (label.isBlank()) {
                        continue;
                    }
                    for (Map.Entry<String, Box> entry
                            : obstacles.entrySet()) {
                        if (path.ends().contains(entry.getKey())) {
                            continue;
                        }
                        if (midInBox(path, entry.getValue())) {
                            result.add(new ValidationIssue(
                                    "geometry.labelPressed",
                                    "连线 " + path.id() + " 的标签「" + label
                                            + "」被图形 "
                                            + boxDesc(entry.getKey(),
                                            entry.getValue())
                                            + " 压住",
                                    "把 " + path.id()
                                            + " 的标签移到路径中点两侧空档，"
                                            + "或移动该连线避开 "
                                            + entry.getKey()));
                            named++;
                            break;
                        }
                    }
                }
                if (pressed > named) {
                    result.add(new ValidationIssue("geometry.labelPressed",
                            "另有 " + (pressed - named)
                                    + " 处标签被压未逐一列出",
                            "同样把对应连线标签移到空档"));
                }
            }

            // 3.4 端点插进绑定图形内部
            if (inset > 0) {
                int named = 0;
                for (Path path : paths) {
                    if (named >= NAMED_CAP) {
                        break;
                    }
                    if (!path.explicit()) {
                        continue;
                    }
                    boolean head = isInside(path.points().get(0),
                            boxes.get(path.startId()));
                    boolean tail = isInside(
                            path.points().get(path.points().size() - 1),
                            boxes.get(path.endId()));
                    if (head || tail) {
                        String which = head && tail ? "首尾"
                                : head ? "首" : "尾";
                        result.add(new ValidationIssue(
                                "geometry.insetEndpoint",
                                "箭头 " + path.id() + " 的" + which
                                        + "端点插在绑定图形 "
                                        + (head ? path.startId()
                                        : path.endId())
                                        + " 内部，会压住框内文字",
                                "去掉 " + path.id()
                                        + " 显式写的 points，只保留 start/end "
                                        + "由渲染器接到框边；"
                                        + "真要转弯再用 ≥3 个点"));
                        named++;
                    }
                }
                if (inset > named) {
                    result.add(new ValidationIssue("geometry.insetEndpoint",
                            "另有 " + (inset - named)
                                    + " 处端点内插未逐一列出",
                            "同样去掉对应箭头的显式 points"));
                }
            }

            if (result.isEmpty()) {
                return List.of();
            }
            LOG.info("几何体检未通过：交叉{} / 穿身{} / 标签被压{} / 端点内插{}"
                            + "（节点 {} 个 / 连线 {} 条，点名 {} 条 issue）",
                    crossings, through, pressed, inset,
                    obstacles.size(), n, result.size());
            return result;
        } catch (Exception e) {
            // 体检是锦上添花，绝不能变成新的故障点
            LOG.debug("几何体检跳过（{}）", e.toString());
            return List.of();
        }
    }

    private static boolean isLabeled(JsonNode element) {
        JsonNode label = element.get("label");
        if (label == null || label.isNull()) {
            return false;
        }
        if (label.isTextual()) {
            return !label.asText().isBlank();
        }
        return !label.path("text").asText("").isBlank();
    }

    /**
     * 分区容器（背景底板）识别。
     *
 * 判据：这个图形的包围盒吞下了另一个带 label 的图形，且自身
     * 至少在一个方向上更大 —— 那它不是节点，是"门禁/停车/能耗"这种把整列
     * 框起来的分区底板。实测 h_long（智慧园区大屏）里 5 块底板各自吞下
     * 一个分组头和三个子项，20 条箭头有 21 处"穿身"是踩在底板上 ——
     * 那是分区该有的样子，不是缺陷。
     *
 * 刻意要求"被吞下的那个带 label"：否则一个刚好框住几个纯装饰小圆的
     * 装饰框会被误判成容器。
     */
    private static boolean isContainer(String selfId, Box candidate,
            Map<String, Box> boxes) {
        for (Map.Entry<String, Box> entry : boxes.entrySet()) {
            if (entry.getKey().equals(selfId)) {
                continue;
            }
            Box other = entry.getValue();
            if (!other.labeled()) {
                continue;
            }
            boolean encloses = candidate.x() <= other.x()
                    && candidate.y() <= other.y()
                    && candidate.x() + candidate.w() >= other.x() + other.w()
                    && candidate.y() + candidate.h() >= other.y() + other.h();
            if (encloses && (candidate.w() > other.w() || candidate.h() > other.h())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把一条箭头还原成绝对坐标路径，尽量贴近渲染层真实落笔。
     *
 * 两种形态：
     *
 * - 显式给了 points（`size >= 2`）→ 用 `x/y + points`
     *       还原。前端在 `hasXY && hasPoints` 时原样透传，
     *       所以这就是屏幕上真正画出来的线。
     *
 * - 没给 points → 复刻前端 `edgePointTowards`：
     *       取两端包围盒朝向对方的边缘交点。用中心连线当近似，
     *       比真实落笔长半个图形，会误报大量穿身。
     *
     */
    private Path pathOf(JsonNode arrow, Map<String, Box> boxes) {
        String startId = arrow.path("start").path("id").asText("");
        String endId = arrow.path("end").path("id").asText("");
        Set<String> ends = new HashSet<>();
        if (!startId.isEmpty()) {
            ends.add(startId);
        }
        if (!endId.isEmpty()) {
            ends.add(endId);
        }

        List<double[]> points = new ArrayList<>();
        JsonNode raw = arrow.get("points");
        boolean explicit = false;
        if (raw != null && raw.isArray() && raw.size() >= 2) {
            double ox = arrow.path("x").asDouble(Double.NaN);
            double oy = arrow.path("y").asDouble(Double.NaN);
            if (!Double.isFinite(ox) || !Double.isFinite(oy)) {
                Box from = boxes.get(startId);
                ox = from == null ? 0 : from.cx();
                oy = from == null ? 0 : from.cy();
            }
            for (JsonNode point : raw) {
                if (point.isArray() && point.size() >= 2
                        && point.get(0).isNumber()
                        && point.get(1).isNumber()) {
                    points.add(new double[]{
                            ox + point.get(0).asDouble(),
                            oy + point.get(1).asDouble()});
                }
            }
            explicit = points.size() >= 2;
        }
        if (!explicit) {
            points.clear();
            Box from = boxes.get(startId);
            Box to = boxes.get(endId);
            if (from == null || to == null) {
                return null;
            }
            points.add(edgePointTowards(from, to.cx(), to.cy()));
            points.add(edgePointTowards(to, from.cx(), from.cy()));
        }
        return new Path(arrow.path("id").asText(""), points, ends,
                arrow.path("label").path("text").asText(""),
                explicit, startId, endId);
    }

    /**
     * 元素包围盒边界上、朝着 `(tx, ty)` 的那个交点。
     * 与前端 `sceneAdapter.edgePointTowards` 同一算法；
     * 目标落在元素内部（或元素无尺寸）时退回中心点。
     */
    private static double[] edgePointTowards(Box box, double tx, double ty) {
        double cx = box.cx();
        double cy = box.cy();
        double halfW = box.w() / 2;
        double halfH = box.h() / 2;
        if (halfW <= 0 && halfH <= 0) {
            return new double[]{cx, cy};
        }
        double dx = tx - cx;
        double dy = ty - cy;
        if (dx == 0 && dy == 0) {
            return new double[]{cx, cy};
        }
        double scaleX = dx != 0 ? halfW / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double scaleY = dy != 0 ? halfH / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double scale = Math.min(scaleX, scaleY);
        if (!Double.isFinite(scale) || scale >= 1) {
            return new double[]{cx, cy};
        }
        return new double[]{cx + dx * scale, cy + dy * scale};
    }

    private int countCrossings(List<Path> paths) {
        int count = 0;
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                if (pathsCross(paths.get(i), paths.get(j))) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean pathsCross(Path a, Path b) {
        // 共享端点的两条线（同一个图形的两条出边）不算"缠在一起"
        for (String id : a.ends()) {
            if (b.ends().contains(id)) {
                return false;
            }
        }
        for (int i = 0; i + 1 < a.points().size(); i++) {
            for (int j = 0; j + 1 < b.points().size(); j++) {
                if (segmentsCross(a.points().get(i), a.points().get(i + 1),
                        b.points().get(j), b.points().get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 规范相交（严格跨越）：共线 / 端点相接不算交叉 */
    private static boolean segmentsCross(double[] p1, double[] p2,
            double[] q1, double[] q2) {
        double d1 = side(p1, p2, q1);
        double d2 = side(p1, p2, q2);
        double d3 = side(q1, q2, p1);
        double d4 = side(q1, q2, p2);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double side(double[] a, double[] b, double[] p) {
        return (b[0] - a[0]) * (p[1] - a[1])
                - (b[1] - a[1]) * (p[0] - a[0]);
    }

    private int countThrough(List<Path> paths, Map<String, Box> obstacles) {
        int count = 0;
        for (Path path : paths) {
            for (Map.Entry<String, Box> entry : obstacles.entrySet()) {
                if (path.ends().contains(entry.getKey())) {
                    continue;
                }
                if (touches(path, entry.getValue())) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean touches(Path path, Box box) {
        for (int i = 0; i + 1 < path.points().size(); i++) {
            if (segmentHitsBox(path.points().get(i),
                    path.points().get(i + 1), box)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 端点内插：箭头显式给了 points，但首/末点落在绑定图形的内部
     * （离最近边界 > INSET_TOLERANCE）。
     *
 * 为什么单列：这类箭头在屏幕上的表现是"从框中间穿出来 / 插进框里"，
     * 必然与框内 label 叠在一起。自算路径（没给 points）不会出现这种情况
     * —— 前端取的就是边界交点。
     */
    private int countInsetEndpoints(List<Path> paths, Map<String, Box> boxes) {
        int count = 0;
        for (Path path : paths) {
            if (!path.explicit()) {
                continue;
            }
            if (isInside(path.points().get(0), boxes.get(path.startId()))) {
                count++;
            }
            if (isInside(path.points().get(path.points().size() - 1),
                    boxes.get(path.endId()))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isInside(double[] p, Box box) {
        if (box == null) {
            return false;
        }
        return p[0] > box.x() + INSET_TOLERANCE
                && p[0] < box.x() + box.w() - INSET_TOLERANCE
                && p[1] > box.y() + INSET_TOLERANCE
                && p[1] < box.y() + box.h() - INSET_TOLERANCE;
    }

    /** Liang-Barsky 裁剪：线段与内缩后的矩形是否有交集 */
    private static boolean segmentHitsBox(double[] a, double[] b, Box box) {
        double left = box.x() + SHRINK;
        double right = box.x() + box.w() - SHRINK;
        double top = box.y() + SHRINK;
        double bottom = box.y() + box.h() - SHRINK;
        if (right <= left || bottom <= top) {
            return false;
        }
        double t0 = 0;
        double t1 = 1;
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a[0] - left, right - a[0], a[1] - top, bottom - a[1]};
        for (int i = 0; i < EDGES; i++) {
            if (p[i] == 0) {
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
        return true;
    }

    private int countPressedLabels(List<Path> paths, Map<String, Box> obstacles) {
        int count = 0;
        for (Path path : paths) {
            String label = path.label();
            if (label.isBlank()) {
                continue;
            }
            double[] mid = midpointOf(path);
            double half = label.length() * LABEL_CHAR_WIDTH / 2;
            for (Map.Entry<String, Box> entry : obstacles.entrySet()) {
                if (path.ends().contains(entry.getKey())) {
                    continue;
                }
                Box box = entry.getValue();
                if (mid[0] + half > box.x()
                        && mid[0] - half < box.x() + box.w()
                        && mid[1] > box.y()
                        && mid[1] < box.y() + box.h()) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** 路径中点：按累计长度取一半处（折线也准） */
    private static double[] midpointOf(Path path) {
        List<double[]> points = path.points();
        double total = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            total += lengthOf(points.get(i), points.get(i + 1));
        }
        double half = total / 2;
        double walked = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            double segment = lengthOf(points.get(i), points.get(i + 1));
            if (walked + segment >= half) {
                double ratio = segment == 0 ? 0 : (half - walked) / segment;
                return new double[]{
                        points.get(i)[0] + ratio
                                * (points.get(i + 1)[0] - points.get(i)[0]),
                        points.get(i)[1] + ratio
                                * (points.get(i + 1)[1] - points.get(i)[1])};
            }
            walked += segment;
        }
        return points.get(points.size() - 1);
    }

    private static double lengthOf(double[] a, double[] b) {
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    private static boolean isPositive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    /** 取图形的 label 文本（点名用，空 label 返回空串） */
    private static String labelText(JsonNode element) {
        JsonNode label = element.get("label");
        if (label == null || label.isNull()) {
            return "";
        }
        if (label.isTextual()) {
            return label.asText("");
        }
        return label.path("text").asText("");
    }

    /** 连线点名：id + 标签文字（无标签只给 id） */
    private static String arrowDesc(Path path) {
        String label = path.label();
        String txt = label.isBlank() ? "" : "「" + label + "」";
        return "「" + path.id() + "」" + txt;
    }

    /** 图形点名：id + label 文字（无标签只给 id） */
    private static String boxDesc(String id, Box box) {
        String label = box.label();
        String txt = label.isBlank() ? "" : "「" + label + "」";
        return "「" + id + "」" + txt;
    }

    /** 路径中点是否落在某个图形包围盒内（标签被压判定） */
    private static boolean midInBox(Path path, Box box) {
        double[] mid = midpointOf(path);
        return mid[0] >= box.x() && mid[0] <= box.x() + box.w()
                && mid[1] >= box.y() && mid[1] <= box.y() + box.h();
    }

    /** 两条连线首个严格交叉点；不交叉返回 null */
    private static double[] firstIntersection(Path a, Path b) {
        for (int i = 0; i + 1 < a.points().size(); i++) {
            for (int j = 0; j + 1 < b.points().size(); j++) {
                double[] pt = intersectPoint(a.points().get(i),
                        a.points().get(i + 1),
                        b.points().get(j), b.points().get(j + 1));
                if (pt != null) {
                    return pt;
                }
            }
        }
        return null;
    }

    /** 两线段严格交叉的相交点；共线 / 相接 / 平行返回 null */
    private static double[] intersectPoint(double[] p1, double[] p2,
            double[] q1, double[] q2) {
        double d1 = side(p1, p2, q1);
        double d2 = side(p1, p2, q2);
        double d3 = side(q1, q2, p1);
        double d4 = side(q1, q2, p2);
        if (!(((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0)))) {
            return null;
        }
        double denom = (p2[0] - p1[0]) * (q2[1] - q1[1])
                - (p2[1] - p1[1]) * (q2[0] - q1[0]);
        if (Math.abs(denom) < 1e-9) {
            return null;
        }
        double t = ((q1[0] - p1[0]) * (q2[1] - q1[1])
                - (q1[1] - p1[1]) * (q2[0] - q1[0])) / denom;
        return new double[]{p1[0] + t * (p2[0] - p1[0]),
                p1[1] + t * (p2[1] - p1[1])};
    }

    /** 图形包围盒（轴对齐）+ 是否带 label + label 文本（点名用） */
    private record Box(double x, double y, double w, double h,
            boolean labeled, String label) {
        double cx() {
            return x + w / 2;
        }

        double cy() {
            return y + h / 2;
        }
    }

    /** 一条连线的绝对坐标路径 + 自身 id + 端点 id + 标签文字 */
    private record Path(String id, List<double[]> points, Set<String> ends,
            String label, boolean explicit, String startId, String endId) {
    }
}
