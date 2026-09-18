package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.WrappingStrategy;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.util.BasicProgressMonitor;
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
 * 基于 ELK 的布局引擎（v2-38）
 *
 * <p>用 Eclipse Layout Kernel 的 layered 算法替代自研分层布局。
 * 换它的理由只有一条：<b>节点摆放的质量</b> —— ELK 做多轮交叉
 * 最小化、用网络单纯形做层内定位，这两项是二十年积累，自研那一趟
 * 重心排序追不上（2026-09-18 真实出图 43 元素时暴露）。
 *
 * <p>同一句话的另一半：ELK 只管节点几何。节点尺寸、层间距、网格吸附
 * 仍由 {@link LayoutSpec} 提供，标签落位仍归我们自己 —— 这些都
 * 按它给的坐标另算，不指望引擎。
 *
 * <p>与 {@link SceneLayout} 行为对齐的两点：
 * <ul>
 *   <li>试 DOWN / RIGHT 两个主方向，取长宽比更接近理想区间者
 *       （"确实更好才翻"，避免把链状图翻得更细长）；</li>
 *   <li>编辑模式只对新增子图跑布局，再整体平移到不与冻结图形
 *       相交的位置 —— 冻结坐标一像素不动，与 {@link SceneDrift}
 *       的判据保持一致。</li>
 * </ul>
 *
 * <p><b>自 2026-09-18 起为默认实现</b>（用户拍板）：同名指标实测下
 * 标签遮挡 8→2、标签互叠 7→0，"一列直下、层间短连线"比自研的
 * "主干横排 + 分支竖堆 + 长斜线"好读。自研实现在配置
 * {@code chartflow.layout.engine=legacy} 时回退启用。
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
     * <p>不是日志洁癖：布局引擎可由配置切换，而"切了没生效"从出图结果
     * 里很难一眼分辨（两种引擎都是分层、都吸附 20px 网格）—— 2026-09-18
     * 就为这件事反复推断过一轮。留一行启动日志，以后直接 `grep 布局引擎`
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
            double gap = LayoutSpec.mainGapFor(
                    labelsOf(elements, movableIds));

            // 两个主方向各排一次，按"偏离理想长宽比的程度"择优。
            // 判据与自研实现一致：另一个方向确实更好才换。
            double[] down = runElk(
                    movable, movableEdges, width, gap,
                    Direction.DOWN);
            double[] right = runElk(
                    movable, movableEdges, width, gap,
                    Direction.RIGHT);
            boolean topDown = true;
            double[] chosen = down;
            if (LayoutSpec.aspectOutOfRange(down[2], down[3])
                    && LayoutSpec.aspectPenalty(right[2], right[3])
                    < LayoutSpec.aspectPenalty(down[2], down[3])) {
                topDown = false;
                chosen = right;
            } else {
                // RIGHT 那一跑把坐标覆盖了，重跑 DOWN 取回
                runElk(movable, movableEdges, width, gap,
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
            double width, double gap, Direction direction) {
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
                LayoutSpec.SIBLING_GAP);
        graph.setProperty(CoreOptions.PADDING,
                new ElkPadding(0));
        // 竖排（不折行）：长链会被排成一根长条（实测 20 图形 → 680×4820），
        // 但"一列直下、层间短连线、标签不打架"是三种方案里最好读的；
        // 折行虽把画布压成 1860×1760，却让长边跨列横穿整图
        // （2026-09-18 三方对照，用户拍板取竖排）
        graph.setProperty(LayeredOptions.WRAPPING_STRATEGY,
                WrappingStrategy.OFF);

        Map<String, ElkNode> nodes = new LinkedHashMap<>();
        for (Shape shape : shapes) {
            ElkNode node = ElkGraphUtil.createNode(graph);
            node.setWidth(width);
            node.setHeight(LayoutSpec.NODE_HEIGHT);
            shape.lw = width;
            shape.lh = LayoutSpec.NODE_HEIGHT;
            nodes.put(shape.id, node);
        }
        for (Edge edge : edges) {
            ElkNode from = nodes.get(edge.from);
            ElkNode to = nodes.get(edge.to);
            if (from != null && to != null) {
                ElkGraphUtil.createSimpleEdge(from, to);
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
            maxX = Math.max(maxX, shape.px + width);
            maxY = Math.max(maxY,
                    shape.py + LayoutSpec.NODE_HEIGHT);
        }
        return new double[] {
            minX, minY, maxX - minX, maxY - minY,
        };
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
     * <p>整体平移而不是逐个挪：新增元素之间的相对关系是 ELK 刚算
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
            edges.add(new Edge(from, to));
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

    private void writeBack(List<Shape> shapes) {
        for (Shape shape : shapes) {
            shape.json.put("x", shape.px);
            shape.json.put("y", shape.py);
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
        private final String label;
        private double px;
        private double py;
        /** 当前尺寸：冻结图形保持原样，可移动图形在布局时统一 */
        private double lw;
        private double lh;

        private Shape(ObjectNode json, String id, String label,
                double x, double y, double w, double h) {
            this.json = json;
            this.id = id;
            this.label = label;
            this.px = x;
            this.py = y;
            this.lw = w;
            this.lh = h;
        }
    }

    /** 一条有向边 */
    private record Edge(String from, String to) {
    }
}
