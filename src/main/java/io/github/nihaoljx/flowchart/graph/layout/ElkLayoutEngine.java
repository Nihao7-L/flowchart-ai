package io.github.nihaoljx.flowchart.graph.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.WrappingStrategy;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkBendPoint;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 ELK 的布局引擎
 *
 * 用 Eclipse Layout Kernel 的 layered 算法替代自研分层布局。
 * 换它的理由只有一条：节点摆放的质量 —— ELK 做多轮交叉
 * 最小化、用网络单纯形做层内定位，这两项是二十年积累，自研那一趟
 * 重心排序追不上。
 *
 * 同一句话的另一半：ELK 只管节点几何。节点尺寸、层间距、网格吸附
 * 仍由 LayoutSpec 提供，标签落位仍归我们自己 —— 这些都
 * 按它给的坐标另算，不指望引擎。
 *
 * 尺寸那一条有个陷阱：nodeSize 给的
 * 不是"我们希望节点多大"，而是渲染端最终会画多大；并且排完必须把
 * 它写回契约（writeBack），否则渲染端会按自己的规则把装不下
 * 文字的容器撑高，与布局算的层间距对不上。
 *
 * 与 SceneLayout 行为对齐的两点：
 *
 * - 试 DOWN / RIGHT 两个主方向，取长宽比更接近理想区间者
 *       （"确实更好才翻"，避免把链状图翻得更细长）；
 *
 * - 编辑模式只对新增子图跑布局，再整体平移到不与冻结图形
 *       相交的位置 —— 冻结坐标一像素不动，与 SceneDrift
 *       的判据保持一致。
 *
 * 默认实现（`chartflow.layout.engine=elk`，
     * 自研实现在 `legacy` 时回退）。实测下标签遮挡 8→2、标签互叠 7→0，
     * "一列直下、层间短连线"比自研的"主干横排 + 分支竖堆 + 长斜线"好读。
 */
@Component
@ConditionalOnProperty(name = "chartflow.layout.engine",
        havingValue = "elk", matchIfMissing = true)
public class ElkLayoutEngine implements LayoutEngine {

    private static final Logger LOG =
            LoggerFactory.getLogger(ElkLayoutEngine.class);

    /** 可以作为布局节点参与摆放的图形类型 */
    private static final Set<String> SHAPE_TYPES =
            Set.of("rectangle", "ellipse", "diamond");

    /** 编辑模式下向右找空位的最大尝试次数 */
    private static final int MAX_SLOT_TRIES = 40;

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    /**
     * 启动即声明"当前生效的是哪个引擎"。
     *
 * 不是日志洁癖：布局引擎可由配置切换，而"切了没生效"从出图结果
     * 里很难一眼分辨（两种引擎都是分层、都吸附 20px 网格）。留一行启动日志，以后直接 `grep 布局引擎`
     * 即可，不用再猜。
     */
    public ElkLayoutEngine() {
        LOG.info("布局引擎：ELK layered（竖排，不折行）");
    }

    @Override
    public String layout(
            String json, Set<String> fixedIds) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode elementsNode = root.get("elements");
            if (elementsNode == null
                    || !elementsNode.isArray()) {
                return json;
            }
            List<JsonNode> elements = new ArrayList<>();
            for (JsonNode element : elementsNode) {
                elements.add(element);
            }
            List<Shape> shapes = collectShapes(elements);
            if (shapes.isEmpty()) {
                return json;
            }

            List<Shape> movable = new ArrayList<>();
            List<Shape> frozen = new ArrayList<>();
            for (Shape shape : shapes) {
                if (fixedIds != null
                        && fixedIds.contains(shape.id)) {
                    frozen.add(shape);
                } else {
                    movable.add(shape);
                }
            }
            if (movable.isEmpty()) {
                LOG.info("ELK 布局跳过：{} 个图形全部冻结",
                        frozen.size());
                return json;
            }

            List<Edge> edges = collectEdges(elements, shapes);
            Set<String> movableIds = new LinkedHashSet<>();
            for (Shape shape : movable) {
                movableIds.add(shape.id);
            }
            List<Edge> movableEdges = new ArrayList<>();
            for (Edge edge : edges) {
                if (movableIds.contains(edge.from)
                        && movableIds.contains(edge.to)) {
                    movableEdges.add(edge);
                }
            }

            double width = LayoutSpec.nodeWidth(
                    labelsOf(movable));
            List<String> edgeLabels =
                    labelsOf(elements, movableIds);
            double gap = LayoutSpec.mainGapFor(edgeLabels);
            // 同层兄弟间距与层间距**对称**放大（修复的真实缺陷）：
            // 原本这里写死 LayoutSpec.SIBLING_GAP = 80，只够 4 个汉字，
            // 而"支付请求路由"6 字 = 120px → 标签必然横跨到相邻节点上
            double siblingGap =
                    LayoutSpec.siblingGapFor(edgeLabels);

            // 两个主方向各排一次，按"偏离理想长宽比的程度"择优。
            // 判据与自研实现一致：另一个方向确实更好才换。
            double[] down = runElk(
                    movable, movableEdges, width, gap, siblingGap,
                    Direction.DOWN);
            double[] right = runElk(
                    movable, movableEdges, width, gap, siblingGap,
                    Direction.RIGHT);
            boolean topDown = true;
            double[] chosen = down;
            if (LayoutSpec.aspectOutOfRange(down[2], down[3])
                    && LayoutSpec.aspectPenalty(right[2], right[3])
                    < LayoutSpec.aspectPenalty(down[2], down[3])) {
                topDown = false;
                chosen = right;
            } else if (LayoutSpec.aspectOutOfRange(down[2], down[3])
                    && LayoutSpec.aspectOutOfRange(
                            right[2], right[3])) {
                // 双向都出区间（链状/树形图）：换向只是转置包围盒，
                // 长宽比判据永远救不了。实测 12 图验收 10_topology /
                // 11_mindmap 排成竖条（0.14 长宽比）—— 渲染视口是
                // 横屏，高度决定缩放下限，竖条最伤；取更宽的一侧
                // （右包围盒更矮 = 更宽），至少与屏幕同向
                if (right[3] < down[3]) {
                    topDown = false;
                    chosen = right;
                    // RIGHT 那一跑的坐标已是最新，无需重跑
                    snapAll(movable);
                    if (!frozen.isEmpty()) {
                        shiftToFreeSlot(movable, frozen);
                    }
                    writeBack(movable);
                    LOG.info("ELK 布局完成：{} 个图形（冻结 {}），"
                                    + "主方向 {}（双向出区间取宽），"
                                    + "包围盒 {}x{}",
                            movable.size(), frozen.size(), "自左而右",
                            Math.round(chosen[2]),
                            Math.round(chosen[3]));
                    return objectMapper.writeValueAsString(root);
                }
                // DOWN 已更宽，坐标就是最新，直接落
                snapAll(movable);
                writeBack(movable);
                LOG.info("ELK 布局完成：{} 个图形（冻结 {}），"
                                + "主方向 {}（双向出区间取宽），"
                                + "包围盒 {}x{}",
                        movable.size(), frozen.size(), "自上而下",
                        Math.round(chosen[2]),
                        Math.round(chosen[3]));
                return objectMapper.writeValueAsString(root);
            } else {
                // RIGHT 那一跑把坐标覆盖了，重跑 DOWN 取回
                runElk(movable, movableEdges, width, gap, siblingGap,
                        Direction.DOWN);
            }

            snapAll(movable);

            if (!frozen.isEmpty()) {
                shiftToFreeSlot(movable, frozen);
            }
            writeBack(movable);

            LOG.info("ELK 布局完成：{} 个图形（冻结 {}），"
                            + "主方向 {}，包围盒 {}x{}",
                    movable.size(), frozen.size(),
                    topDown ? "自上而下" : "自左而右",
                    Math.round(chosen[2]),
                    Math.round(chosen[3]));
            return objectMapper
                    .writeValueAsString(root);
        } catch (Exception e) {
            LOG.warn("ELK 布局跳过（{}）", e.getMessage());
            return json;
        }
    }

    // ==================== ELK 调用 ====================

    /**
     * 跑一次 ELK，并把结果坐标写进各 Shape 的 px / py。
     *
     * @return {最小 x, 最小 y, 包围盒宽, 包围盒高}
     */
    private double[] runElk(
            List<Shape> shapes, List<Edge> edges,
            double width, double gap, double siblingGap,
            Direction direction) {
        // 元数据（选项定义与算法清单）由 jar 内的 META-INF/services
        // 通过 SPI 自动注册，脱离 Eclipse 也不需要手动初始化
        ElkNode graph = ElkGraphUtil.createGraph();
        graph.setProperty(CoreOptions.ALGORITHM,
                "org.eclipse.elk.layered");
        graph.setProperty(CoreOptions.DIRECTION, direction);
        graph.setProperty(
                LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS,
                gap);
        graph.setProperty(LayeredOptions.SPACING_NODE_NODE,
                siblingGap);
        graph.setProperty(CoreOptions.PADDING,
                new ElkPadding(0));
        // 竖排（不折行）：长链会被排成一根长条（实测 20 图形 → 680×4820），
        // 但"一列直下、层间短连线、标签不打架"是三种方案里最好读的；
        // 折行虽把画布压成 1860×1760，却让长边跨列横穿整图
        // （三方对照，用户取竖排）
        graph.setProperty(LayeredOptions.WRAPPING_STRATEGY,
                WrappingStrategy.OFF);
        // 显式声明正交布线：ELK 默认值解析后通常也是正交，但
        // 边路由拐点是下面 writeBackEdgeRoutes 的原料，不依赖
        // "默认恰好如此" —— 显式设置才保证 sections 一定有折线。
        graph.setProperty(CoreOptions.EDGE_ROUTING,
                EdgeRouting.ORTHOGONAL);

        Map<String, ElkNode> nodes = new LinkedHashMap<>();
        for (Shape shape : shapes) {
            ElkNode node = ElkGraphUtil.createNode(graph);
            double[] size = LayoutSpec.nodeSize(
                    shape.type, shape.label, width);
            // ELK 只认"节点多大"，它不管这个尺寸从哪来 —— 但层间距是
            // 按它算的，所以这里必须给**渲染端最终会画多大**的尺寸
            // （见 LayoutSpec#nodeSize：文字装不下时渲染端会撑高容器）
            node.setWidth(size[0]);
            node.setHeight(size[1]);
            shape.lw = size[0];
            shape.lh = size[1];
            nodes.put(shape.id, node);
        }
        Map<Edge, ElkEdge> elkEdges = new LinkedHashMap<>();
        for (Edge edge : edges) {
            ElkNode from = nodes.get(edge.from);
            ElkNode to = nodes.get(edge.to);
            if (from != null && to != null) {
                elkEdges.put(edge,
                        ElkGraphUtil.createSimpleEdge(from, to));
            }
        }

        new RecursiveGraphLayoutEngine().layout(
                graph, new BasicProgressMonitor());

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Shape shape : shapes) {
            ElkNode node = nodes.get(shape.id);
            shape.px = node.getX();
            shape.py = node.getY();
            minX = Math.min(minX, shape.px);
            minY = Math.min(minY, shape.py);
            maxX = Math.max(maxX, shape.px + shape.lw);
            maxY = Math.max(maxY, shape.py + shape.lh);
        }
        writeBackEdgeRoutes(elkEdges);
        return new double[] {
            minX, minY, maxX - minX, maxY - minY,
        };
    }

    /**
     * 把 ELK 算出的边路由（正交折线）写回箭头的 points。
     *
     * 修复的真实缺陷：引擎此前只回写节点坐标，边的路由被整个
     * 丢掉 —— 前端只能拿两端中心连直线，回边（如"校验失败"
     * 从菱形指回上层的输入框）会**竖着穿过中间层的节点**，
     * 五图验收里登录流程图首当其冲。ELK 的分层 + 正交路由本
     * 就是选它的理由之一，拐点白算不用等于买了车不开。
     *
     * 写法遵循契约约定：x / y = 折线首点绝对坐标，
     * points = 相对首点的偏移数组。首末点落在节点边缘（ELK
     * 的 start/end location），前端吸附逻辑会再做一次锚点
     * 归位，中段拐点原样保留 —— 见 sceneAdapter 的
     * withArrowGeometry（自带几何中段不透传改动）。
     *
     * 只处理本次参与布局的边（两端都可移动）：冻结图形的边
     * 不重排，坐标一像素不动。
     */
    private void writeBackEdgeRoutes(
            Map<Edge, ElkEdge> elkEdges) {
        for (Map.Entry<Edge, ElkEdge> entry
                : elkEdges.entrySet()) {
            ElkEdge elkEdge = entry.getValue();
            if (elkEdge.getSections() == null
                    || elkEdge.getSections().isEmpty()) {
                continue;
            }
            ElkEdgeSection section =
                    elkEdge.getSections().get(0);
            double startX = section.getStartX();
            double startY = section.getStartY();
            double endX = section.getEndX();
            double endY = section.getEndY();
            if (!Double.isFinite(startX)
                    || !Double.isFinite(startY)
                    || !Double.isFinite(endX)
                    || !Double.isFinite(endY)) {
                continue;
            }
            // 只写"真折线"（有拐点）的边：直线边的几何留给前端
            // 按绑定推导（锚点边缘交点比 ELK 的原始端点更贴合），
            // 这与 SceneSanitizer"绑定箭头只留 ≥3 点折线"的口径
            // 一致 —— 2 点数组会被它摘掉，白写。
            if (section.getBendPoints().isEmpty()) {
                continue;
            }
            // 先局部收集、全量校验，再一次性写回 —— 半路发现
            // 坏点时不能留下写了一半的 points 在箭头上
            List<double[]> route = new ArrayList<>();
            route.add(new double[] {0, 0});
            boolean broken = false;
            for (ElkBendPoint bend
                    : section.getBendPoints()) {
                if (!Double.isFinite(bend.getX())
                        || !Double.isFinite(bend.getY())) {
                    broken = true;
                    break;
                }
                route.add(new double[] {
                    round1(bend.getX() - startX),
                    round1(bend.getY() - startY),
                });
            }
            if (broken) {
                continue;
            }
            route.add(new double[] {
                round1(endX - startX),
                round1(endY - startY),
            });
            ObjectNode arrow = entry.getKey().json;
            ArrayNode points = arrow.putArray("points");
            for (double[] point : route) {
                appendPoint(points, point[0], point[1]);
            }
            arrow.put("x", round1(startX));
            arrow.put("y", round1(startY));
        }
    }

    private void appendPoint(
            ArrayNode points, double dx, double dy) {
        ArrayNode point = points.addArray();
        point.add(round1(dx));
        point.add(round1(dy));
    }

    /** 一位小数：布局坐标不需要更多精度，也避免浮点尾巴进契约 */
    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private void snapAll(List<Shape> shapes) {
        for (Shape shape : shapes) {
            shape.px = LayoutSpec.snap(shape.px);
            shape.py = LayoutSpec.snap(shape.py);
        }
    }

    // ==================== 编辑模式避让 ====================

    /**
     * 把新增子图整体推到不与冻结图形相交的位置。
     *
 * 整体平移而不是逐个挪：新增元素之间的相对关系是 ELK 刚算
     * 出来的，挪散了就白排了。
     */
    private void shiftToFreeSlot(
            List<Shape> movable, List<Shape> frozen) {
        double step = LayoutSpec.MIN_NODE_WIDTH
                + LayoutSpec.SIBLING_GAP;
        for (int i = 0; i < MAX_SLOT_TRIES; i++) {
            if (!collides(movable, frozen)) {
                if (i > 0) {
                    LOG.info("ELK 新增子图右移 {} 次避让冻结图形",
                            i);
                }
                return;
            }
            for (Shape shape : movable) {
                shape.px += step;
            }
        }
        LOG.warn("ELK 避让尝试 {} 次仍未找到空位",
                MAX_SLOT_TRIES);
    }

    private boolean collides(
            List<Shape> movable, List<Shape> frozen) {
        for (Shape a : movable) {
            for (Shape b : frozen) {
                if (overlaps(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean overlaps(Shape a, Shape b) {
        return a.px < b.px + b.lw && b.px < a.px + a.lw
                && a.py < b.py + b.lh
                && b.py < a.py + a.lh;
    }

    // ==================== 采集与回写 ====================

    private List<Shape> collectShapes(
            List<JsonNode> elements) {
        List<Shape> shapes = new ArrayList<>();
        for (JsonNode element : elements) {
            if (!element.isObject()
                    || !SHAPE_TYPES.contains(
                            text(element, "type"))) {
                continue;
            }
            String id = text(element, "id");
            if (id == null) {
                continue;
            }
            if (!finite(element, "x")
                    || !finite(element, "y")
                    || !positive(element, "width")
                    || !positive(element, "height")) {
                continue;
            }
            shapes.add(new Shape(
                    (ObjectNode) element, id,
                    text(element, "type"),
                    labelOf(element),
                    number(element, "x"),
                    number(element, "y"),
                    number(element, "width"),
                    number(element, "height")));
        }
        return shapes;
    }

    /** 只认"两端都绑定到图形"的箭头 */
    private List<Edge> collectEdges(
            List<JsonNode> elements, List<Shape> shapes) {
        Set<String> shapeIds = new LinkedHashSet<>();
        for (Shape shape : shapes) {
            shapeIds.add(shape.id);
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonNode element : elements) {
            if (!"arrow".equals(
                    text(element, "type"))) {
                continue;
            }
            String from = bindingId(element, "start");
            String to = bindingId(element, "end");
            if (from == null || to == null
                    || from.equals(to)) {
                continue;
            }
            if (!shapeIds.contains(from)
                    || !shapeIds.contains(to)) {
                continue;
            }
            edges.add(new Edge(from, to, (ObjectNode) element));
        }
        return edges;
    }

    private List<String> labelsOf(List<Shape> shapes) {
        List<String> labels = new ArrayList<>();
        for (Shape shape : shapes) {
            labels.add(shape.label);
        }
        return labels;
    }

    /** 与可移动图形相连的边标签 */
    private List<String> labelsOf(
            List<JsonNode> elements, Set<String> movableIds) {
        List<String> labels = new ArrayList<>();
        for (JsonNode element : elements) {
            if (!"arrow".equals(
                    text(element, "type"))) {
                continue;
            }
            boolean related =
                    movableIds.contains(
                            bindingId(element, "start"))
                    || movableIds.contains(
                            bindingId(element, "end"));
            if (related) {
                labels.add(labelOf(element));
            }
        }
        return labels;
    }

    /**
     * 把算好的坐标与尺寸写回图形。
     *
 * 尺寸必须一起写回（修复）：引擎是按
     * nodeSize 算出的"渲染尺寸"排的层间距，如果契约里
     * 留着 LLM 给的另一个尺寸，渲染端就会按它自己的规则重排文字、把装不下
     * 的容器撑高 —— 排好的层间距当场失效（实测题 2：两处图形重叠）。
     * 自研引擎 SceneLayout 一直写着尺寸，这里是补齐它的行为。
     */
    private void writeBack(List<Shape> shapes) {
        for (Shape shape : shapes) {
            shape.json.put("x", shape.px);
            shape.json.put("y", shape.py);
            shape.json.put("width", shape.lw);
            shape.json.put("height", shape.lh);
        }
    }

    // ==================== 读取小工具 ====================

    private String labelOf(JsonNode element) {
        JsonNode label = element.get("label");
        if (label == null) {
            return "";
        }
        if (label.isTextual()) {
            return label.asText();
        }
        JsonNode inner = label.get("text");
        return inner != null && inner.isTextual()
                ? inner.asText() : "";
    }

    private String bindingId(
            JsonNode element, String field) {
        JsonNode binding = element.get(field);
        if (binding == null || !binding.isObject()) {
            return null;
        }
        return text(binding, "id");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual()
                ? value.asText() : null;
    }

    private boolean finite(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber()
                && Double.isFinite(value.asDouble());
    }

    private boolean positive(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber()
                && value.asDouble() > 0;
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? 0 : value.asDouble();
    }

    /** 布局中的一个图形 */
    private static final class Shape {
        private final ObjectNode json;
        private final String id;
        /** 图形类型：决定"文字可用宽高"（菱形只用到一半宽） */
        private final String type;
        private final String label;
        private double px;
        private double py;
        /** 当前尺寸：冻结图形保持原样，可移动图形由规格算出并写回 */
        private double lw;
        private double lh;

        private Shape(ObjectNode json, String id, String type,
                String label, double x, double y, double w, double h) {
            this.json = json;
            this.id = id;
            this.type = type;
            this.label = label;
            this.px = x;
            this.py = y;
            this.lw = w;
            this.lh = h;
        }
    }

    /** 一条有向边（json = 对应箭头元素，路由拐点写回的目标） */
    private record Edge(
            String from, String to, ObjectNode json) {
    }
}
