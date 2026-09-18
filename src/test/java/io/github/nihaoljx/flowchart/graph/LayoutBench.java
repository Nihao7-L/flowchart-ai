package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 布局质量基准（v2-38）
 *
 * <p>把同一批 fixture 灌进不同的 {@link LayoutEngine}，输出同一套指标，
 * 用数字回答"哪个布局更好" —— 换引擎时不再靠肉眼和口味。
 *
 * <p>类名故意不带 {@code Test}，所以常规 {@code mvn test} 不会执行它，
 * 也不会给测试计数里塞一条 skipped。手动跑：
 *
 * <pre>mvn -q -Dsurefire.useFile=false -Dtest=LayoutBench test</pre>
 *
 * <p>被测引擎由系统属性 {@code -Dengine=} 选（默认 {@code legacy}，
 * 即自研 {@link SceneLayout}）；{@code -Dengine=elk} 走 ELK 实现。
 *
 * <p>指标口径（全部基于布局产出的坐标算，两个引擎用同一套）：
 * <ul>
 *   <li><b>overlap</b>：图形两两相交的对数（交集面积 &gt; 1px²）；</li>
 *   <li><b>cross</b>：边两两交叉的次数。边用"两端图形中心连线"
 *       近似（绑定箭头渲染时也是直线），严格相交才算，
 *       共享端点的两条边不计；</li>
 *   <li><b>aspect</b>：整图包围盒的 长/宽（&lt;1 表示竖长，&gt;1 表示横长，
 *       报告里取 max/min 表"细长程度"）；</li>
 *   <li><b>label&gt;shape</b>：边标签压住某个图形的个数
 *       （标签按"边中点 + 估算文字宽度"摆）；</li>
 *   <li><b>label&gt;label</b>：边标签互相重叠的对数 —— 这就是截图里
 *       红字挤成一团的量化形态。</li>
 * </ul>
 */
class LayoutBench {

    /** 字符宽度估算，必须与 SceneLayout 的常数保持一致 */
    private static final double CJK_CHAR_WIDTH = 20.0;
    private static final double LATIN_CHAR_WIDTH = 10.0;

    /** 单行边标签的渲染高度（Excalidraw 默认 fontSize 20） */
    private static final double LABEL_HEIGHT = 25.0;

    /** 判定"压住"的最小交集面积（px²），滤掉贴边的零面积接触 */
    private static final double MIN_AREA = 1.0;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path FIXTURES = Paths.get(
            "workbuddyFlow", "tools", "layout-bench", "fixtures");

    /** 布局结果落盘目录，供渲染对比图用 */
    private static final Path OUT = Paths.get(
            "workbuddyFlow", "tools", "layout-bench", "out");

    @Test
    void report() throws Exception {
        LayoutEngine engine = engine();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(FIXTURES)) {
            stream.filter(path -> path.toString()
                            .endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
        }

        StringBuilder out = new StringBuilder();
        out.append('\n').append(String.format(
                "%-18s %6s %6s %8s %7s %7s %13s %13s %16s%n",
                "fixture", "shapes", "arrows", "overlap", "cross",
                "aspect", "label>shape", "label>label", "bbox(WxH)"));

        Files.createDirectories(OUT);
        String name = engine.getClass().getSimpleName();
        for (Path file : files) {
            String json = Files.readString(file);
            String laid = engine.layout(json, Set.of());
            Files.writeString(OUT.resolve(
                    name + "-" + file.getFileName()), laid);
            Metrics m = measure(laid);
            out.append(String.format(
                    "%-18s %6d %6d %8d %7d %7.2f %13d %13d %16s%n",
                    file.getFileName(), m.shapes, m.arrows,
                    m.overlap, m.cross, m.aspect, m.labelOnShape,
                    m.labelOnLabel,
                    Math.round(m.width) + "x" + Math.round(m.height)));
        }
        out.append('\n').append("engine = ")
                .append(engine.getClass().getSimpleName()).append('\n');
        System.out.println(out);
    }

    /** 被测引擎：-Dengine=elk 走 ELK，其余走自研 */
    private static LayoutEngine engine() {
        String name = System.getProperty("engine", "legacy");
        if ("elk".equalsIgnoreCase(name)) {
            return new ElkLayoutEngine();
        }
        return new SceneLayout();
    }

    // ==================== 指标 ====================

    private static Metrics measure(String json) throws IOException {
        JsonNode elements = MAPPER.readTree(json).get("elements");
        Map<String, double[]> boxes = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        List<double[]> arrows = new ArrayList<>();
        List<double[]> labels = new ArrayList<>();

        for (JsonNode element : elements) {
            String type = element.path("type").asText();
            String id = element.path("id").asText();
            if (isShape(type)) {
                boxes.put(id, new double[] {
                    element.path("x").asDouble(),
                    element.path("y").asDouble(),
                    element.path("width").asDouble(),
                    element.path("height").asDouble(),
                });
                ids.add(id);
            }
        }
        for (JsonNode element : elements) {
            if (!"arrow".equals(element.path("type").asText())) {
                continue;
            }
            String from = element.path("start").path("id").asText();
            String to = element.path("end").path("id").asText();
            double[] a = boxes.get(from);
            double[] b = boxes.get(to);
            if (a == null || b == null || from.equals(to)) {
                continue;
            }
            arrows.add(new double[] {
                a[0] + a[2] / 2, a[1] + a[3] / 2,
                b[0] + b[2] / 2, b[1] + b[3] / 2,
            });
            String text = element.path("label").path("text").asText("");
            if (!text.isEmpty()) {
                labels.add(new double[] {
                    (a[0] + a[2] / 2 + b[0] + b[2] / 2) / 2,
                    (a[1] + a[3] / 2 + b[1] + b[3] / 2) / 2,
                    labelWidth(text),
                    LABEL_HEIGHT,
                });
            }
        }

        Metrics m = new Metrics();
        m.shapes = boxes.size();
        m.arrows = arrows.size();
        m.overlap = countOverlaps(boxes, ids);
        m.cross = countCrossings(arrows);
        m.labelOnShape = countLabelOnShape(labels, boxes);
        m.labelOnLabel = countOverlaps(
                toMap(labels), rangeOf(labels.size()));
        double[] bounds = bounds(boxes.values());
        m.width = bounds[0];
        m.height = bounds[1];
        m.aspect = bounds[1] <= 0 || bounds[0] <= 0
                ? 0 : bounds[1] / bounds[0];
        return m;
    }

    private static boolean isShape(String type) {
        return "rectangle".equals(type)
                || "ellipse".equals(type)
                || "diamond".equals(type);
    }

    private static int countOverlaps(
            Map<String, double[]> boxes, List<String> ids) {
        int count = 0;
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (overlapArea(boxes.get(ids.get(i)),
                        boxes.get(ids.get(j))) > MIN_AREA) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countLabelOnShape(
            List<double[]> labels, Map<String, double[]> boxes) {
        int count = 0;
        for (double[] label : labels) {
            for (double[] box : boxes.values()) {
                if (overlapArea(label, box) > MIN_AREA) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static int countCrossings(List<double[]> arrows) {
        int count = 0;
        for (int i = 0; i < arrows.size(); i++) {
            for (int j = i + 1; j < arrows.size(); j++) {
                double[] a = arrows.get(i);
                double[] b = arrows.get(j);
                if (samePoint(a, b)) {
                    continue;
                }
                if (segmentsCross(a[0], a[1], a[2], a[3],
                        b[0], b[1], b[2], b[3])) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 共享端点的两条边不算交叉 */
    private static boolean samePoint(double[] a, double[] b) {
        return eq(a[0], b[0]) && eq(a[1], b[1])
                || eq(a[2], b[2]) && eq(a[3], b[3])
                || eq(a[0], b[2]) && eq(a[1], b[3])
                || eq(a[2], b[0]) && eq(a[3], b[1]);
    }

    private static boolean eq(double a, double b) {
        return Math.abs(a - b) < 0.5;
    }

    /** 严格相交：两端点分居另一线段两侧，共线/端点接触不算 */
    private static boolean segmentsCross(
            double ax, double ay, double bx, double by,
            double cx, double cy, double dx, double dy) {
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double cross(
            double ax, double ay, double bx, double by,
            double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /** 两个轴对齐盒子的交集面积 */
    private static double overlapArea(double[] a, double[] b) {
        double w = Math.min(a[0] + a[2], b[0] + b[2])
                - Math.max(a[0], b[0]);
        double h = Math.min(a[1] + a[3], b[1] + b[3])
                - Math.max(a[1], b[1]);
        return w <= 0 || h <= 0 ? 0 : w * h;
    }

    private static Map<String, double[]> toMap(List<double[]> values) {
        Map<String, double[]> map = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            map.put("l" + i, values.get(i));
        }
        return map;
    }

    private static List<String> rangeOf(int size) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            list.add("l" + i);
        }
        return list;
    }

    /** 整图包围盒：[宽, 高] */
    private static double[] bounds(Iterable<double[]> boxes) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] box : boxes) {
            minX = Math.min(minX, box[0]);
            minY = Math.min(minY, box[1]);
            maxX = Math.max(maxX, box[0] + box[2]);
            maxY = Math.max(maxY, box[1] + box[3]);
        }
        if (minX > maxX) {
            return new double[] {0, 0};
        }
        return new double[] {maxX - minX, maxY - minY};
    }

    private static double labelWidth(String label) {
        double width = 0;
        for (int i = 0; i < label.length(); i++) {
            width += label.charAt(i) >= 0x2E80
                    ? CJK_CHAR_WIDTH : LATIN_CHAR_WIDTH;
        }
        return width;
    }

    /** 一行指标 */
    private static final class Metrics {
        private int shapes;
        private int arrows;
        private int overlap;
        private int cross;
        private int labelOnShape;
        private int labelOnLabel;
        private double width;
        private double height;
        private double aspect;
    }
}
