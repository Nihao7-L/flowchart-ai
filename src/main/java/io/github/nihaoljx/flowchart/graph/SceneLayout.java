package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 布局（v2-36）：把坐标从 LLM 手里收回
 *
 * <p><b>它解决的问题：</b>在此之前"摆在哪"是 LLM 说了算，而模型给的坐标
 * 是逐 token 猜出来的。2026-09-17 用户实测：元素互相压住、箭头短到看不出
 * 方向、边上加了文字就把线盖没。而契约自述（scene.schema.json）与 ADR-4
 * 都早已写明"坐标为数据，生成时由布局算法确定性算出" —— 实现侧一直缺这一步。
 *
 * <p><b>两种模式（硬约束，不是优化）：</b>
 * <ul>
 *   <li><b>新建</b>（{@code fixedIds} 为空）：整图全量重排，这正是要治的"乱"；</li>
 *   <li><b>编辑</b>（{@code fixedIds} 非空）：<b>只给本次新增的图形定位</b>，
 *       既有图形的坐标一个都不动。</li>
 * </ul>
 * 后者不是偏好而是必须：PromptService 已向 LLM 承诺"没有要求改动的元素
 * 必须原样保留 id / type / 几何"，这条承诺由 {@link SceneDrift} 守着 ——
 * 布局若在编辑模式下重排全图，当场被判定为"整图重排"并触发修正轮，
 * 两个组件互相打架。
 *
 * <p><b>算法：</b>分层布局（Sugiyama 的简化版），刻意手写、不引依赖。
 * 最长路径分层 → 层内重心排序（上下各一趟）→ 主 / 副方向坐标分配 →
 * 网格对齐 → 长宽比超出区间就换一次主方向。
 *
 * <p><b>刻意不做的（都是"宁缺勿滥"）：</b>
 * <ul>
 *   <li>不碰 {@code frame}：契约里 frame 没有 children，无从知道它框住了谁，
 *       动它只会把分组关系弄错；</li>
 *   <li>不碰独立 {@code text} 元素：那是标注性文字（标题、说明），
 *       位置属于用户的表达，不属于图的拓扑；</li>
 *   <li>不重排多折线（points &gt; 2）：重算会把它拉直，与
 *       {@link SceneBinder} 的取舍保持一致；</li>
 *   <li>不做交叉最小化、不做节点内换行 —— 布局质量弱于 ELK，
 *       这是"不引依赖"的已知代价（见 v2-36 决策 1）。</li>
 * </ul>
 *
 * <p><b>任何异常都原样返回</b>：布局是"锦上添花"，不能变成新的故障点。
 *
 * <p><b>自 2026-09-18 起让位为备用实现</b>（v2-38 抽 {@link LayoutEngine}
 * 接口后）：默认走 {@code ElkLayoutEngine}，本类在
 * {@code chartflow.layout.engine=legacy} 时启用。留着的价值有二 ——
 * 零第三方依赖的退路，以及作为 ELK 的对照组（见
 * {@code workbuddyFlow/tools/layout-bench/}）。
 */
@Component
@ConditionalOnProperty(name = "chartflow.layout.engine",
        havingValue = "legacy")
public class SceneLayout implements LayoutEngine {

    private static final Logger LOG =
            LoggerFactory.getLogger(SceneLayout.class);

    /**
     * 启动即声明"当前生效的是哪个引擎"。
     *
     * <p>两种引擎都是分层 + 20px 吸附，光看出来的图很难分辨是谁排的 ——
     * 2026-09-18 切换默认引擎后就为这件事反复推断过一轮。留一行启动
     * 日志，`grep 布局引擎` 即可确认，不必再猜。
     */
    public SceneLayout() {
        LOG.info("布局引擎：自研分层布局（legacy）");
    }

    /** 网格步长：坐标吸附到它的整数倍（治"歪歪扭扭"，也让快照测试稳定） */
    private static final double GRID = 20.0;

    /** 节点统一高度 */
    private static final double NODE_HEIGHT = 60.0;

    /** 节点宽度区间：下限保证框里放得下字，上限防止一个框吃掉整行 */
    private static final double MIN_NODE_WIDTH = 160.0;
    private static final double MAX_NODE_WIDTH = 360.0;

    /** 图形内边距（左右合计），与前端 label 的 padding 大致对齐 */
    private static final double NODE_LABEL_PADDING = 24.0;

    /**
     * 单字符宽度估算（2026-09-18 用真实浏览器实测标定，探针见
     * {@code workbuddyFlow/tools/e2e/probe_text_metrics.py}，Excalidraw 默认 fontSize 20）：
     * 中日韩实测**正好 20px/字**（1~8 字标签宽度 = 20/40/80/120/160，全部吻合 1 em）、
     * 拉丁实测小写 9.18px、单个大写 11.5~12.9px，取 10 覆盖混排。
     * 旧值 16 / 9 把中文低估 25%，直接后果是层间距按偏窄的标签算 ——
     * "边上加了文字就看不到线"（2026-09-18 实测：4 字标签估 64 / 实测 80，
     * 层间距 100 &lt; 需要的 104）。
     */
    private static final double LATIN_CHAR_WIDTH = 10.0;
    private static final double CJK_CHAR_WIDTH = 20.0;

    /** 主方向层间距（相邻两层的净距离） */
    private static final double LAYER_GAP = 100.0;

    /** 副方向同层间距（同一层相邻两节点的净距离） */
    private static final double SIBLING_GAP = 80.0;

    /**
     * 边标签两侧留白。12 在宽度校准后太紧（估算与实测仍有 ±10% 余量，
     * 且箭头头部本身约占 10px），提到 16 —— 标签两侧各露 16px 线头。
     */
    private static final double EDGE_LABEL_PADDING = 16.0;

    /** 整图长宽比允许区间，超出就换一次主方向 */
    private static final double MIN_ASPECT = 0.5;
    private static final double MAX_ASPECT = 2.0;

    /** 端点命中判定容差，与 SceneBinder 保持一致 */
    private static final double SNAP_TOLERANCE = 12.0;

    /** 编辑模式找空位时的最大试探次数 */
    private static final int MAX_SLOT_TRIES = 40;

    /** 可以把坐标交给布局的图形类型 */
    private static final Set<String> SHAPE_TYPES =
            Set.of("rectangle", "ellipse", "diamond");

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    /**
     * 布局：算出图形该摆在哪
     *
     * @param json     已做过端点吸附与去重的场景 JSON
     * @param fixedIds 坐标必须原样保留的图形 id；
     *                 为空 = 新建模式（全量重排）
     * @return 布局后的 JSON；没有可摆放的图形、或发生任何异常时
     *         <b>返回原字符串</b>
     */
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
            List<Node> shapes = collectShapes(elements);
            if (shapes.isEmpty()) {
                return json;
            }

            List<Node> movable = new ArrayList<>();
            List<Node> frozen = new ArrayList<>();
            for (Node shape : shapes) {
                if (fixedIds != null
                        && fixedIds.contains(shape.id)) {
                    frozen.add(shape);
                } else {
                    movable.add(shape);
                }
            }
            if (movable.isEmpty()) {
                // 编辑模式下这一轮没有新增图形：既有坐标一个都不动
                LOG.info("布局跳过：{} 个图形全部冻结",
                        frozen.size());
                return json;
            }

            List<Edge> edges = collectEdges(
                    elements, shapes);
            Set<String> movableIds = new LinkedHashSet<>();
            for (Node node : movable) {
                movableIds.add(node.id);
            }
            List<Edge> movableEdges = new ArrayList<>();
            for (Edge edge : edges) {
                if (movableIds.contains(edge.from)
                        && movableIds.contains(edge.to)) {
                    movableEdges.add(edge);
                }
            }

            sizeNodes(movable);
            double mainGap = mainGapFor(
                    elements, movableIds);
            boolean topDown = true;
            double[] box = place(
                    movable, movableEdges,
                    mainGap, topDown);
            if (aspectOutOfRange(box)) {
                double[] flipped = place(
                        movable, movableEdges,
                        mainGap, false);
                // 翻过去如果更细长就退回原方向。链式图是这条判据的
                // 试金石：10 层竖排是 1:9.4，横过来是 38:1 ——
                // "超区间就翻"会挑中更糟的那个（2026-09-18 实测）
                if (aspectOutOfRange(flipped)
                        && aspectPenalty(flipped)
                        >= aspectPenalty(box)) {
                    box = place(movable, movableEdges,
                            mainGap, true);
                } else {
                    topDown = false;
                    box = flipped;
                }
            }

            if (!frozen.isEmpty()) {
                shiftToFreeSlot(movable, frozen);
            }
            writeBack(movable, elements, shapes);

            LOG.info("布局完成：{} 个图形（冻结 {}），主方向 {}",
                    movable.size(), frozen.size(),
                    topDown ? "自上而下" : "自左而右");
            return objectMapper
                    .writeValueAsString(root);
        } catch (Exception e) {
            LOG.warn("布局跳过（{}）", e.getMessage());
            return json;
        }
    }

    // ==================== 采集 ====================

    /** 收集可以参与摆放的图形 */
    private List<Node> collectShapes(
            List<JsonNode> elements) {
        List<Node> shapes = new ArrayList<>();
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
            shapes.add(new Node(
                    (ObjectNode) element,
                    id,
                    labelOf(element),
                    number(element, "x"),
                    number(element, "y"),
                    number(element, "width"),
                    number(element, "height")));
        }
        return shapes;
    }

    /** 收集有向边：只认"两端都绑定到图形"的箭头 */
    private List<Edge> collectEdges(
            List<JsonNode> elements, List<Node> shapes) {
        Set<String> shapeIds = new LinkedHashSet<>();
        for (Node shape : shapes) {
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
            // 悬空端、frame 端、自环都给不出可靠的父子关系，
            // 硬凑只会让分层错乱
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

    // ==================== 尺寸 ====================

    /**
     * 收敛节点尺寸
     *
     * <p>宽度按"最长的标签装得下"取一个统一值 —— 既整齐，
     * 又不像"一律 200x60"那样把文字截断。
     */
    private void sizeNodes(List<Node> nodes) {
        double widest = MIN_NODE_WIDTH;
        for (Node node : nodes) {
            double needed = labelWidth(node.label)
                    + NODE_LABEL_PADDING;
            widest = Math.max(widest,
                    Math.min(MAX_NODE_WIDTH, needed));
        }
        for (Node node : nodes) {
            node.w = widest;
            node.h = NODE_HEIGHT;
        }
    }

    /**
     * 主方向层间距：至少要装得下最长的边标签
     *
     * <p>这是"边上加了文字就看不到线"的直接对策：边在主方向上的
     * 可见长度就等于层间距，装不下标签就会被文字盖满。
     *
     * <p>返回值已含两项余量：① 标签两侧留白；② 一个 {@link #GRID} 的吸附容差
     * （层间距由"绝对坐标四舍五入到网格"间接决定，相邻两层各带 ±10 误差）。
     * 结果向上取整到网格整数倍，保证确定性（同输入同输出）。
     *
     * @param movableIds 会移动的图形 id（只统计与它们相连的边）
     */
    private double mainGapFor(
            List<JsonNode> elements,
            Set<String> movableIds) {
        double widest = 0;
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
            if (!related) {
                continue;
            }
            widest = Math.max(widest,
                    labelWidth(labelOf(element)));
        }
        double needed = widest + 2 * EDGE_LABEL_PADDING;
        // 加一个 GRID 的吸附容差：坐标是"绝对位置四舍五入到 20 的整数倍"，
        // 相邻两层的取整误差各自 ±10，最坏会把层间距吃掉 20px ——
        // 2026-09-18 真实出图实测：7 字标签需要 140+32=172，实际落到 160。
        // 再向上取整到网格整数倍，保证结果确定（同输入同输出）且不会差一点点。
        double raw = Math.max(LAYER_GAP,
                needed + GRID);
        return Math.ceil(raw / GRID) * GRID;
    }

    /** 估算一段文字的像素宽度（中文按 1 em，宁可估宽不可估窄） */
    private double labelWidth(String label) {
        if (label == null || label.isEmpty()) {
            return 0;
        }
        double width = 0;
        for (int i = 0; i < label.length(); i++) {
            width += label.charAt(i) >= 0x2E80
                    ? CJK_CHAR_WIDTH
                    : LATIN_CHAR_WIDTH;
        }
        return width;
    }

    // ==================== 分层与排序 ====================

    /**
     * 最长路径分层
     *
     * <p>用 Kahn 拓扑序而不是 BFS：BFS 的层号取决于"哪条路径先走到"，
     * 同一张图会抖动；最长路径与遍历顺序无关，才配得上"同输入同输出"。
     * 环上的节点（Kahn 走不到）统一放最后一层 —— 不猜环内顺序，
     * 只要保证它们不阻塞其它节点。
     */
    private List<List<Node>> assignLayers(
            List<Node> nodes, List<Edge> edges) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, List<String>> out =
                new LinkedHashMap<>();
        Map<String, Integer> indegree =
                new LinkedHashMap<>();
        for (Node node : nodes) {
            ids.add(node.id);
            out.put(node.id, new ArrayList<>());
            indegree.put(node.id, 0);
        }
        for (Edge edge : edges) {
            if (!ids.contains(edge.from)
                    || !ids.contains(edge.to)) {
                continue;
            }
            out.get(edge.from).add(edge.to);
            indegree.merge(edge.to, 1, Integer::sum);
        }

        Map<String, Integer> level =
                new LinkedHashMap<>();
        for (Node node : nodes) {
            level.put(node.id, 0);
        }
        List<String> queue = new ArrayList<>();
        for (Node node : nodes) {
            if (indegree.get(node.id) == 0) {
                queue.add(node.id);
            }
        }
        Set<String> visited = new LinkedHashSet<>();
        int head = 0;
        while (head < queue.size()) {
            String from = queue.get(head++);
            visited.add(from);
            for (String to : out.get(from)) {
                level.put(to, Math.max(
                        level.get(to),
                        level.get(from) + 1));
                if (indegree.merge(to, -1,
                        Integer::sum) == 0) {
                    queue.add(to);
                }
            }
        }

        int maxLevel = 0;
        for (String id : visited) {
            maxLevel = Math.max(maxLevel,
                    level.get(id));
        }
        for (Node node : nodes) {
            if (!visited.contains(node.id)) {
                level.put(node.id, maxLevel + 1);
            }
        }

        int layerCount = 0;
        for (Node node : nodes) {
            node.layer = level.get(node.id);
            layerCount = Math.max(
                    layerCount, node.layer + 1);
        }
        List<List<Node>> layers = new ArrayList<>();
        for (int i = 0; i < layerCount; i++) {
            layers.add(new ArrayList<>());
        }
        // 层内初始顺序按 id 字典序：布局承诺"同输入同输出"，
        // 任何依赖哈希迭代顺序的初始顺序都会让快照随机变红
        List<Node> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparing(
                node -> node.id));
        for (Node node : sorted) {
            layers.get(node.layer).add(node);
        }
        layers.removeIf(List::isEmpty);
        for (List<Node> layer : layers) {
            renumber(layer);
        }
        return layers;
    }

    /**
     * 层内排序：一趟向下 + 一趟向上
     *
     * <p>只排一趟只能解开一半交叉 —— 向下的那趟把子节点往父节点
     * 的平均位置上拉，向上的那趟再反向修一次。
     */
    private void orderLayers(
            List<List<Node>> layers,
            Map<String, List<String>> parents,
            Map<String, List<String>> children) {
        for (int i = 1; i < layers.size(); i++) {
            sortByBarycenter(layers.get(i),
                    layers.get(i - 1), parents);
        }
        for (int i = layers.size() - 2;
                i >= 0; i--) {
            sortByBarycenter(layers.get(i),
                    layers.get(i + 1), children);
        }
    }

    /**
     * 按"邻居层里的平均序号"重排一层
     *
     * <p>没有邻居的节点重心记为正无穷，一律沉到层尾；
     * 它们之间的相对顺序靠 List.sort 的稳定性保住。
     */
    private void sortByBarycenter(
            List<Node> layer,
            List<Node> reference,
            Map<String, List<String>> adjacency) {
        Map<String, Integer> order = new HashMap<>();
        for (Node node : reference) {
            order.put(node.id, node.order);
        }
        layer.sort(Comparator
                .comparingDouble((Node node) ->
                        barycenter(
                                adjacency.get(node.id),
                                order))
                .thenComparing(node -> node.id));
        renumber(layer);
    }

    /** 邻居在本层的平均序号；没有可用邻居时返回正无穷 */
    private double barycenter(
            List<String> neighbours,
            Map<String, Integer> order) {
        if (neighbours == null
                || neighbours.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double sum = 0;
        int count = 0;
        for (String id : neighbours) {
            Integer position = order.get(id);
            if (position != null) {
                sum += position;
                count++;
            }
        }
        return count == 0
                ? Double.MAX_VALUE
                : sum / count;
    }

    private void renumber(List<Node> layer) {
        for (int i = 0; i < layer.size(); i++) {
            layer.get(i).order = i;
        }
    }

    // ==================== 坐标分配 ====================

    /**
     * 算坐标：分层 → 排序 → 分配 → 归一化
     *
     * @return 整图包围盒 {x, y, width, height}
     */
    private double[] place(
            List<Node> nodes, List<Edge> edges,
            double mainGap, boolean topDown) {
        List<List<Node>> layers = assignLayers(
                nodes, edges);
        Map<String, List<String>> parents =
                new LinkedHashMap<>();
        Map<String, List<String>> children =
                new LinkedHashMap<>();
        for (Node node : nodes) {
            parents.put(node.id, new ArrayList<>());
            children.put(node.id, new ArrayList<>());
        }
        for (Edge edge : edges) {
            children.get(edge.from).add(edge.to);
            parents.get(edge.to).add(edge.from);
        }
        orderLayers(layers, parents, children);
        return allocate(layers, mainGap, topDown);
    }

    /** 逐层摆位：主方向累加层间距，副方向层内依次排开并居中 */
    private double[] allocate(
            List<List<Node>> layers,
            double mainGap, boolean topDown) {
        double widestSpan = 0;
        for (List<Node> layer : layers) {
            widestSpan = Math.max(widestSpan,
                    spanOf(layer, topDown));
        }

        double mainPos = 0;
        for (List<Node> layer : layers) {
            double crossPos = (widestSpan
                    - spanOf(layer, topDown)) / 2.0;
            double maxMainSize = 0;
            for (Node node : layer) {
                maxMainSize = Math.max(maxMainSize,
                        topDown ? node.h : node.w);
            }
            for (Node node : layer) {
                if (topDown) {
                    node.px = crossPos;
                    node.py = mainPos;
                    crossPos += node.w + SIBLING_GAP;
                } else {
                    node.px = mainPos;
                    node.py = crossPos;
                    crossPos += node.h + SIBLING_GAP;
                }
            }
            mainPos += maxMainSize + mainGap;
        }
        return normalize(layers);
    }

    /** 一层在副方向上占的宽度 */
    private double spanOf(
            List<Node> layer, boolean topDown) {
        double span = 0;
        for (int i = 0; i < layer.size(); i++) {
            if (i > 0) {
                span += SIBLING_GAP;
            }
            Node node = layer.get(i);
            span += topDown ? node.w : node.h;
        }
        return span;
    }

    /** 平移到原点 + 吸附网格，返回包围盒 */
    private double[] normalize(
            List<List<Node>> layers) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        for (List<Node> layer : layers) {
            for (Node node : layer) {
                minX = Math.min(minX, node.px);
                minY = Math.min(minY, node.py);
            }
        }
        double maxX = 0;
        double maxY = 0;
        for (List<Node> layer : layers) {
            for (Node node : layer) {
                node.px = Math.round(
                        (node.px - minX) / GRID) * GRID;
                node.py = Math.round(
                        (node.py - minY) / GRID) * GRID;
                maxX = Math.max(maxX,
                        node.px + node.w);
                maxY = Math.max(maxY,
                        node.py + node.h);
            }
        }
        return new double[] {0, 0, maxX, maxY};
    }

    /**
     * 长宽比偏离"方块"的程度：|ln(宽/高)|
     *
     * <p>用对数而不是差值：2:1 与 1:2 的偏离是一样的，
     * 而宽/高的差值判法会把它们判成天差地别。
     */
    private double aspectPenalty(double[] box) {
        if (box[2] <= 0 || box[3] <= 0) {
            return Double.MAX_VALUE;
        }
        return Math.abs(Math.log(box[2] / box[3]));
    }

    /** 长宽比是否跑出允许区间（竖长条 / 扁长条） */
    private boolean aspectOutOfRange(double[] box) {
        if (box[2] <= 0 || box[3] <= 0) {
            return false;
        }
        double aspect = box[2] / box[3];
        return aspect < MIN_ASPECT
                || aspect > MAX_ASPECT;
    }

    // ==================== 编辑模式：避让既有元素 ====================

    /**
     * 编辑模式：把新增图形整组挪到不压住既有图形的位置
     *
     * <p>做法是<b>整组平移</b>而不是逐个让位 —— 新增元素之间的相对
     * 位置（层结构）必须保住，否则"分层摆好"这件事就白做了。
     */
    private void shiftToFreeSlot(
            List<Node> movable, List<Node> frozen) {
        double[] frozenBox = boundsOf(frozen, false);
        double[] movableBox = boundsOf(movable, true);
        double width = movableBox[2];
        double height = movableBox[3];

        // 候选位置依次向外扩：右侧 → 下方 → 再右一档 → 再下一档
        for (int i = 0; i < MAX_SLOT_TRIES; i++) {
            double dx;
            double dy;
            if (i % 2 == 0) {
                dx = frozenBox[0] + frozenBox[2]
                        + LAYER_GAP
                        + (i / 2) * (width + LAYER_GAP);
                dy = frozenBox[1];
            } else {
                dx = frozenBox[0];
                dy = frozenBox[1] + frozenBox[3]
                        + LAYER_GAP
                        + (i / 2) * (height + LAYER_GAP);
            }
            if (!collides(movable, frozen, dx, dy)) {
                translate(movable, dx, dy);
                LOG.info("布局避让：新增 {} 个图形平移到 ({}, {})",
                        movable.size(),
                        Math.round(dx), Math.round(dy));
                return;
            }
        }
        // 找不到空位就贴到既有图右侧最远处 ——
        // 宁可离得远，也不能压住用户已有的图
        double dx = frozenBox[0] + frozenBox[2]
                + LAYER_GAP
                + MAX_SLOT_TRIES * (width + LAYER_GAP);
        translate(movable, dx, frozenBox[1]);
        LOG.warn("布局避让未找到空位，新增图形放到既有图右侧");
    }

    /** 整组平移后是否与既有图形相撞 */
    private boolean collides(
            List<Node> movable, List<Node> frozen,
            double dx, double dy) {
        for (Node node : movable) {
            double ax = node.px + dx;
            double ay = node.py + dy;
            for (Node other : frozen) {
                if (overlaps(ax, ay, node.w, node.h,
                        other.x0, other.y0,
                        other.w0, other.h0)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 两个矩形是否重叠（外扩半个同层间距，留出呼吸空间） */
    private boolean overlaps(
            double ax, double ay, double aw, double ah,
            double bx, double by, double bw, double bh) {
        double pad = SIBLING_GAP / 2.0;
        return ax < bx + bw + pad
                && bx < ax + aw + pad
                && ay < by + bh + pad
                && by < ay + ah + pad;
    }

    private void translate(
            List<Node> nodes, double dx, double dy) {
        for (Node node : nodes) {
            node.px += dx;
            node.py += dy;
        }
    }

    /** 一组节点在指定坐标系下的包围盒 {x, y, width, height} */
    private double[] boundsOf(
            List<Node> nodes, boolean useNew) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Node node : nodes) {
            double x = useNew ? node.px : node.x0;
            double y = useNew ? node.py : node.y0;
            double w = useNew ? node.w : node.w0;
            double h = useNew ? node.h : node.h0;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + w);
            maxY = Math.max(maxY, y + h);
        }
        return new double[] {
            minX, minY,
            maxX - minX, maxY - minY,
        };
    }

    // ==================== 写回 ====================

    /** 把算好的坐标写回图形，并收拾未绑定箭头的几何 */
    private void writeBack(
            List<Node> movable,
            List<JsonNode> elements,
            List<Node> shapes) {
        Map<String, Node> moved = new LinkedHashMap<>();
        for (Node node : movable) {
            moved.put(node.id, node);
        }
        for (Node node : movable) {
            node.json.put("x", node.px);
            node.json.put("y", node.py);
            node.json.put("width", node.w);
            node.json.put("height", node.h);
        }
        // 未绑定的两点箭头：图形挪走了它却留在原地，看上去就是断了。
        // 只重算"两端命中的图形都移动了"的那些 —— 一端没动的
        // （比如连到冻结元素的线）保持原样，免得凭空制造几何漂移
        for (JsonNode element : elements) {
            reattach(element, shapes, moved);
        }
    }

    /** 重算一条未绑定箭头的端点，让它重新贴在两端图形的边界上 */
    private void reattach(
            JsonNode element,
            List<Node> shapes,
            Map<String, Node> moved) {
        if (!"arrow".equals(text(element, "type"))
                || element.hasNonNull("start")
                || element.hasNonNull("end")) {
            return;
        }
        JsonNode points = element.get("points");
        if (points == null
                || !points.isArray()
                || points.size() != 2
                || !finite(element, "x")
                || !finite(element, "y")
                || !isPoint(points.get(0))
                || !isPoint(points.get(1))) {
            return;
        }
        double[] from = {
            number(element, "x")
                    + points.get(0).get(0).asDouble(),
            number(element, "y")
                    + points.get(0).get(1).asDouble(),
        };
        double[] to = {
            number(element, "x")
                    + points.get(1).get(0).asDouble(),
            number(element, "y")
                    + points.get(1).get(1).asDouble(),
        };
        Node fromNode = shapeAt(shapes, from);
        Node toNode = shapeAt(shapes, to);
        if (fromNode == null || toNode == null
                || fromNode == toNode
                || !moved.containsKey(fromNode.id)
                || !moved.containsKey(toNode.id)) {
            return;
        }
        double[] start = boundaryPoint(
                fromNode, toNode);
        double[] end = boundaryPoint(
                toNode, fromNode);
        ObjectNode arrow = (ObjectNode) element;
        arrow.put("x", start[0]);
        arrow.put("y", start[1]);
        ArrayNode path = objectMapper.createArrayNode();
        path.add(pointNode(0, 0));
        path.add(pointNode(
                end[0] - start[0],
                end[1] - start[1]));
        arrow.set("points", path);
    }

    /**
     * 从图形中心朝目标中心射一条线，取它与图形边界的交点
     *
     * <p>这样箭头总是"贴着框画"的，长度随两框距离自然变化。
     */
    private double[] boundaryPoint(
            Node node, Node towards) {
        double cx = node.px + node.w / 2.0;
        double cy = node.py + node.h / 2.0;
        double dx = towards.px + towards.w / 2.0 - cx;
        double dy = towards.py + towards.h / 2.0 - cy;
        if (dx == 0 && dy == 0) {
            return new double[] {cx, cy};
        }
        double scaleX = dx == 0
                ? Double.MAX_VALUE
                : (node.w / 2.0) / Math.abs(dx);
        double scaleY = dy == 0
                ? Double.MAX_VALUE
                : (node.h / 2.0) / Math.abs(dy);
        double scale = Math.min(scaleX, scaleY);
        return new double[] {
            cx + dx * scale, cy + dy * scale,
        };
    }

    /**
     * 找出包含该点的最小图形（面积最小 = 最具体）
     *
     * <p><b>必须用原始坐标</b>：箭头存的是旧端点，而图形此刻已经被
     * 写成新坐标了，拿新坐标去命中只会处处不中。
     */
    private Node shapeAt(
            List<Node> shapes, double[] point) {
        Node best = null;
        double bestArea = Double.MAX_VALUE;
        for (Node node : shapes) {
            boolean inside =
                    point[0] >= node.x0 - SNAP_TOLERANCE
                    && point[0] <= node.x0 + node.w0
                            + SNAP_TOLERANCE
                    && point[1] >= node.y0 - SNAP_TOLERANCE
                    && point[1] <= node.y0 + node.h0
                            + SNAP_TOLERANCE;
            if (!inside) {
                continue;
            }
            double area = node.w0 * node.h0;
            if (area < bestArea) {
                bestArea = area;
                best = node;
            }
        }
        return best;
    }

    // ==================== 小工具 ====================

    private ArrayNode pointNode(double x, double y) {
        ArrayNode point = objectMapper.createArrayNode();
        point.add(x);
        point.add(y);
        return point;
    }

    /** 读元素的文字：图形是 label，箭头也是 label（字符串或对象） */
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
                ? inner.asText()
                : "";
    }

    /** 读绑定字段里的元素 id */
    private String bindingId(
            JsonNode element, String field) {
        JsonNode binding = element.get(field);
        if (binding == null || !binding.isObject()) {
            return null;
        }
        return text(binding, "id");
    }

    private boolean isPoint(JsonNode node) {
        return node != null
                && node.isArray()
                && node.size() == 2
                && node.get(0).isNumber()
                && node.get(1).isNumber();
    }

    private boolean finite(
            JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null
                && value.isNumber()
                && Double.isFinite(value.asDouble());
    }

    private boolean positive(
            JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null
                && value.isNumber()
                && value.asDouble() > 0;
    }

    private double number(
            JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber()
                ? value.asDouble()
                : 0.0;
    }

    private String text(
            JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual()
                ? null
                : value.asText();
    }

    // ==================== 内部结构 ====================

    /** 一个可摆放的图形 */
    private static final class Node {

        private final ObjectNode json;
        private final String id;
        private final String label;
        /** 原始几何：改过坐标之后就取不到旧值了，而命中判定要用它 */
        private final double x0;
        private final double y0;
        private final double w0;
        private final double h0;
        /** 布局算出的尺寸与位置 */
        private double w;
        private double h;
        private double px;
        private double py;
        private int layer;
        private int order;

        private Node(
                ObjectNode json, String id, String label,
                double x0, double y0,
                double w0, double h0) {
            this.json = json;
            this.id = id;
            this.label = label;
            this.x0 = x0;
            this.y0 = y0;
            this.w0 = w0;
            this.h0 = h0;
        }
    }

    /** 有向边：两端都绑定到图形的箭头 */
    private record Edge(String from, String to) {
    }
}
