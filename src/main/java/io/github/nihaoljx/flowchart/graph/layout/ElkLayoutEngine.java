package io.github.nihaoljx.flowchart.graph.layout;

import io.github.nihaoljx.flowchart.graph.transform.SceneGeometryKit;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
        return layout(json, fixedIds, true);
    }

    /**
     * 模块内布局：关掉"双向出区间取宽"那条视口启发式，保留竖排。
     *
     * <p>{@code viewportAware=false} 时只有"另一个方向的偏离度确实
     * 更小"才换向（那是真实的方向优化，如 3 个并列兄弟竖堆更紧凑），
     * 而"两个方向都出区间就取更宽"不再生效 —— 对模块来说，那等于
     * 把一条竖链压成横排（见 {@link LayoutEngine#layoutModule}）。
     */
    @Override
    public String layoutModule(
            String json, Set<String> fixedIds) {
        return layout(json, fixedIds, false);
    }

    /**
     * 指定主方向的模块内布局（2026-10-16 五图实测新增）。
     *
     * <p>方向由调用方（ScenePlanner 的整图试排）决定，这里只执行：
     * 认证图「AWS Cloud」该横成一条带、Transformer「DECODER」该竖
     * 成一列，判断依据是**整图打包后**的长宽比，模块自己的长宽比
     * 说明不了该横还是该竖。
     *
     * <p>与 {@link #layoutModule(String, Set)} 的另一处区别：不做
     * 方向择优、也不做"双向出区间取宽"，给定什么方向就排什么方向。
     */
    @Override
    public String layoutModule(
            String json, Set<String> fixedIds, boolean horizontal) {
        try {
            Prepared p = prepare(json, fixedIds);
            if (p == null) {
                return json;
            }
            // 星形拓扑同样不走分层：模块内部也可能是"一中心 + 若干
            // 互不相连的叶子"（如"消息总线 + 订阅者"分组）。判据极严
            // （唯一枢纽、叶子只连枢纽、无外人），认错了比不处理更糟，
            // 所以宁可在此多判一次。放射摆放无视方向参数 —— 星形没有
            // 主方向可言。
            if (p.frozen.isEmpty()
                    && tryRadial(p.movable, p.movableEdges, p.width)) {
                snapAll(p.movable);
                writeBack(p.movable);
                LOG.info("ELK 布局完成：{} 个图形呈星形放射摆放",
                        p.movable.size());
                return objectMapper.writeValueAsString(p.root);
            }
            double[] box = runElk(
                    p.movable, p.movableEdges, p.width, p.gap,
                    p.siblingGap,
                    horizontal ? Direction.RIGHT : Direction.DOWN);
            // snapAll 已并入 runElk（边路由吸附要用归位后的坐标）
            if (!p.frozen.isEmpty()) {
                shiftToFreeSlot(p.movable, p.frozen);
            }
            writeBack(p.movable);
            LOG.info("ELK 布局完成：{} 个图形（冻结 {}），主方向 {}，"
                            + "包围盒 {}x{}",
                    p.movable.size(), p.frozen.size(),
                    horizontal ? "自左而右" : "自上而下",
                    Math.round(box[2]), Math.round(box[3]));
            return objectMapper.writeValueAsString(p.root);
        } catch (Exception e) {
            LOG.warn("ELK 布局跳过（{}）", e.getMessage());
            return json;
        }
    }

    private String layout(String json, Set<String> fixedIds,
            boolean viewportAware) {
        try {
            Prepared p = prepare(json, fixedIds);
            if (p == null) {
                return json;
            }
            List<Shape> movable = p.movable;
            List<Shape> frozen = p.frozen;
            List<Edge> movableEdges = p.movableEdges;
            double width = p.width;
            double gap = p.gap;
            double siblingGap = p.siblingGap;

            // 星形拓扑（一个中心 + 若干互不相连的叶子）不走分层：
            // ELK 的 layered 算法会把星形排成"中心在顶、叶子竖堆"的
            // 树，而这类图（开放式创新模型、辐射状拓扑）的参考形态
            // 就是放射摆放 —— 五图实测里它被排成一列，相似度约 25%。
            // 只在新建路径生效：编辑模式有冻结坐标，不重新摆放。
            if (frozen.isEmpty()
                    && tryRadial(movable, movableEdges, width)) {
                snapAll(movable);
                writeBack(movable);
                LOG.info("ELK 布局完成：{} 个图形呈星形放射摆放",
                        movable.size());
                return objectMapper.writeValueAsString(p.root);
            }

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
            LOG.info("方向试排：down={}x{} penalty={} outOfRange={} | "
                            + "right={}x{} penalty={} outOfRange={}",
                    Math.round(down[2]), Math.round(down[3]),
                    LayoutSpec.aspectPenalty(down[2], down[3]),
                    LayoutSpec.aspectOutOfRange(down[2], down[3]),
                    Math.round(right[2]), Math.round(right[3]),
                    LayoutSpec.aspectPenalty(right[2], right[3]),
                    LayoutSpec.aspectOutOfRange(right[2], right[3]));
            if (LayoutSpec.aspectOutOfRange(down[2], down[3])
                    && LayoutSpec.aspectPenalty(right[2], right[3])
                    < LayoutSpec.aspectPenalty(down[2], down[3])) {
                topDown = false;
                chosen = right;
            } else if (viewportAware
                    && LayoutSpec.aspectOutOfRange(down[2], down[3])
                    && LayoutSpec.aspectOutOfRange(
                            right[2], right[3])) {
                // 双向都出区间（链状/树形图）：换向只是转置包围盒，
                // 往往救不了长宽比 —— 这一步只该在"转置确实更接近
                // 理想区间"时才做。
                //
                // 2026-10-17 修正：原判据只有 `right[3] < down[3]`，
                // 即"横排更矮就横过来"。对链状流程图它**恒成立**：
                // 横排高度恒等于最大单节点高（约 60~120），竖排高度恒
                // 等于节点数×层高（5 个节点就已 500+），于是任何 5 节点
                // 以上的流程图都会被转置成 9:1 的细长横条，直接违背
                // 流程图模板"主流程从上往下单向推进"的要求（实测请假
                // 审批图：5 节点排成 1120x120，纵向距离 0px；7 节点链
                // dx=1320/dy=0）。模块路径正因同一原因关掉了这条启发式
                // （见 layoutModule 的 javadoc），整图路径漏了。
                //
                // 现在要求横排的偏离度严格小于竖排，才允许转置：真正的
                // 层级图（3 层×5 列）横排能从 5:3 的方块救回接近 16:9；
                // 而链状图横竖两个方向都是极端值、谁都不在区间内，代价
                // 相近或竖排更优，于是保持竖排。
                if (right[3] < down[3]
                        && LayoutSpec.aspectPenalty(right[2], right[3])
                        < LayoutSpec.aspectPenalty(down[2], down[3])) {
                    topDown = false;
                    chosen = right;
                    // RIGHT 那一跑的坐标已是最新，无需重跑
                    snapAll(movable);
                    if (!frozen.isEmpty()) {
                        shiftToFreeSlot(movable, frozen);
                    }
                    writeBack(movable);
                    LOG.info("ELK 布局完成：{} 个图形（冻结 {}），"
                                    + "主方向 {}（双向出区间，横排偏离度更小），"
                                    + "包围盒 {}x{}",
                            movable.size(), frozen.size(), "自左而右",
                            Math.round(chosen[2]),
                            Math.round(chosen[3]));
                    return objectMapper.writeValueAsString(p.root);
                }
                // DOWN 更优（或横排并不更优）：**必须重跑 DOWN 拿回坐标**。
                //
                // 2026-10-17 修正的第二个缺陷：这里原本注释"DOWN 已更宽，
                // 坐标就是最新，直接落"，但那是错的 —— 上一次 runElk
                // 跑的是 RIGHT，Shape 的 px/py 已被横排坐标覆盖，包围盒
                // down[] 只是当时算出来的数字，不代表内存里还是竖排坐标。
                // 症状：日志写"主方向 自上而下、包围盒 160x540"，实际写进
                // JSON 的却是 right 的横排坐标（实测 first=(0,0)
                // second=(220,0)，应为 (0,120)）。这正是"改对了判据却看不出
                // 效果"的原因。
                runElk(movable, movableEdges, width, gap, siblingGap,
                        Direction.DOWN);
                snapAll(movable);
                writeBack(movable);
                LOG.info("ELK 布局完成：{} 个图形（冻结 {}），"
                                + "主方向 {}（双向出区间取宽），"
                                + "包围盒 {}x{}",
                        movable.size(), frozen.size(), "自上而下",
                        Math.round(chosen[2]),
                        Math.round(chosen[3]));
                return objectMapper.writeValueAsString(p.root);
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
                    .writeValueAsString(p.root);
        } catch (Exception e) {
            LOG.warn("ELK 布局跳过（{}）", e.getMessage());
            return json;
        }
    }

    // ==================== 场景准备 ====================

    /**
     * 布局前的公共准备：读场景、收图形与边、分冻结/可移动、
     * 算统一宽度与两档间距。两种入口（单层 layout / 指定方向
     * layoutModule）共用，避免两边各抄一份还抄歪。
     *
     * @return 准备好的场景；没有可摆放图形时返回 null
     *         （调用方应原样返回入参）
     * @throws Exception 场景 JSON 解析失败等（调用方都在 try 内）
     */
    private Prepared prepare(String json, Set<String> fixedIds)
            throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode elementsNode = root.get("elements");
        if (elementsNode == null
                || !elementsNode.isArray()) {
            return null;
        }
        List<JsonNode> elements = new ArrayList<>();
        for (JsonNode element : elementsNode) {
            elements.add(element);
        }
        List<Shape> shapes = collectShapes(elements);
        if (shapes.isEmpty()) {
            return null;
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
            return null;
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

        return new Prepared(root, movable, frozen, movableEdges,
                width, gap, siblingGap);
    }

    /** 一次布局的公共准备产物（{@link #prepare} 的输出） */
    private static final class Prepared {
        private final JsonNode root;
        private final List<Shape> movable;
        private final List<Shape> frozen;
        private final List<Edge> movableEdges;
        private final double width;
        private final double gap;
        private final double siblingGap;

        private Prepared(
                JsonNode root, List<Shape> movable, List<Shape> frozen,
                List<Edge> movableEdges, double width, double gap,
                double siblingGap) {
            this.root = root;
            this.movable = movable;
            this.frozen = frozen;
            this.movableEdges = movableEdges;
            this.width = width;
            this.gap = gap;
            this.siblingGap = siblingGap;
        }
    }

    // ==================== 星形放射布局 ====================

    /**
     * 识别"一个中心 + 若干互不相连的叶子"的星形拓扑，并放射摆放。
     *
     * <p>判据（三条同时满足才认，宁可漏认不可错认 —— 错认会把
     * 本该分层的图摆成放射，比不处理更糟）：
     *
     * ① 存在唯一枢纽：度数 ≥ 3；
     * ② 枢纽的所有邻居度数都是 1（只连枢纽，彼此不相连）；
     * ③ 图里没有"外人"—— 每个图形要么是枢纽要么是枢纽的邻居。
     *
     * <p>摆法：枢纽居中小圆心、叶子均布在外圆上（首枚叶在正上方、
     * 顺时针），半径取"相邻叶子盒不重叠"与"叶盒不压枢纽盒"的
     * 较大值；边画成枢纽盒边缘到叶盒边缘的直线（3 点共线，
     * 与 {@link #writeBackEdgeRoutes} 同协议）。
     *
     * @return true = 已识别并摆好（坐标已写入 Shape，边已写回箭头）
     */
    private boolean tryRadial(
            List<Shape> shapes, List<Edge> edges, double width) {
        Map<String, Shape> byId = new LinkedHashMap<>();
        Map<String, List<Shape>> neighbors =
                new LinkedHashMap<>();
        for (Shape shape : shapes) {
            byId.put(shape.id, shape);
            neighbors.put(shape.id, new ArrayList<>());
        }
        for (Edge edge : edges) {
            Shape from = byId.get(edge.from);
            Shape to = byId.get(edge.to);
            if (from == null || to == null || from == to) {
                continue;
            }
            neighbors.get(edge.from).add(to);
            neighbors.get(edge.to).add(from);
        }
        Shape hub = null;
        for (Shape shape : shapes) {
            int degree = neighbors.get(shape.id).size();
            if (degree >= 3) {
                if (hub != null) {
                    return false; // 两个枢纽：不是单纯星形
                }
                hub = shape;
            }
        }
        if (hub == null) {
            return false;
        }
        List<Shape> leaves = neighbors.get(hub.id);
        for (Shape leaf : leaves) {
            if (neighbors.get(leaf.id).size() != 1) {
                return false; // 叶子还连着别人：不是单纯星形
            }
        }
        if (leaves.size() + 1 != shapes.size()) {
            return false; // 有外人：交给分层布局
        }

        double[] hubSize = LayoutSpec.nodeSize(
                hub.type, hub.label, width);
        hub.lw = hubSize[0];
        hub.lh = hubSize[1];
        double leafW = 0;
        double leafH = 0;
        for (Shape leaf : leaves) {
            double[] size = LayoutSpec.nodeSize(
                    leaf.type, leaf.label, width);
            leaf.lw = size[0];
            leaf.lh = size[1];
            leafW = Math.max(leafW, leaf.lw);
            leafH = Math.max(leafH, leaf.lh);
        }
        int n = leaves.size();
        // 半径下限一：相邻两枚叶子的盒不重叠（弦长 ≥ 叶宽 + 兄弟间距）
        double rByChord = (leafW + LayoutSpec.SIBLING_GAP)
                / (2 * Math.sin(Math.PI / n));
        // 半径下限二：叶盒不压枢纽盒（两盒心距 ≥ 两盒半宽和 + 模块通道）
        double rByHub = (hub.lw + leafW) / 2 + LayoutSpec.MODULE_GAP;
        double radius = Math.max(rByChord, rByHub);

        hub.px = -hub.lw / 2;
        hub.py = -hub.lh / 2;
        for (int i = 0; i < n; i++) {
            Shape leaf = leaves.get(i);
            // 首枚叶在正上方，顺时针均布
            double angle = -Math.PI / 2 + i * 2 * Math.PI / n;
            double cx = radius * Math.cos(angle);
            double cy = radius * Math.sin(angle);
            leaf.px = cx - leaf.lw / 2;
            leaf.py = cy - leaf.lh / 2;
        }
        // 归一化到左上角 (0,0)，与分层布局的输出口径一致
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        for (Shape shape : shapes) {
            minX = Math.min(minX, shape.px);
            minY = Math.min(minY, shape.py);
        }
        for (Shape shape : shapes) {
            shape.px -= minX;
            shape.py -= minY;
        }
        // 先归位再画边：吸附用的 px/py 必须是最终写回的坐标。
        // 历史缺陷：这里先 writeStraightRoute 再 snapAll，箭头端点按
        // 未量化的 (330,380,...) 算，而图形坐标随后被 snapAll 挪到
        // (340,340,...) ⇒ 端点与图形错开整整一个 quantize（用户
        // m06890 截图里菱形出边"平行悬空 10px"就是这一格）。
        // 与 runElk 的「snapAll 先于 writeBackEdgeRoutes」同口径。
        snapAll(shapes);
        for (Edge edge : edges) {
            Shape from = byId.get(edge.from);
            Shape to = byId.get(edge.to);
            if (from == null || to == null) {
                continue;
            }
            writeStraightRoute(edge, from, to);
        }
        return true;
    }

    /**
     * 把一条边写成"源盒边缘 → 目标盒边缘"的 3 点折线。
     *
     * <p>协议与 {@link #writeBackEdgeRoutes} 一致：x / y = 首点绝对
     * 坐标，points = 相对首点的偏移、首点 [0,0]。补足 3 点是为了
     * 存活 SceneSanitizer 对绑定箭头"少于 3 点即摘"的口径。
     *
     * <p>中间点放在**直角拐点**上，不是两端点连线的中点。星形放射布局
     * 里枢纽与叶子的盒心连线天然是斜的，若把中点取在斜线上，三点共线
     * ⇒ 整条边是一根斜线：既违反"全正交"契约，又会把画布包围盒撑大
     * （实测 state-machine 的 processing→已取消/已完成 两根辐条斜着
     * 扎到 x=-293，整图被撑出 293px 空白）。所以这里走 Z 形：
     * 先沿长轴走一半，再横过去，最后沿长轴收进目标。
     */
    private void writeStraightRoute(
            Edge edge, Shape from, Shape to) {
        double fx = from.px + from.lw / 2;
        double fy = from.py + from.lh / 2;
        double tx = to.px + to.lw / 2;
        double ty = to.py + to.lh / 2;
        double dx = tx - fx;
        double dy = ty - fy;
        if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) {
            return;
        }
        double[] start = slopedExit(fx, fy, tx, ty, from);
        double[] end = slopedExit(tx, ty, fx, fy, to);
        double ex = end[0] - start[0];
        double ey = end[1] - start[1];
        // 拐点：水平位移更大就「先横后竖」，否则「先竖后横」；
        // 单侧位移接近 0（本来就正交）时不插拐点，退化成 2 点直线，
        // 由后面的补点逻辑补中点。
        List<double[]> route = new ArrayList<>();
        route.add(new double[] {0, 0});
        if (Math.abs(ex) > 0.5 && Math.abs(ey) > 0.5) {
            if (Math.abs(ex) >= Math.abs(ey)) {
                route.add(new double[] {round1(ex), 0});
            } else {
                route.add(new double[] {0, round1(ey)});
            }
        }
        route.add(new double[] {round1(ex), round1(ey)});
        // 统一走一遍几何后处理：合并共线冗余弯，保证全正交。
        // 直线边化简后可能被压回 2 点，补回中点维持 ≥3 点不变式。
        route = Polyline.orthogonalizeAndSimplify(route);
        if (route.size() == 2) {
            double[] a = route.get(0);
            double[] b = route.get(1);
            route.add(1, new double[] {
                    round1((a[0] + b[0]) / 2),
                    round1((a[1] + b[1]) / 2) });
        }
        ObjectNode arrow = edge.json;
        ArrayNode points = arrow.putArray("points");
        for (double[] point : route) {
            appendPoint(points, point[0], point[1]);
        }
        arrow.put("x", round1(start[0]));
        arrow.put("y", round1(start[1]));
    }

    /**
     * 星形放射摆放的端点吸附：菱形/椭圆贴真实斜边，矩形仍走
     * 外接矩形近似。
     *
     * <p>{@link #boxExit} 的方向是 (dx, dy) = 两端盒心连线，本方法
     * 改用「from 心 → to 心」这条射线重新求交点 —— 两条射线本应同向，
     * 独立成方法只是为了少传两个参数。
     */
    private static double[] slopedExit(
            double fx, double fy, double tx, double ty, Shape from) {
        double[] box = new double[] {
                from.px, from.py, from.lw, from.lh,
        };
        if (SceneGeometryKit.hasSlopedBoundary(from.type)) {
            return SceneGeometryKit.edgePointOnShape(
                    new double[] {fx, fy},
                    new double[] {tx, ty}, box, from.type);
        }
        return SceneGeometryKit.edgePointOnBox(
                new double[] {fx, fy},
                new double[] {tx, ty}, box);
    }

    /**
     * 从盒心 (cx, cy) 沿方向 (dx, dy) 出发，算出穿出盒边缘的点。
     * 盒按矩形近似（椭圆/菱形的外接矩形）—— 端点由前端吸附逻辑
     * 再做一次归位，这里只保证"起点落在盒外不远处"。
     */
    private static double[] boxExit(
            double cx, double cy, double dx, double dy,
            double w, double h) {
        double halfW = w / 2;
        double halfH = h / 2;
        double scale = Double.MAX_VALUE;
        if (Math.abs(dx) > 1e-6) {
            scale = Math.min(scale, halfW / Math.abs(dx));
        }
        if (Math.abs(dy) > 1e-6) {
            scale = Math.min(scale, halfH / Math.abs(dy));
        }
        if (!Double.isFinite(scale) || scale <= 0) {
            scale = 0;
        }
        return new double[] {
            cx + dx * scale, cy + dy * scale,
        };
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
        // 先归位再写边路由：端点吸附要按形状"落地后的最终坐标"算，
        // 否则吸附用的 px/py 与随后 snapAll 的结果差一个 quantize，
        // 菱形斜边又会漏出零点几到几像素的缝（m06890 的悬空就是
        // 多个口径对不上叠出来的）。
        snapAll(shapes);
        writeBackEdgeRoutes(elkEdges, shapes);
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
     *
     * 2026-10-15 二次改口径（回归实测 regress_arch 5 条箭头端点
     * 距目标 70~437px 悬空）：直线边**一律写回**，不再留给前端
     * 重算 —— 悬空的 5 条全部是走前端重算分支的直线边，而
     * ELK 写回 points 的箭头（吸附分支）0 悬空。直线路由补中点
     * 成 3 点共线折线，恰好 ≥3 点存活 SceneSanitizer 的摘除口径。
     */
    private void writeBackEdgeRoutes(
            Map<Edge, ElkEdge> elkEdges, List<Shape> shapes) {
        Map<String, Shape> byId = new LinkedHashMap<>();
        for (Shape shape : shapes) {
            byId.put(shape.id, shape);
        }
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
            // 端点的形状感知吸附：ELK 的 section start/end 落在节点的
            // **外接矩形**边上（它看不到菱形斜边 / 椭圆弧），于是菱形四
            // 条出边都离真实边界差一个内接正方形半径 —— 用户 m06890
            // 截图里就是那 ≈10px 的「箭头起点悬空」。
            //
            // 另一个必须修的坑：ELK 的 section 起点/终点与**箭头方向**
            // 无关。2026-10-18 单层探针（hub 菱形上、k0 矩形下）实测
            // start = (80,210) 恰是 k0 的中心，末点才落在 k0 上边。
            // 第一版吸附用「start → toCenter」当射线方向，此时
            // start == toCenter ⇒ 方向退化 → 直接原样返回，白改。
            // 改成「fromCenter → toCenter」这条恒定有向的射线：起点
            // 是 from 的中心（必在 from 内），第一个正交点就是 from
            // 边界上的出射点；end 同理用 toCenter → fromCenter。
            Shape fromShape = byId.get(entry.getKey().from);
            Shape toShape = byId.get(entry.getKey().to);
            if (fromShape != null && toShape != null) {
                double[] fromCenter = {
                        fromShape.px + fromShape.lw / 2,
                        fromShape.py + fromShape.lh / 2,
                };
                double[] toCenter = {
                        toShape.px + toShape.lw / 2,
                        toShape.py + toShape.lh / 2,
                };
                double[] start;
                double[] end;
                if (SceneGeometryKit.hasSlopedBoundary(fromShape.type)) {
                    start = SceneGeometryKit.edgePointOnShape(
                            fromCenter, toCenter,
                            new double[] {fromShape.px, fromShape.py,
                                    fromShape.lw, fromShape.lh},
                            fromShape.type);
                } else {
                    start = SceneGeometryKit.edgePointOnBox(
                            fromCenter, toCenter,
                            new double[] {fromShape.px, fromShape.py,
                                    fromShape.lw, fromShape.lh});
                }
                if (SceneGeometryKit.hasSlopedBoundary(toShape.type)) {
                    end = SceneGeometryKit.edgePointOnShape(
                            toCenter, fromCenter,
                            new double[] {toShape.px, toShape.py,
                                    toShape.lw, toShape.lh},
                            toShape.type);
                } else {
                    end = SceneGeometryKit.edgePointOnBox(
                            toCenter, fromCenter,
                            new double[] {toShape.px, toShape.py,
                                    toShape.lw, toShape.lh});
                }
                startX = start[0];
                startY = start[1];
                endX = end[0];
                endY = end[1];
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
            if (section.getBendPoints().isEmpty()) {
                // 直线路由：补中点凑足 3 点（≥3 才不被摘）
                route.add(new double[] {
                    round1((endX - startX) / 2),
                    round1((endY - startY) / 2),
                });
            }
            route.add(new double[] {
                round1(endX - startX),
                round1(endY - startY),
            });
            // 几何后处理：强制全正交 + 最少拐弯。ELK 在复杂图里偶尔给出
            // 末段斜线（端点未与中段轴对齐），历史产物也本就带斜线段；
            // 这里把斜线拆成直角弯，并合并共线冗余弯，兑现「线不乱、拐弯少」。
            route = Polyline.orthogonalizeAndSimplify(route);
            // 直线边化简后可能被压回 2 点；补回中点维持 ≥3 点不变式，
            // 保证「直线边由引擎接管」不被 SceneSanitizer 的摘除口径吞掉
            // （2026-10-15 修复的依赖：直线边不再留给前端重算）。
            if (route.size() == 2) {
                double[] a = route.get(0);
                double[] b = route.get(1);
                route.add(1, new double[] {
                        round1((a[0] + b[0]) / 2),
                        round1((a[1] + b[1]) / 2) });
            }
            if (route.size() < 2) {
                continue;
            }
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
