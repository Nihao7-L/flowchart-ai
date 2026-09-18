package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排版层：结构规划（v2-39 / 模块计划 m8）＋ frame 成员归属（v2-40 P1）。
 *
 * <p><b>它补的是链路里缺的那一步</b>：原链路
 * {@code LLM → Binder → Deduper → LayoutEngine → Validator} 只有"一次性几何求解"，
 * LLM 吐出的是一份<b>扁平</b>元素列表（无层级、无分组），布局引擎只能"每层居中
 * 叠罗汉" —— 于是复杂图必然翻车（跨层长边的标签砸在中间层节点上、竖排把图收成
 * 一根窄条 680×4820）。本类在 {@code Deduper} 与 {@code LayoutEngine} 之间插入
 * <b>结构规划</b>：先算「模块 → 模块成员 → 模块间关系」，再按分区落几何。
 *
 * <p><b>为什么不靠调间距</b>（2026-09-18 实测，勿重复试）：垂直层间距拉 16 倍
 * （340→5440px），标签压节点数 2→2 纹丝不动，画布反而涨到 76220px 高 ——
 * 因为标签渲染位置 = 箭头几何中点（Excalidraw 现算），而垂直拉伸是仿射变换、
 * 中点仍是中点：跨 2 层的边其中点永远落在中间那一层。有效手段只有
 * <b>层间横向错位 / 分区</b>，那正是本类的产物。
 *
 * <p><b>两级布局，而不是 ELK 的 compound graph</b>：模块内部交给现成的
 * {@link LayoutEngine}（竖排已验证可用），模块之间用<b>确定性网格打包</b>
 * （列数按长宽比择优）—— 后者只有几十行、纯函数、可单测，而 compound graph
 * 要处理父子坐标系换算与跨层边路由，踩坑面大得多。
 *
 * <p><b>模块从哪来（5.2 的三级降级）</b>：
 * <ol>
 *   <li><b>① LLM 标注</b>（主）：提示词要求图形数超 {@link #MODULE_THRESHOLD}
 *       时给每个图形写 {@code group}；</li>
 *   <li><b>② 后端聚类兜底</b>：LLM 没给 → 按拓扑连通分量 / 标签前缀聚类
 *       （见 {@link #inferGroups}，有 frame 时不启用）；</li>
 *   <li><b>③ 不分组</b>：兜底也拿不到成形分区 → 走单层布局，行为与改造前一致。</li>
 * </ol>
 *
 * <p><b>零回归是硬约束</b>：以下三种情况<b>完全不介入</b>，直接返回引擎输出
 * （字节级原样）——
 * <ol>
 *   <li>编辑模式（{@code fixedIds} 非空）：既有坐标一个不许动，否则与
 *       {@link SceneDrift} 当场打架；</li>
 *   <li>图形数 ≤ {@link #MODULE_THRESHOLD}（默认 12）：小图不该为不存在的
 *       复杂度买单，也避免把"加一个审批节点"这类图排变样；</li>
 *   <li>模块数 &lt; 2：只有"有分组"才谈得上分区。</li>
 * </ol>
 *
 * <p><b>异常一律降级</b>：任何一步抛错都退回单层布局，绝不让排版把整张图
 * 搞没 —— 与 {@code SceneBinder} / {@code SceneDeduper} 的容错口径一致。
 */
@Component
public class ScenePlanner {

    private static final Logger LOG =
            LoggerFactory.getLogger(ScenePlanner.class);

    /** 参与排版的图形类型（与 {@link ElkLayoutEngine} 同口径） */
    private static final Set<String> SHAPE_TYPES =
            Set.of("rectangle", "ellipse", "diamond");

    /** 图形数超过它才做分区（**唯一真相源**：PromptService 的提示词与契约描述都引用它） */
    public static final int MODULE_THRESHOLD = 12;

    /** 一个模块至少要有几个图形（兜底聚类用；1 个图形的"模块"没有意义） */
    private static final int MIN_CLUSTER_SIZE = 2;

    /** 模块数上限：超出说明聚得没有意义，宁可退回单层布局 */
    private static final int MAX_MODULE_COUNT = 5;

    /** 兜底聚类的最低覆盖率：簇只装下少数图形时不值得为它重排整张图 */
    private static final double MIN_CLUSTER_COVERAGE = 0.6;

    /** 标签前缀聚类用的分隔符（"订单-创建" → 模块"订单"） */
    private static final String LABEL_SEPARATORS = "-_/·:：|｜ ";

    /** 模块名的最大长度：超长截断（LLM 偶尔会把一句话填进来） */
    private static final int MAX_GROUP_NAME = 24;

    /** 打包时的目标长宽比：模块横排 + 模块内竖排 → 整体略宽比正方好看 */
    private static final double TARGET_ASPECT = 1.5;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScenePlanner() {
        LOG.info("排版层：结构规划已就绪（图形数 > {} 且模块数 ≥ 2 时生效）",
                MODULE_THRESHOLD);
    }

    /**
     * 排版入口：与 {@link LayoutEngine} 同签名，便于在生成链路里直接替换调用。
     *
     * @param json     场景 JSON（bind + dedup 之后）
     * @param engine   单层布局引擎 —— 排版只在"模块内部"复用它，不替代它
     * @param fixedIds 冻结 id 全集；非空表示编辑模式（排版不介入）
     * @return 排版并布局后的场景 JSON
     */
    public String arrange(
            String json, LayoutEngine engine, Set<String> fixedIds) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode elementsNode = root.get("elements");
            if (elementsNode == null || !elementsNode.isArray()) {
                return engine.layout(json, fixedIds);
            }
            List<JsonNode> elements = new ArrayList<>();
            elementsNode.forEach(elements::add);

            boolean editMode = fixedIds != null && !fixedIds.isEmpty();
            boolean dirtyChildren = hasChildren(elements);
            Partition partition = partition(elements);

            if (editMode || !partition.active()) {
                String laid = engine.layout(json, fixedIds);
                // 零回归路径：只要画布里没有"LLM 越权填的 children"，
                // 就把引擎的输出原样返回 —— 一个字节都不碰
                return dirtyChildren ? stripChildren(laid) : laid;
            }
            return arrangeModules(root, partition, engine);
        } catch (Exception e) {
            LOG.warn("排版跳过（{}），退回单层布局", e.getMessage());
            return safeLayout(engine, json, fixedIds);
        }
    }

    private String safeLayout(
            LayoutEngine engine, String json, Set<String> fixedIds) {
        try {
            return engine.layout(json, fixedIds);
        } catch (Exception e) {
            LOG.warn("单层布局也失败（{}），原样返回场景", e.getMessage());
            return json;
        }
    }

    // ==================== 结构规划 ====================

    /**
     * 分区：把每个图形按 {@code group} 归入模块，建成一棵模块树。
     *
     * <p>{@code group} 的形态由契约约束（{@code oneOf} 字符串 / 字符串数组），
     * 但这里一律<b>宽容归一化</b>——空串、非字符串、超长、空数组都被
     * 降级成"未分组"，绝不抛异常中断生成（v2-39 验收口径之一）。
     * 数组的语义与渲染层的 {@code groupIds} 一致：<b>从内到外</b>，
     * 例如 {@code ["下单", "订单域"]} 表示"下单"隶属于"订单域"。
     */
    private Partition partition(List<JsonNode> elements) {
        List<Node> nodes = collectNodes(elements);

        // ① LLM 标注：图形自带的 group 直接建模块树
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Node node : nodes) {
            List<String> path = groupPath(node.json);
            if (!path.isEmpty()) {
                grouped.put(node.id, path);
            }
        }
        Block root = buildTree(nodes, grouped);
        int moduleCount = countModules(root);

        // ② 后端聚类兜底：图形多、而 LLM 没给出成形分组时才启用
        //   （5.2 的降级设计；③ 兜底也拿不到 → 单层布局，行为与改造前一致）
        if (moduleCount < 2 && nodes.size() > MODULE_THRESHOLD
                && !hasFrame(elements)) {
            Map<String, List<String>> inferred = inferGroups(nodes, elements);
            if (!inferred.isEmpty()) {
                root = buildTree(nodes, inferred);
                moduleCount = countModules(root);
                LOG.info("分组兜底：LLM 未标注模块，按拓扑/标签聚出 {} 个模块",
                        moduleCount);
            }
        }

        matchFrames(root, elements);
        boolean active = nodes.size() > MODULE_THRESHOLD
                && moduleCount >= 2;
        return new Partition(
                root, elements, nodes, active, moduleCount);
    }

    /** 收集参与排版的图形（与 {@link #SHAPE_TYPES} 同口径，缺 id 的丢弃） */
    private List<Node> collectNodes(List<JsonNode> elements) {
        List<Node> nodes = new ArrayList<>();
        for (JsonNode element : elements) {
            if (!element.isObject()
                    || !SHAPE_TYPES.contains(text(element, "type"))) {
                continue;
            }
            String id = text(element, "id");
            if (id == null) {
                continue;
            }
            nodes.add(new Node((ObjectNode) element, id,
                    number(element, "width"), number(element, "height")));
        }
        return nodes;
    }

    /**
     * 按「图形 id → 从内到外的模块名」建模块树；没有分组的图形留在根块
     * （根块自己的图形会被当成一个整块参与打包，不会丢）。
     */
    private Block buildTree(
            List<Node> nodes, Map<String, List<String>> grouped) {
        Block root = new Block(null);
        for (Node node : nodes) {
            List<String> path = grouped.get(node.id);
            Block cursor = root;
            if (path != null) {
                List<String> outerFirst = new ArrayList<>(path);
                Collections.reverse(outerFirst);
                for (String name : outerFirst) {
                    Block child = cursor.byName.get(name);
                    if (child == null) {
                        child = new Block(name);
                        cursor.byName.put(name, child);
                        cursor.children.add(child);
                    }
                    cursor = child;
                }
            }
            cursor.shapes.add(node);
        }
        return root;
    }

    private int countModules(Block root) {
        List<Block> all = new ArrayList<>();
        flatten(root, all);
        return all.size();
    }

    private boolean hasFrame(List<JsonNode> elements) {
        for (JsonNode element : elements) {
            if (element.isObject()
                    && "frame".equals(text(element, "type"))) {
                return true;
            }
        }
        return false;
    }

    // ==================== ② 聚类兜底 ====================

    /**
     * LLM 没标注模块时的兜底聚类（v2-39 5.2 第 ② 级）。
     *
     * <p>两步，按可靠性排序，任一步拿到「成形分区」就用它：
     * <ol>
     *   <li><b>拓扑连通性</b>：只看"两端都是图形"的箭头，取无向连通分量。
     *       天然把"互不相连的几块"分开 —— 这在架构图里就是子系统；</li>
     *   <li><b>标签前缀</b>：图形文字形如 {@code 订单-创建} / {@code 订单_支付}
     *       时，按分隔符取首段当模块名（中文标签常见的命名习惯）。</li>
     * </ol>
     *
     * <p><b>为什么有 frame 时不做兜底</b>：兜底聚类只能给出「模块1 / 模块2」
     * 这类无名模块，而 {@link #matchFrames} 是按<b>名字</b>认领 frame 的 ——
     * 认领不上就"原样不动"。于是节点被重排到别处、框还留在 LLM 给的旧位置，
     * 正是 5.4 实测的那个病症（24/42 个图形跑出框）被加重。这种情况交给
     * 提示词去修（要求 frame 的 name 与模块名逐字相同），不做几何猜测。
     *
     * @return 图形 id → 模块名路径；拿不到成形分区时返回空表（= 不分组）
     */
    private Map<String, List<String>> inferGroups(
            List<Node> nodes, List<JsonNode> elements) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node node : nodes) {
            byId.put(node.id, node);
        }
        Map<String, List<String>> named = new LinkedHashMap<>();

        List<List<Node>> components =
                connectedComponents(nodes, elements, byId);
        if (validClusters(components, nodes.size())) {
            int index = 0;
            for (List<Node> cluster : components) {
                index++;
                String name = "模块" + index;
                for (Node node : cluster) {
                    named.put(node.id, List.of(name));
                }
            }
            return named;
        }

        Map<String, List<Node>> byPrefix = clusterByLabelPrefix(nodes);
        if (validClusters(new ArrayList<>(byPrefix.values()), nodes.size())) {
            for (Map.Entry<String, List<Node>> entry : byPrefix.entrySet()) {
                for (Node node : entry.getValue()) {
                    named.put(node.id, List.of(entry.getKey()));
                }
            }
            return named;
        }
        named.clear();
        return named;
    }

    /** 无向连通分量（只认两端都落在图形集合里的箭头；孤立图形自成一份） */
    private List<List<Node>> connectedComponents(
            List<Node> nodes, List<JsonNode> elements,
            Map<String, Node> byId) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (Node node : nodes) {
            adjacency.put(node.id, new ArrayList<>());
        }
        for (JsonNode element : elements) {
            if (!"arrow".equals(text(element, "type"))) {
                continue;
            }
            String from = bindingId(element, "start");
            String to = bindingId(element, "end");
            if (from == null || to == null
                    || !adjacency.containsKey(from)
                    || !adjacency.containsKey(to)) {
                continue;
            }
            adjacency.get(from).add(to);
            adjacency.get(to).add(from);
        }

        Set<String> seen = new LinkedHashSet<>();
        List<List<Node>> clusters = new ArrayList<>();
        // 按 nodes 的顺序起步 → 同一份输入必得同一份分区（确定性）
        for (Node node : nodes) {
            if (!seen.add(node.id)) {
                continue;
            }
            List<Node> cluster = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(node.id);
            while (!queue.isEmpty()) {
                String id = queue.poll();
                cluster.add(byId.get(id));
                for (String next : adjacency.get(id)) {
                    if (seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    /** 按标签首段聚类（没有分隔符的标签不参与） */
    private Map<String, List<Node>> clusterByLabelPrefix(List<Node> nodes) {
        Map<String, List<Node>> byPrefix = new LinkedHashMap<>();
        for (Node node : nodes) {
            String prefix = labelPrefix(node.json);
            if (prefix == null) {
                continue;
            }
            byPrefix.computeIfAbsent(prefix, key -> new ArrayList<>())
                    .add(node);
        }
        return byPrefix;
    }

    /** 取图形标签的首段（"订单-创建" → "订单"）；无分隔符 / 空首段返回 null */
    private String labelPrefix(JsonNode element) {
        JsonNode label = element.get("label");
        if (label == null || !label.isObject()) {
            return null;
        }
        String value = text(label, "text");
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            if (LABEL_SEPARATORS.indexOf(trimmed.charAt(i)) < 0) {
                continue;
            }
            String prefix = trimmed.substring(0, i).trim();
            if (prefix.isEmpty() || prefix.length() > MAX_GROUP_NAME) {
                return null;
            }
            return prefix;
        }
        return null;
    }

    /**
     * 一个分区是否"成形"：模块数在 2~{@link #MAX_MODULE_COUNT} 之间、
     * 每块至少 {@link #MIN_CLUSTER_SIZE} 个图形、且这些块合起来覆盖了
     * {@link #MIN_CLUSTER_COVERAGE} 以上的图形。
     *
     * <p>覆盖率是关键的一条：只圈住 2 个图形的"分区"会让其余 40 个图形
     * 无家可归，重排之后比不排更乱。要求每块 ≥2 个则把孤立点"
     * 自然放回根块，不必单独处理。
     */
    private boolean validClusters(List<List<Node>> clusters, int total) {
        List<List<Node>> kept = new ArrayList<>();
        int covered = 0;
        for (List<Node> cluster : clusters) {
            if (cluster.size() >= MIN_CLUSTER_SIZE) {
                kept.add(cluster);
                covered += cluster.size();
            }
        }
        if (kept.size() < 2 || kept.size() > MAX_MODULE_COUNT) {
            return false;
        }
        return total <= 0
                || (double) covered / total >= MIN_CLUSTER_COVERAGE;
    }

    /**
     * 读一个图形的 {@code group}，返回<b>从内到外</b>的模块名列表。
     *
     * <p>只做了三件宽容处理：去空白、截断超长、丢弃空串与非字符串。
     * 数字 / 对象 / 布尔等一律当作"没分组"——契约里它是
     * {@code oneOf[string, string[]]}，写错的成本是"少一次分区"，
     * 不该是"生成失败"。
     */
    private List<String> groupPath(JsonNode element) {
        JsonNode group = element.get("group");
        List<String> path = new ArrayList<>();
        if (group == null || group.isNull()) {
            return path;
        }
        if (group.isTextual()) {
            addName(path, group.asText());
        } else if (group.isArray()) {
            for (JsonNode item : group) {
                if (item.isTextual()) {
                    addName(path, item.asText());
                }
            }
        }
        return path;
    }

    private void addName(List<String> path, String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return;
        }
        if (name.length() > MAX_GROUP_NAME) {
            name = name.substring(0, MAX_GROUP_NAME);
        }
        if (!path.contains(name)) {
            path.add(name);
        }
    }

    /**
     * 把 LLM 画的 {@code frame} 认领给同名模块。
     *
     * <p>先精确匹配，再"包含"匹配（LLM 常把 name 写成「订单域模块」这类）。
     * <b>匹配不上的一律原样不动</b> —— 不去猜它的成员：布局会把它原本覆盖的
     * 节点打散到不同模块，按"几何兜底"重算只会得到一个横跨全图的大框，
     * 比不处理更难看。
     */
    private void matchFrames(Block root, List<JsonNode> elements) {
        for (JsonNode element : elements) {
            if (!element.isObject()
                    || !"frame".equals(text(element, "type"))) {
                continue;
            }
            String name = text(element, "name");
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            Block block = findBlock(root, name.trim());
            if (block == null || block.frame != null) {
                continue;
            }
            block.frame = (ObjectNode) element;
        }
    }

    /** 按模块名找块：先精确，再包含（拿不到就返回 null） */
    private Block findBlock(Block root, String name) {
        List<Block> all = new ArrayList<>();
        flatten(root, all);
        for (Block block : all) {
            if (block.name != null && block.name.equals(name)) {
                return block;
            }
        }
        for (Block block : all) {
            if (block.name != null
                    && (block.name.contains(name)
                    || name.contains(block.name))) {
                return block;
            }
        }
        return null;
    }

    private void flatten(Block block, List<Block> out) {
        for (Block child : block.children) {
            out.add(child);
            flatten(child, out);
        }
    }

    // ==================== 落地几何 ====================

    private String arrangeModules(
            JsonNode root, Partition partition, LayoutEngine engine) {
        Block top = partition.root;
        layoutBlock(top, engine, partition);
        place(top, 0, 0);
        writeShapes(partition.nodes);
        writeFrames(top);

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Node node : partition.nodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
            maxX = Math.max(maxX, node.x + node.w);
            maxY = Math.max(maxY, node.y + node.h);
        }
        LOG.info("排版完成：{} 个图形分入 {} 个模块，包围盒 {}x{}",
                partition.nodes.size(), partition.moduleCount,
                Math.round(maxX - minX), Math.round(maxY - minY));
        return root.toString();
    }

    /**
     * 自底向上排一个块：先排它的子块，再把"自己的图形"和"子块"一起打包。
     *
     * <p>图形块内部交给 {@link LayoutEngine}（竖排），块与块之间用网格打包
     * 并留出 {@link LayoutSpec#MODULE_GAP} 宽的通道 —— 跨模块长边的标签
     * 中点就落在这条通道里，这是 5.3 验证有效的那个机制。
     */
    private void layoutBlock(
            Block block, LayoutEngine engine, Partition partition) {
        for (Block child : block.children) {
            layoutBlock(child, engine, partition);
        }
        List<Block> items = new ArrayList<>();
        if (!block.shapes.isEmpty()) {
            Block own = layoutOwnShapes(block.shapes, engine, partition);
            block.own = own;
            items.add(own);
        }
        items.addAll(block.children);

        if (items.isEmpty()) {
            block.contentW = 0;
            block.contentH = 0;
        } else {
            double[] size = pack(items, LayoutSpec.MODULE_GAP);
            block.contentW = size[0];
            block.contentH = size[1];
        }

        // 有画框的块要为自己留出内边距（框画在内容外侧，还占一条标题带），
        // 否则同级两个框会贴在一起甚至相叠
        boolean framed = block.frame != null;
        block.padLeft = framed ? LayoutSpec.MODULE_PADDING : 0;
        block.padRight = block.padLeft;
        block.padBottom = block.padLeft;
        block.padTop = framed
                ? LayoutSpec.MODULE_PADDING + LayoutSpec.FRAME_TITLE : 0;
    }

    /**
     * 把一个模块内的图形交给单层引擎排，并把结果归一化到"左上角为 (0,0)"。
     *
     * <p>返回的占位块只带尺寸（{@code shapes} 为空）：真正的图形仍归
     * 外层块持有，避免同一次平移被算两遍。
     */
    private Block layoutOwnShapes(
            List<Node> shapes, LayoutEngine engine, Partition partition) {
        Set<String> ids = new LinkedHashSet<>();
        for (Node node : shapes) {
            ids.add(node.id);
        }
        ArrayNode sub = objectMapper.createArrayNode();
        for (Node node : shapes) {
            sub.add(node.json);
        }
        for (JsonNode element : partition.rawElements) {
            if (!"arrow".equals(text(element, "type"))) {
                continue;
            }
            String from = bindingId(element, "start");
            String to = bindingId(element, "end");
            if (from != null && to != null
                    && ids.contains(from) && ids.contains(to)) {
                sub.add(element);
            }
        }
        ObjectNode scene = objectMapper.createObjectNode();
        scene.set("elements", sub);
        String laid = engine.layout(scene.toString(), Set.of());

        Map<String, JsonNode> byId = new LinkedHashMap<>();
        try {
            JsonNode laidRoot = objectMapper.readTree(laid);
            JsonNode laidElements = laidRoot.get("elements");
            if (laidElements != null) {
                for (JsonNode element : laidElements) {
                    String id = text(element, "id");
                    if (id != null) {
                        byId.put(id, element);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("模块内布局结果解析失败（{}），该模块保持种子坐标",
                    e.getMessage());
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        for (Node node : shapes) {
            JsonNode laidNode = byId.get(node.id);
            if (laidNode != null && isNumber(laidNode, "x")
                    && isNumber(laidNode, "y")) {
                node.x = number(laidNode, "x");
                node.y = number(laidNode, "y");
                // 引擎会按"渲染尺寸"改写宽高，必须一起取回：下面算模块
                // 包围盒用的是 node.w / node.h，frame 几何又由包围盒得出
                if (isNumber(laidNode, "width")) {
                    node.w = number(laidNode, "width");
                }
                if (isNumber(laidNode, "height")) {
                    node.h = number(laidNode, "height");
                }
            }
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
        }
        if (minX == Double.MAX_VALUE) {
            minX = 0;
            minY = 0;
        }
        double maxX = 0;
        double maxY = 0;
        for (Node node : shapes) {
            node.x -= minX;
            node.y -= minY;
            maxX = Math.max(maxX, node.x + node.w);
            maxY = Math.max(maxY, node.y + node.h);
        }
        Block box = new Block(null);
        box.contentW = maxX;
        box.contentH = maxY;
        return box;
    }

    /**
     * 网格打包：试遍列数，取长宽比最接近 {@link #TARGET_ASPECT} 的一种。
     *
     * <p>确定性是刻意的 —— 同一份输入必须得到同一份坐标，否则
     * {@link SceneDrift}（几何保真）与 E2E 断言都无从谈起。
     *
     * @return 打包后的整体尺寸
     */
    private double[] pack(List<Block> items, double gap) {
        if (items.size() == 1) {
            items.get(0).ox = 0;
            items.get(0).oy = 0;
            return new double[] {
                items.get(0).outerW(), items.get(0).outerH(),
            };
        }
        int bestCols = 0;
        double bestPenalty = Double.MAX_VALUE;
        for (int cols = 1; cols <= items.size(); cols++) {
            double[] size = applyGrid(items, cols, gap);
            if (size[0] <= 0 || size[1] <= 0) {
                continue;
            }
            double penalty = Math.abs(Math.log(
                    (size[0] / size[1]) / TARGET_ASPECT));
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestCols = cols;
            }
        }
        if (bestCols == 0) {
            return new double[] {0, 0};
        }
        // 循环里最后一次 applyGrid 未必是当选的那一列，再摆一次定稿
        return applyGrid(items, bestCols, gap);
    }

    /** 按固定列数摆一次，返回整体尺寸（行内垂直居中，列居中对齐） */
    private double[] applyGrid(List<Block> items, int cols, double gap) {
        int rows = (items.size() + cols - 1) / cols;
        double[] colW = new double[cols];
        double[] rowH = new double[rows];
        for (int i = 0; i < items.size(); i++) {
            Block item = items.get(i);
            colW[i % cols] = Math.max(colW[i % cols], item.outerW());
            rowH[i / cols] = Math.max(rowH[i / cols], item.outerH());
        }
        double[] colX = new double[cols];
        double cursorX = 0;
        for (int c = 0; c < cols; c++) {
            colX[c] = cursorX;
            cursorX += colW[c] + gap;
        }
        double[] rowY = new double[rows];
        double cursorY = 0;
        for (int r = 0; r < rows; r++) {
            rowY[r] = cursorY;
            cursorY += rowH[r] + gap;
        }
        for (int i = 0; i < items.size(); i++) {
            Block item = items.get(i);
            int r = i / cols;
            int c = i % cols;
            item.ox = colX[c] + (colW[c] - item.outerW()) / 2;
            item.oy = rowY[r] + (rowH[r] - item.outerH()) / 2;
        }
        return new double[] {
            Math.max(0, cursorX - gap), Math.max(0, cursorY - gap),
        };
    }

    /** 把局部坐标累加成全局坐标（{@code dx/dy} = 本块内容区左上角） */
    private void place(Block block, double dx, double dy) {
        if (block.own != null) {
            for (Node node : block.shapes) {
                node.x += dx + block.own.ox;
                node.y += dy + block.own.oy;
            }
        }
        for (Block child : block.children) {
            place(child, dx + child.ox + child.padLeft,
                    dy + child.oy + child.padTop);
        }
        block.gx = dx - block.padLeft;
        block.gy = dy - block.padTop;
    }

    /**
     * 把模块内布局的结果写回图形。
     *
     * <p><b>宽高也要写</b>（2026-09-18 修）：{@link #layoutOwnShapes} 是把
     * 模块内的图形拼成一份子场景交给引擎的，引擎在<b>重新解析出来的副本</b>上
     * 干活 —— 它写回的尺寸只留在那份副本里，不抄回来就等于没写。而尺寸必须
     * 一致：引擎按"渲染尺寸"算的层间距，契约里若留着小一号的旧尺寸，渲染端
     * 会把容器撑高，层间距当场失效（实测题 2 两处图形重叠）。
     */
    private void writeShapes(List<Node> nodes) {
        for (Node node : nodes) {
            node.json.put("x", LayoutSpec.snap(node.x));
            node.json.put("y", LayoutSpec.snap(node.y));
            node.json.put("width", node.w);
            node.json.put("height", node.h);
        }
    }

    /**
     * 回写画框几何与成员。
     *
     * <p>{@code children} 由后端算、不交给 LLM —— 它填不准，填错还会连带
     * 影响整批转换（见 {@code constraint.md}）。成员口径：<b>没有画框的子块
     * 直接把成员上交给外层框</b>，有画框的子块则整体作为外层框的一个成员
     * （渲染器只允许一个元素属于一个 frame，嵌套框就是这个语义）。
     */
    private void writeFrames(Block block) {
        if (block.frame != null) {
            block.frame.put("x", LayoutSpec.snap(block.gx));
            block.frame.put("y", LayoutSpec.snap(block.gy));
            block.frame.put("width",
                    Math.max(LayoutSpec.GRID,
                            LayoutSpec.snap(block.outerW())));
            block.frame.put("height",
                    Math.max(LayoutSpec.GRID,
                            LayoutSpec.snap(block.outerH())));
            ArrayNode children = block.frame.putArray("children");
            collectChildren(block, children);
        }
        for (Block child : block.children) {
            writeFrames(child);
        }
    }

    private void collectChildren(Block block, ArrayNode children) {
        for (Node node : block.shapes) {
            children.add(node.id);
        }
        for (Block child : block.children) {
            if (child.frame != null) {
                String id = text(child.frame, "id");
                if (id != null) {
                    children.add(id);
                }
            } else {
                collectChildren(child, children);
            }
        }
    }

    // ==================== children 清理 ====================

    private boolean hasChildren(List<JsonNode> elements) {
        for (JsonNode element : elements) {
            if ("frame".equals(text(element, "type"))
                    && element.has("children")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 抹掉"不该由 LLM 填"的 {@code children}。
     *
     * <p>只在排版未介入、而画布里确实带着 children 时才走这条路 ——
     * 它要重新解析一次引擎输出，所以刻意放在零回归路径的最后。
     */
    private String stripChildren(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode elements = root.get("elements");
            if (elements == null || !elements.isArray()) {
                return json;
            }
            for (JsonNode element : elements) {
                if (element.isObject()
                        && "frame".equals(text(element, "type"))) {
                    ((ObjectNode) element).remove("children");
                }
            }
            return root.toString();
        } catch (Exception e) {
            return json;
        }
    }

    // ==================== 小工具 ====================

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual()
                ? value.asText() : null;
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber()
                ? 0 : value.asDouble();
    }

    private boolean isNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber()
                && Double.isFinite(value.asDouble());
    }

    private String bindingId(JsonNode element, String field) {
        JsonNode binding = element.get(field);
        if (binding == null || !binding.isObject()) {
            return null;
        }
        return text(binding, "id");
    }

    // ==================== 内部数据结构 ====================

    /** 一次分区结果 */
    private static final class Partition {
        private final Block root;
        /** 原始元素列表（排模块内部时按它挑子集箭头） */
        private final List<JsonNode> rawElements;
        private final List<Node> nodes;
        private final boolean active;
        private final int moduleCount;

        private Partition(
                Block root, List<JsonNode> rawElements,
                List<Node> nodes, boolean active,
                int moduleCount) {
            this.root = root;
            this.rawElements = rawElements;
            this.nodes = nodes;
            this.active = active;
            this.moduleCount = moduleCount;
        }

        private boolean active() {
            return active;
        }
    }

    /** 一个图形 */
    private static final class Node {
        private final ObjectNode json;
        private final String id;
        /**
         * 尺寸：初值是种子尺寸，引擎排完会<b>改写</b>它 —— 引擎写回的是
         * "渲染端最终会画多大"，模块包围盒与 frame 必须按它算，否则框会
         * 按旧尺寸包住新图形（2026-09-18 起引擎写回尺寸）。
         */
        private double w;
        private double h;
        private double x;
        private double y;

        private Node(ObjectNode json, String id, double w, double h) {
            this.json = json;
            this.id = id;
            this.w = w;
            this.h = h;
            this.x = number(json, "x");
            this.y = number(json, "y");
        }

        private static double number(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || !value.isNumber()
                    ? 0 : value.asDouble();
        }
    }

    /** 模块树的一个节点 */
    private static final class Block {
        private final String name;
        private final List<Block> children = new ArrayList<>();
        private final List<Node> shapes = new ArrayList<>();
        private final Map<String, Block> byName = new LinkedHashMap<>();
        private ObjectNode frame;
        private Block own;
        private double ox;
        private double oy;
        private double padLeft;
        private double padTop;
        private double padRight;
        private double padBottom;
        private double contentW;
        private double contentH;
        private double gx;
        private double gy;

        private Block(String name) {
            this.name = name;
        }

        private double outerW() {
            return contentW + padLeft + padRight;
        }

        private double outerH() {
            return contentH + padTop + padBottom;
        }
    }
}
