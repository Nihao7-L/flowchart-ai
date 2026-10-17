package io.github.nihaoljx.flowchart.graph.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.nihaoljx.flowchart.graph.validate.RelationCatalog;
import io.github.nihaoljx.flowchart.graph.layout.LayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.LayoutSpec;

/**
 * 排版层：结构规划（/ 模块计划 m8）＋ frame 成员归属（P1）。
 *
 * 它补的是链路里缺的那一步：原链路
 * `LLM → Binder → Deduper → LayoutEngine → Validator` 只有"一次性几何求解"，
 * LLM 吐出的是一份扁平元素列表（无层级、无分组），布局引擎只能"每层居中
 * 叠罗汉" —— 于是复杂图必然翻车（跨层长边的标签砸在中间层节点上、竖排把图收成
 * 一根窄条 680×4820）。本类在 `Deduper` 与 `LayoutEngine` 之间插入
 * 结构规划：先算「模块 → 模块成员 → 模块间关系」，再按分区落几何。
 *
 * 为什么不靠调间距（实测，勿重复试）：垂直层间距拉 16 倍
 * （340→5440px），标签压节点数 2→2 纹丝不动，画布反而涨到 76220px 高 ——
 * 因为标签渲染位置 = 箭头几何中点（Excalidraw 现算），而垂直拉伸是仿射变换、
 * 中点仍是中点：跨 2 层的边其中点永远落在中间那一层。有效手段只有
 * 层间横向错位 / 分区，那正是本类的产物。
 *
 * 两级布局，而不是 ELK 的 compound graph：模块内部交给现成的
 * LayoutEngine（竖排已验证可用），模块之间用确定性网格打包
 * （列数按长宽比择优）—— 后者只有几十行、纯函数、可单测，而 compound graph
 * 要处理父子坐标系换算与跨层边路由，踩坑面大得多。
 *
 * 模块从哪来（2026-10-15 收紧：分区是**语义决策**，只认显式声明）：
 *
 * - ① LLM 标注（唯一来源）：图形自带 `group`（或 group 数组层级）
 *       → 按标注建模块树。模型读了用户需求，分不分区、怎么分
 *       它最清楚 —— 与 mermaid 的 subgraph 同一个哲学：
 *       分区由图作者显式声明，工具不猜；
 *
 * - ② frame 认领：画布上已有 frame 且名字对得上模块 → 认领进树；
 *
 * - ③ 不分组：两者都没有 → 单层布局，行为与改造前一致。
 *       此前还有一条「节点数 > 15 就后端自动聚类」的兜底
 *       （inferGroups，按拓扑/标签前缀猜模块），已删：猜错的模块
 *      比不分区更难看，且「大于 15」这种机械阈值不该存在。
 *       （2026-10-16：阈值从"模块数 ≥ 2"降到"≥ 1"—— 一个分组
 *       也值得走分区路径，理由见 partition() 内注释。）
 *
 * 零回归是硬约束：以下三种情况完全不介入，直接返回引擎输出
 * （字节级原样）——
 *
 * - 编辑模式（`fixedIds` 非空）：既有坐标一个不许动，否则与
 *       SceneDrift 当场打架；
 *
 * - 无显式分组（无 group / 无可认领 frame）：不做任何猜测；
 *
 * - 模块数 < 2：只有"有分组"才谈得上分区。
 *
 * 异常一律降级：任何一步抛错都退回单层布局，绝不让排版把整张图
 * 搞没 —— 与 `SceneBinder` / `SceneDeduper` 的容错口径一致。
 */
@Component
public class ScenePlanner {

    private static final Logger LOG =
            LoggerFactory.getLogger(ScenePlanner.class);

    /** 参与排版的图形类型（与 ElkLayoutEngine 同口径） */
    private static final Set<String> SHAPE_TYPES =
            Set.of("rectangle", "ellipse", "diamond");

    /**
     * 关系语义 → 视觉样式（P2）。
     *
 * LLM 用 `relation` 表达连线的业务含义，后端据此推导线型
     * （strokeStyle）与端点形状（startArrowhead / endArrowhead），免去 LLM
     * 自己拼 Excalidraw 细节。唯一来源见 RelationCatalog
     * （关系清单 + 样式映射合并为代码单源）；schema 的 `arrow.relation`
     * 枚举与提示词仍各手抄一份，扩值时须与 RelationCatalog 同步。
     */

    /** 模块名的最大长度：超长截断（LLM 偶尔会把一句话填进来） */
    private static final int MAX_GROUP_NAME = 24;

    /** 打包时的目标长宽比：模块横排 + 模块内竖排 → 整体略宽比正方好看 */
    private static final double TARGET_ASPECT = 1.5;

    /**
     * 整图方向试排的目标长宽比 = 渲染视口（16:9 ≈ 1.78）。
     *
     * 为什么与 TARGET_ASPECT 分开：pack 的择列管的是"模块网格排成
     * 什么形状"（1.5 是网格形态的中间值），而方向试排管的是"整张图
     * 与屏幕同不同向"—— 前端按内容缩放取景，整图贴着视口比例时
     * 缩放最大、留边最小。五张参考图没有一张低于 0.91，最高 1.71。
     */
    private static final double VIEWPORT_ASPECT = 16.0 / 9.0;

    /**
     * 方向试排的横排倾斜系数：右排惩罚 < 竖排惩罚 × 1.1 就选横排。
     *
     * 为什么需要（2026-10-16 五图实测）：RAG 参考图是"横向查询流在
     * 上、摄入横带在下"的堆叠构成，竖排等价打包（两列并排）的长宽比
     * 惩罚与横排堆叠几乎相等 —— 惩罚值是按估算宽度算的（节点宽度
     * ±30% 误差），near-tie 时它分不清真假。此时倒向横排：渲染视口
     * 是横屏，且五张参考图里横向流占多数（流程自左而右读）。
     * 竖排要赢必须明显更好（惩罚低 10% 以上）。
     */
    private static final double HORIZONTAL_TIE_BREAK = 1.1;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScenePlanner() {
        LOG.info("排版层：结构规划已就绪（LLM 显式分组或可认领 frame 且模块数 ≥ 1 时生效）");
    }

    /**
     * 排版入口：与 LayoutEngine 同签名，便于在生成链路里直接替换调用。
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
            // 仅新建路径编译 relation → 视觉样式（编辑模式不动既有坐标，
            // 避免与 SceneDrift 打架）。compileRelations 只增不删，返回是否改动。
            String effectiveJson = json;
            if (!editMode && compileRelations(elements)) {
                effectiveJson = objectMapper.writeValueAsString(root);
            }
            boolean dirtyChildren = hasChildren(elements);
            Partition partition = partition(elements);

            if (editMode || !partition.active()) {
                String laid = engine.layout(effectiveJson, fixedIds);
                // 零回归路径：只要画布里没有"LLM 越权填的 children"，
                // 就把引擎的输出原样返回 —— 一个字节都不碰
                if (!editMode) {
                    laid = governLabelsSingleLayer(laid);
                }
                return dirtyChildren ? stripChildren(laid) : laid;
            }
            return arrangeModules(root, partition, engine);
        } catch (Exception e) {
            LOG.warn("排版跳过（{}），退回单层布局", e.getMessage());
            return safeLayout(engine, json, fixedIds);
        }
    }

    /**
     * 单层路径的边标签治理：在 engine.layout() 输出上做去重 + 落位。
     *
     * <p>为什么需要：2026-10-17 实测请假流程图（7 节点无 group），
     * 菱形双出边「是」「否」都由渲染器自动居中在箭头包围盒中心，
     * 两条边都起于菱形底边同一点 ⇒ 标签贴线重叠不可读。
     *
     * <p>分区路径（arrangeModules）在 routeCrossModuleArrows 之后
     * 调 placeEdgeLabels 落位，但单层路径（无 group / editMode=false）
     * 从来没人管标签 —— 绑定标签由前端渲染器居中，位置不受控。
     *
     * <p>这里重新解析引擎输出（节点坐标已定稿），建分区（无 group
     * 元素全部落在 root 块），再复用 placeEdgeLabels 做同样的事。
     * 引擎输出已是有效 JSON（points/x/y 齐全），placeEdgeLabels
     * 读这些字段计算标签位置并写入独立 text 元素。
     */
    private String governLabelsSingleLayer(String laid) {
        try {
            JsonNode root = objectMapper.readTree(laid);
            JsonNode elementsNode = root.get("elements");
            if (elementsNode == null || !elementsNode.isArray()) {
                return laid;
            }
            List<JsonNode> elements = new ArrayList<>();
            elementsNode.forEach(elements::add);
            // 重新分区：无 group 元素全在 root，partition.active()=false
            // 但 placeEdgeLabels 只读 partition.nodes 和 partition.root
            Partition laidPartition = partition(elements);
            placeEdgeLabels(root, laidPartition);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warn("单层标签治理跳过（{}），保留引擎输出", e.getMessage());
            return laid;
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

    /**
     * 原生坐标模式：不调布局引擎、直接采用 LLM 给的种子坐标，
     * 仅保留「画框认领」—— 把 frame 的成员 id 填进 `children`，
     * 这样画框在渲染端拖动 / 删除时会带上成员（与改造前一致的交互）。
     *
 * 为什么不再重排：后端布局引擎会把所有图形尺寸抹平成统一大小、
     * 再把整图竖排成一根窄条（实测 c01 长宽比 2.83→0.33、c06 17.67→0.26），
     * 远不如 LLM 原生布局好看。原生模式下「摆哪、多大」全交给 LLM，
     * 后端只做结构补全（frame.children）与样式编译（relation → 线型）。
     *
 * 零回归保证：本方法不改动任何几何（x/y/width/height 原样返回），
     * 因此编辑模式也安全 —— 既有坐标一个字节都不动，不会触发 SceneDrift。
     *
     * @param json bind + dedup 之后的场景 JSON
     * @return 仅补全 frame.children（与 relation 样式）的场景 JSON
     */
    public String claimFramesOnly(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode elementsNode = root.get("elements");
            if (elementsNode == null || !elementsNode.isArray()) {
                return json;
            }
            List<JsonNode> elements = new ArrayList<>();
            elementsNode.forEach(elements::add);
            // relation → 视觉样式：只增不删，不动几何（与 arrange 同口径）
            compileRelations(elements);
            // 建模块树 + 按名字把 frame 认领给模块（matchFrames 在 partition 内执行）
            Partition partition = partition(elements);
            writeFrameChildrenOnly(partition.root);
            return root.toString();
        } catch (Exception e) {
            LOG.warn("原生坐标模式：框认领跳过（{}），原样返回", e.getMessage());
            return json;
        }
    }

    /**
     * 只把成员 id 填进 frame 的 `children`，不重写画框几何
     * （原生坐标模式下画框尺寸 / 位置由 LLM 给出，后端不该覆盖）。
     * 递归处理嵌套模块树。
     */
    private void writeFrameChildrenOnly(Block block) {
        if (block.frame != null) {
            ArrayNode children = block.frame.putArray("children");
            collectChildren(block, children);
        }
        for (Block child : block.children) {
            writeFrameChildrenOnly(child);
        }
    }

    // ==================== relation 编译（P2） ====================

    /**
     * 把 `relation` 语义编译成 Excalidraw 视觉样式（strokeStyle / 端点形状）。
     *
 * 只在新建路径调用（见 arrange），编辑模式不碰既有坐标。
     * 只增不删：仅当 relation 命中已知映射时才写对应字段，绝不影响其它属性，
     * 因此无 relation 的箭头输出与改造前逐字节一致（零回归）。
     *
 * 包级可见是为了让 `RelationCompileTest` 直接单测，不必走整条布局链路。
     *
     * @return 是否真的改动了某个元素
     */
    static boolean compileRelations(List<JsonNode> elements) {
        boolean changed = false;
        for (JsonNode elem : elements) {
            if (!"arrow".equals(elem.path("type").asText(""))) {
                continue;
            }
            JsonNode rel = elem.get("relation");
            if (rel == null || !rel.isTextual()) {
                continue;
            }
            RelationCatalog.RelationStyle style =
                    RelationCatalog.STYLE.get(rel.asText());
            if (style == null) {
                continue;
            }
            ObjectNode arrow = (ObjectNode) elem;
            if (style.strokeStyle() != null) {
                arrow.put("strokeStyle", style.strokeStyle());
            }
            if (style.startArrowhead() != null) {
                arrow.put("startArrowhead", style.startArrowhead());
            }
            if (style.endArrowhead() != null) {
                arrow.put("endArrowhead", style.endArrowhead());
            }
            changed = true;
        }
        return changed;
    }

    // ==================== 结构规划 ====================

    /**
     * 分区：把每个图形按 `group` 归入模块，建成一棵模块树。
     *
 * `group` 的形态由契约约束（`oneOf` 字符串 / 字符串数组），
     * 但这里一律宽容归一化——空串、非字符串、超长、空数组都被
     * 降级成"未分组"，绝不抛异常中断生成（验收口径之一）。
     * 数组的语义与渲染层的 `groupIds` 一致：从内到外，
     * 例如 `["下单", "订单域"]` 表示"下单"隶属于"订单域"。
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

        // ② 后端聚类兜底已删（2026-10-15）：此前「节点数 > 15 就按
        // 拓扑/标签前缀猜模块」。分不分区是语义决策 —— 模型读了用户
        // 需求，它不标组说明需求里没有分区语义；后端按前缀猜错的
        // 模块比不分区更难看（mermaid 的 subgraph 同哲学：只认显式声明）。

        matchFrames(root, elements);
        // 2026-10-16 数量阈值正式废弃：分区是语义决策，不是数量决策。
        // 只要 LLM 显式标注了 group（哪怕只有一组），就按它分区 ——
        // 单组同样值得走分区路径（模块内自排、与其他图形分开打包），
        // 不再用 moduleCount ≥ 2 把单组挡在门外。
        //
        // 没有显式分组时不介入：画布上孤立、名字认领不上任何模块的
        // frame 不足以成为分区理由（frame 只能挂到已存在的模块上，
        // 不会凭空造模块 —— inferenceIsSkippedWhenFramesExist 锁这条）。
        boolean active = moduleCount >= 1;
        return new Partition(
                root, elements, nodes, active, moduleCount);
    }

    /** 收集参与排版的图形（与 SHAPE_TYPES 同口径，缺 id 的丢弃） */
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

    /**
     * 读一个图形的 `group`，返回从内到外的模块名列表。
     *
 * 只做了三件宽容处理：去空白、截断超长、丢弃空串与非字符串。
     * 数字 / 对象 / 布尔等一律当作"没分组"——契约里它是
     * `oneOf[string, string[]]`，写错的成本是"少一次分区"，
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
     * 把 LLM 画的 `frame` 认领给同名模块。
     *
 * 先精确匹配，再"包含"匹配（LLM 常把 name 写成「订单域模块」这类）。
     * 匹配不上的一律原样不动 —— 不去猜它的成员：布局会把它原本覆盖的
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
        orderModulesByFlow(partition);
        // 整图方向试排（2026-10-16 五图实测）：模块内方向不再由模块
        // 自己的长宽比决定 —— 认证图「AWS Cloud」4 节点横排成一条带
        // 是对的（参考图就是横带），Transformer「DECODER」10 节点竖排
        // 成一列也是对的（参考图就是竖列），模块自己的长宽比说明不了
        // 该横还是该竖。判断依据在整图打包后，那只有这里看得见：
        // 两个全局取向各排一遍（layoutBlock 只算尺寸与块内坐标，
        // 不做全局平移，可重复跑），按整图与视口长宽比的距离定稿。
        layoutBlock(top, engine, partition, false);
        double downW = top.contentW;
        double downH = top.contentH;
        layoutBlock(top, engine, partition, true);
        double rightW = top.contentW;
        double rightH = top.contentH;
        boolean horizontal = viewportPenalty(rightW, rightH)
                < viewportPenalty(downW, downH)
                        * HORIZONTAL_TIE_BREAK;
        if (!horizontal) {
            // 定稿跑必须与当选取向一致（上一遍跑的是横排）
            layoutBlock(top, engine, partition, false);
        }
        place(top, 0, 0);
        writeShapes(partition.nodes);
        writeFrames(top);
        routeCrossModuleArrows(partition);
        placeEdgeLabels(root, partition);

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
        LOG.info("排版完成：{} 个图形分入 {} 个模块，主方向 {}"
                        + "（试排：竖 {}x{} / 横 {}x{}），包围盒 {}x{}",
                partition.nodes.size(), partition.moduleCount,
                horizontal ? "自左而右" : "自上而下",
                Math.round(downW), Math.round(downH),
                Math.round(rightW), Math.round(rightH),
                Math.round(maxX - minX), Math.round(maxY - minY));
        return root.toString();
    }

    /**
     * 整图尺寸与视口长宽比的对数距离（方向试排的择优函数）。
     *
     * <p>与 {@link LayoutSpec#aspectPenalty} 分开：那个按理想区间
     * [0.7, 2.0] 算"出不出区间"，治的是病态窄条/宽条；这个按
     * {@link #VIEWPORT_ASPECT} 算"贴不贴屏幕"，治的是两个合法形态
     * （竖堆 / 横堆）之间选哪个。
     */
    private static double viewportPenalty(double width, double height) {
        return SceneGeometryKit.viewportPenalty(width, height);
    }

    /**
     * 分区路径的跨模块箭头正交避障路由。
     *
     * 为什么需要：模块内边在 layoutOwnShapes 里随子图进过 ELK，points 已由
     * 引擎写回；**跨模块边不属于任何模块子图**，从头到尾没人给它们几何。
     *
     * v2（2026-10-15 regress_arch3 被用户判「一坨狗屎」后重写）：此前的
     * 朴素 L 形有三宗罪 —— ① 起笔从框中心穿出、压过框内标签；② 拐点
     * 通道压在实体上（x=380 直接穿过三个服务框）；③ 所有边共用同一条
     * 通道叠成一股。现按 draw.io 观感标准重写，三件套：
     *
     *  ① 贴边进出：锚点落在源/目标框朝向走廊的边缘中点；
     *  ② 走空隙：拐点落在「无任何节点覆盖」的自由走廊（节点 bbox 外扩
     *     4px 后的一维补区间），竖线/横线永不穿框；
     *  ③ 通道分配：共用同一条走廊的边均摊布线（最小间距 14px）。
     *
     * 路由模板按观感优先级依次尝试，取首条零碰撞的：
     *   T1 H-V-H（侧面进出）→ T2 V-H-V（上下进出）→
     *   T3 H-V-H-V（侧出 + 绕水平走廊、从上/下进）→ T4（对称）。
     * 全败时取碰撞最少方案；连走廊都没有时退化为贴边 L 形。
     *
     * 坐标口径与 ElkLayoutEngine.writeBackEdgeRoutes 一致：
     * x/y = 首点绝对坐标，points = 相对首点的偏移、首点 [0,0]。
     */
    private void routeCrossModuleArrows(Partition partition) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node node : partition.nodes) {
            byId.put(node.id, node);
        }
        List<ObjectNode> arrows = new ArrayList<>();
        List<Node> froms = new ArrayList<>();
        List<Node> tos = new ArrayList<>();
        for (JsonNode element : partition.rawElements) {
            if (!"arrow".equals(text(element, "type"))
                    || !(element instanceof ObjectNode arrow)) {
                continue;
            }
            JsonNode existing = arrow.get("points");
            if (existing != null && existing.isArray()
                    && !existing.isEmpty()) {
                continue;
            }
            Node from = byId.get(bindingId(element, "start"));
            Node to = byId.get(bindingId(element, "end"));
            if (from != null && to != null && from != to) {
                arrows.add(arrow);
                froms.add(from);
                tos.add(to);
            }
        }
        if (arrows.isEmpty()) {
            return;
        }
        // 同源第几条出边 / 同目标第几条入边（0 起）与总数：
        // 锚点沿边分散靠它 —— 全挤边中点就是「一坨线分不清谁连谁」
        // 的直接原因（regress_arch6 实测 198 交叉 + 28 共线重叠）。
        Map<String, Integer> outCnt = new LinkedHashMap<>();
        Map<String, Integer> inCnt = new LinkedHashMap<>();
        for (int i = 0; i < arrows.size(); i++) {
            outCnt.merge(froms.get(i).id, 1, Integer::sum);
            inCnt.merge(tos.get(i).id, 1, Integer::sum);
        }
        Map<Integer, Integer> outIdx = new LinkedHashMap<>();
        Map<Integer, Integer> inIdx = new LinkedHashMap<>();
        Map<String, Integer> outSeen = new LinkedHashMap<>();
        Map<String, Integer> inSeen = new LinkedHashMap<>();
        for (int i = 0; i < arrows.size(); i++) {
            outIdx.put(i, outSeen.merge(froms.get(i).id, 0,
                    (old, none) -> old + 1));
            inIdx.put(i, inSeen.merge(tos.get(i).id, 0,
                    (old, none) -> old + 1));
        }
        // 短边先路由：短边容易零交叉占内侧走廊，长边自然绕外侧
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < arrows.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            double sa = span(froms.get(a), tos.get(a));
            double sb = span(froms.get(b), tos.get(b));
            return Double.compare(sa, sb);
        });
        List<double[]> vCorridors = freeIntervals(partition.nodes, true);
        List<double[]> hCorridors = freeIntervals(partition.nodes, false);
        Map<Integer, List<Double>> usedV = new LinkedHashMap<>();
        Map<Integer, List<Double>> usedH = new LinkedHashMap<>();
        List<double[][]> routed = new ArrayList<>();
        OrthoAStarRouter.Grid occupancy =
                new OrthoAStarRouter.Grid(partition.nodes);
        int routedCount = 0;
        for (int slot : order) {
            Fan fan = new Fan(
                    outIdx.getOrDefault(slot, 0),
                    outCnt.getOrDefault(froms.get(slot).id, 1),
                    inIdx.getOrDefault(slot, 0),
                    inCnt.getOrDefault(tos.get(slot).id, 1));
            // A* 优先（全走廊空间搜索 + 已路由边占用惩罚），
            // 模板候选制兜底（A* 网格不可达等极端情况）
            List<double[]> astar = OrthoAStarRouter.route(
                    froms.get(slot), tos.get(slot),
                    fan.outIdx(), fan.outCnt(),
                    fan.inIdx(), fan.inCnt(),
                    partition.nodes, occupancy);
            double[][] route = astar == null
                    ? null : dedupe(astar.toArray(new double[0][]));
            if (route == null) {
                route = routeOne(froms.get(slot), tos.get(slot),
                        fan, partition.nodes, vCorridors, hCorridors,
                        usedV, usedH, routed);
            }
            if (route == null) {
                continue;
            }
            occupancy.mark(route);
            routed.add(route);
            writeRoute(arrows.get(slot), route);
            routedCount++;
        }
        if (routedCount > 0) {
            LOG.info("跨模块箭头补正交避障路由 {}/{} 条", routedCount,
                    arrows.size());
        }
    }

    /** 两节点中心距（曼哈顿）：路由顺序的排序键 */
    private static double span(Node f, Node t) {
        return Math.abs(f.x + f.w / 2 - (t.x + t.w / 2))
                + Math.abs(f.y + f.h / 2 - (t.y + t.h / 2));
    }

    /** 一条边的扇出/扇入参数：出边序号/总数、入边序号/总数 */
    private record Fan(int outIdx, int outCnt, int inIdx, int inCnt) {
    }

    /** 一条跨模块边被摆成逆流时，给该打包方案加的罚分 */
    private static final double BACKWARD_EDGE_COST = 2.0;

    /**
     * 顶层模块按跨模块边方向重排，并把流向记录进 partition.flow。
     *
     * regress_arch6 实测教训：「接入→服务→数据」3 模块链被网格打包成
     * 2 列两行，数据层落到服务层左下方 —— 一条对角线边被迫绕外圈
     * 走廊，跟别的边交叉成坨。好图的标准（mermaid/draw.io 的 layered
     * 同理）：上游模块排左上、下游排右下。拓扑序排 children，打包
     * 评估再按 flow 罚逆流摆法，链式依赖自然展开成一行。
     */
    private void orderModulesByFlow(Partition partition) {
        Block top = partition.root;
        if (top.children.size() < 2) {
            return;
        }
        Map<String, Block> owner = new LinkedHashMap<>();
        for (Block b : top.children) {
            collectMemberIds(b, owner);
        }
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node n : partition.nodes) {
            byId.put(n.id, n);
        }
        Map<String, Map<String, Integer>> flow = new LinkedHashMap<>();
        for (JsonNode element : partition.rawElements) {
            if (!"arrow".equals(text(element, "type"))) {
                continue;
            }
            Node from = byId.get(bindingId(element, "start"));
            Node to = byId.get(bindingId(element, "end"));
            if (from == null || to == null) {
                continue;
            }
            Block bf = owner.get(from.id);
            Block bt = owner.get(to.id);
            if (bf == null || bt == null || bf == bt) {
                continue;
            }
            flow.computeIfAbsent(bf.name, k -> new LinkedHashMap<>())
                    .merge(bt.name, 1, Integer::sum);
        }
        partition.flow = flow;
        if (flow.isEmpty()) {
            return;
        }
        Map<String, Block> byName = new LinkedHashMap<>();
        for (Block b : top.children) {
            byName.put(b.name, b);
        }
        // 稳定拓扑排序：上游全部出列后自己才出列；环时按原序出首个
        List<Block> ordered = new ArrayList<>();
        List<Block> remaining = new ArrayList<>(top.children);
        while (!remaining.isEmpty()) {
            Block pick = null;
            for (Block b : remaining) {
                if (hasUnplacedUpstream(b, flow, byName, ordered)) {
                    continue;
                }
                pick = b;
                break;
            }
            if (pick == null) {
                pick = remaining.get(0);
            }
            ordered.add(pick);
            remaining.remove(pick);
        }
        top.children.clear();
        top.children.addAll(ordered);
    }

    /** 块 b 是否还有「有边指向它、且尚未排定」的上游顶层块 */
    private static boolean hasUnplacedUpstream(
            Block b, Map<String, Map<String, Integer>> flow,
            Map<String, Block> byName, List<Block> ordered) {
        for (Map.Entry<String, Map<String, Integer>> e : flow.entrySet()) {
            if (e.getKey().equals(b.name)
                    || !byName.containsKey(e.getKey())
                    || !e.getValue().containsKey(b.name)) {
                continue;
            }
            boolean placed = false;
            for (Block done : ordered) {
                if (done.name.equals(e.getKey())) {
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                return true;
            }
        }
        return false;
    }

    /** 递归收集一个块的全体成员图形 id（含嵌套子块），归属到该块 */
    private static void collectMemberIds(Block block, Map<String, Block> owner) {
        for (Node n : block.shapes) {
            owner.put(n.id, block);
        }
        for (Block child : block.children) {
            collectMemberIds(child, owner);
        }
    }

    /**
     * 该打包摆法下「逆流」的跨模块边条数：下游块被排到上游块左侧
     * （或同列且在其上方）。flow 键是顶层模块名，模块内部打包时
     * items 名字不在 flow 里，返回 0 —— 惩罚只作用于顶层。
     */
    private static int backwardEdges(
            List<Block> items, Map<String, Map<String, Integer>> flow) {
        if (flow.isEmpty()) {
            return 0;
        }
        Map<String, Block> pos = new LinkedHashMap<>();
        for (Block b : items) {
            pos.put(b.name, b);
        }
        int back = 0;
        for (Map.Entry<String, Map<String, Integer>> e : flow.entrySet()) {
            Block from = pos.get(e.getKey());
            if (from == null) {
                continue;
            }
            for (String to : e.getValue().keySet()) {
                Block tb = pos.get(to);
                if (tb == null) {
                    continue;
                }
                boolean backward = tb.ox < from.ox - 1
                        || (Math.abs(tb.ox - from.ox) <= 1
                            && tb.oy < from.oy - 1);
                if (backward) {
                    back++;
                }
            }
        }
        return back;
    }

    /** 通道最小间距（px）：共用走廊的线至少离这么远，不成股 */
    private static final double CHANNEL_GAP = 14;

    /** 评分权重：穿框 > 交叉 > 通道序号 */
    private static final int W_HIT = 10000;
    private static final int W_CROSS = 100;
    private static final int W_SLOT = 10;

    /** 一个路由候选：折线 + 评分 + 选中后要做的通道占用提交 */
    private static final class Candidate {
        final double[][] route;
        final int score;
        final Runnable commit;

        Candidate(double[][] route, int score, Runnable commit) {
            this.route = route;
            this.score = score;
            this.commit = commit;
        }
    }

    /**
     * 为一条跨模块边求路由（绝对坐标折线），失败返回 null。
     *
     * 评分制而非"首个零碰撞即用"（2026-10-15 regress_arch6 教训）：
     * 四个模板 × 各自走廊全部展开成候选，按
     * 穿框×10000 + 与已路由边交叉×100 + 通道序号×10
     * 取最优 —— 交叉最小化是可读性的第一指标，首个可行解往往
     * 和先前的线撞在同一条走廊里叠成一股。
     */
    private double[][] routeOne(
            Node f, Node t, Fan fan, List<Node> nodes,
            List<double[]> vCorridors, List<double[]> hCorridors,
            Map<Integer, List<Double>> usedV,
            Map<Integer, List<Double>> usedH,
            List<double[][]> routed) {
        double fcx = f.x + f.w / 2;
        double fcy = f.y + f.h / 2;
        double tcx = t.x + t.w / 2;
        double tcy = t.y + t.h / 2;
        List<Integer> vOrder = rankCorridors(vCorridors,
                Math.min(fcx, tcx) - 40, Math.max(fcx, tcx) + 40);
        List<Integer> hOrder = rankCorridors(hCorridors,
                Math.min(fcy, tcy) - 40, Math.max(fcy, tcy) + 40);
        List<Candidate> cands = new ArrayList<>();

        double outSlideY = fanSlide(fan.outIdx(), fan.outCnt(), f.h);
        double inSlideY = fanSlide(fan.inIdx(), fan.inCnt(), t.h);
        double outSlideX = fanSlide(fan.outIdx(), fan.outCnt(), f.w);
        double inSlideX = fanSlide(fan.inIdx(), fan.inCnt(), t.w);

        // T1：H-V-H —— 源/目标侧边出进（锚点沿边分散）
        for (int idx : vOrder.subList(0, Math.min(4, vOrder.size()))) {
            double[] c = nextChannel(vCorridors.get(idx),
                    usedV.computeIfAbsent(idx, k -> new ArrayList<>()),
                    clamp((fcx + tcx) / 2, vCorridors.get(idx)));
            double[] sa = sideAnchor(f, c[0], true, outSlideY);
            double[] ta = sideAnchor(t, c[0], true, inSlideY);
            double[][] route = {
                sa, {c[0], sa[1]}, {c[0], ta[1]}, ta};
            cands.add(cand(route, f, t, nodes, routed, c[1],
                    () -> usedV.get(idx).add(c[0])));
        }
        // T2：V-H-V —— 源/目标上下边出进
        for (int idx : hOrder.subList(0, Math.min(4, hOrder.size()))) {
            double[] c = nextChannel(hCorridors.get(idx),
                    usedH.computeIfAbsent(idx, k -> new ArrayList<>()),
                    clamp((fcy + tcy) / 2, hCorridors.get(idx)));
            double[] sa = sideAnchor(f, c[0], false, outSlideX);
            double[] ta = sideAnchor(t, c[0], false, inSlideX);
            double[][] route = {
                sa, {sa[0], c[0]}, {ta[0], c[0]}, ta};
            cands.add(cand(route, f, t, nodes, routed, c[1],
                    () -> usedH.get(idx).add(c[0])));
        }
        // T3：H-V-H-V —— 侧出 + 竖走廊 + 水平走廊 + 从上/下边进目标
        for (int vi : vOrder.subList(0, Math.min(3, vOrder.size()))) {
            double[] c = nextChannel(vCorridors.get(vi),
                    usedV.computeIfAbsent(vi, k -> new ArrayList<>()),
                    clamp((fcx + tcx) / 2, vCorridors.get(vi)));
            for (int hi : hOrder.subList(0, Math.min(3, hOrder.size()))) {
                for (double bias : new double[] {0, 40, -40}) {
                    double ideal = tcy + bias;
                    double[] l = nextChannel(hCorridors.get(hi),
                            usedH.computeIfAbsent(
                                    hi, k -> new ArrayList<>()),
                            clamp(ideal, hCorridors.get(hi)));
                    double[] sa = sideAnchor(f, c[0], true, outSlideY);
                    double[] ta = sideAnchor(t, l[0], false, inSlideX);
                    double[][] route = {
                        sa, {c[0], sa[1]}, {c[0], l[0]},
                        {ta[0], l[0]}, ta};
                    cands.add(cand(route, f, t, nodes, routed,
                            c[1] + l[1],
                            () -> {
                                usedV.get(vi).add(c[0]);
                                usedH.get(hi).add(l[0]);
                            }));
                }
            }
        }
        // T4：V-H-V-H —— 上下出 + 水平走廊 + 竖走廊 + 从侧边进目标
        for (int hi : hOrder.subList(0, Math.min(3, hOrder.size()))) {
            double[] l = nextChannel(hCorridors.get(hi),
                    usedH.computeIfAbsent(hi, k -> new ArrayList<>()),
                    clamp((fcy + tcy) / 2, hCorridors.get(hi)));
            for (int vi : vOrder.subList(0, Math.min(3, vOrder.size()))) {
                for (double bias : new double[] {0, 40, -40}) {
                    double ideal = tcx + bias;
                    double[] c = nextChannel(vCorridors.get(vi),
                            usedV.computeIfAbsent(
                                    vi, k -> new ArrayList<>()),
                            clamp(ideal, vCorridors.get(vi)));
                    double[] sa = sideAnchor(f, l[0], false, outSlideX);
                    double[] ta = sideAnchor(t, c[0], true, inSlideY);
                    double[][] route = {
                        sa, {sa[0], l[0]}, {c[0], l[0]},
                        {c[0], ta[1]}, ta};
                    cands.add(cand(route, f, t, nodes, routed,
                            c[1] + l[1],
                            () -> {
                                usedH.get(hi).add(l[0]);
                                usedV.get(vi).add(c[0]);
                            }));
                }
            }
        }
        if (cands.isEmpty()) {
            return null;
        }
        Candidate best = cands.get(0);
        for (Candidate c : cands) {
            if (c.score < best.score) {
                best = c;
            }
        }
        best.commit.run();
        return dedupe(best.route);
    }

    /** 打分一个候选：穿框 + 与已路由边的交叉 + 通道拥挤度 */
    private static Candidate cand(
            double[][] route, Node f, Node t, List<Node> nodes,
            List<double[][]> routed, double slot, Runnable commit) {
        int score = countHits(route, f, t, nodes) * W_HIT
                + countCrossings(route, routed) * W_CROSS
                + (int) slot * W_SLOT;
        return new Candidate(route, score, commit);
    }

    /**
     * 扇出/扇入的锚点滑动量：同源（目标）第 idx 条、共 cnt 条时，
     * 沿边居中对称铺开，间距封顶 16px、且必须留在边内。
     */
    private static double fanSlide(int idx, int cnt, double span) {
        return SceneGeometryKit.fanSlide(idx, cnt, span);
    }

    /**
     * 框边缘锚点：alongX=true 锚在朝 coordinate 一侧的左/右边
     * （x 固定，y 按滑动量偏移并夹在边内）；false 为上/下边。
     */
    private static double[] sideAnchor(
            Node n, double coordinate, boolean alongX, double slide) {
        return SceneGeometryKit.sideAnchor(
                new double[] {n.x, n.y, n.w, n.h}, coordinate, alongX, slide);
    }

    /** 候选折线与全部已路由折线的交叉 + 共线重叠总数（端点豁免） */
    private static int countCrossings(
            double[][] route, List<double[][]> routed) {
        return SceneGeometryKit.countCrossings(route, routed);
    }

    /** 轴对齐线段相交（共线重叠也算）；交点贴任一端点 10px 内豁免 */
    private static boolean segCross(
            double[] p1, double[] p2, double[] p3, double[] p4) {
        return SceneGeometryKit.segCross(p1, p2, p3, p4);
    }

    /** 框的边缘锚点：alongX=true 取朝 coordinate 方向的左/右边中点 x，否则取上/下边中点 y */
    private static double side(Node n, double coordinate, boolean alongX) {
        return SceneGeometryKit.side(
                new double[] {n.x, n.y, n.w, n.h}, coordinate, alongX);
    }

    private static double clamp(double v, double[] interval) {
        return SceneGeometryKit.clamp(v, interval);
    }

    /**
     * 节点 bbox 外扩 4px 后的一维自由区间（并集的补集）。
     * 落在自由区间里的轴线永不与任何节点相交 —— 走廊的判定基础。
     * 区间外扩到图形包络外 60px，允许绕外侧走线。
     */
    private static List<double[]> freeIntervals(
            List<Node> nodes, boolean alongX) {
        List<double[]> spans = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            spans.add(alongX
                    ? new double[] {n.x, n.x + n.w}
                    : new double[] {n.y, n.y + n.h});
        }
        return SceneGeometryKit.freeIntervals(spans, 4, 60);
    }

    /** 走廊候选排序：与 [lo,hi] 重叠宽者优先，同宽取离区间中心近者 */
    private static List<Integer> rankCorridors(
            List<double[]> corridors, double lo, double hi) {
        return SceneGeometryKit.rankCorridors(corridors, lo, hi);
    }

    /**
     * 走廊内取一个与已用通道错开的坐标：先取理想点，冲突则按 14px
     * 步长向两侧探测；走廊太窄挤不下就退回理想点（宁叠不越界）。
     */
    private static double[] nextChannel(
            double[] interval, List<Double> used, double ideal) {
        return SceneGeometryKit.nextChannel(interval, used, ideal);
    }

    /**
     * 折线（绝对坐标）撞到多少个无关节点（两端框豁免，外扩 5px 容差）。
     * 轴对齐线段与矩形的相交就是一维区间的两两相交。容差 5 与
     * assertRoutesAvoidNodes 的 margin=4 同口径（2026-10-15 小场景
     * 实测：2px 容差放行了贴框 4px 的走廊，测试判穿、引擎放行）。
     * 之前 5px 误杀「贴框顶 4px 掠过」的问题已由模块流向排序根治
     * （不再产生必须贴缝绕行的对角线边），容差可以放心对齐。
     */
    private static int countHits(
            double[][] route, Node from, Node to, List<Node> nodes) {
        if (nodes == null) {
            return 0;
        }
        List<double[]> boxes = new ArrayList<>(nodes.size());
        int skipA = -1;
        int skipB = -1;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            if (n == from) {
                skipA = i;
            }
            if (n == to) {
                skipB = i;
            }
            boxes.add(new double[] {n.x, n.y, n.w, n.h});
        }
        return SceneGeometryKit.countHits(route, boxes, skipA, skipB, 5);
    }

    /** 去重零长段；两点直线补中点成三点（存活 SceneSanitizer 的 ≥3 点口径） */
    private static double[][] dedupe(double[][] route) {
        return SceneGeometryKit.dedupe(route);
    }

    /** 绝对坐标折线写回箭头（x/y = 首点，points = 相对偏移） */
    private void writeRoute(ObjectNode arrow, double[][] abs) {
        double ox = abs[0][0];
        double oy = abs[0][1];
        ArrayNode points = arrow.putArray("points");
        for (double[] p : abs) {
            ArrayNode item = points.addArray();
            item.add(round1(p[0] - ox));
            item.add(round1(p[1] - oy));
        }
        arrow.put("x", round1(ox));
        arrow.put("y", round1(oy));
    }

    /** 一位小数：与 ElkLayoutEngine.round1 同口径，防浮点尾巴进契约 */
    private static double round1(double value) {
        return SceneGeometryKit.round1(value);
    }

    /** 边标签字号（比节点文字小一档，mermaid 的视觉层级口径） */
    private static final double LABEL_FONT = 16;

    /** 标签离线段/框的净距 */
    private static final double LABEL_GAP = 16;

    /**
     * 边标签治理：去重 + 落位成独立文字。
     *
     * 2026-10-15 regress_arch5 用户圈出的两坨噪音都出在标签上：
     * ① 网关 4 条出线各带一个同名「路由转发」，而箭头标签渲染时自动
     *    居中在整条箭头包围盒中心 —— 4 条平行线中心几乎同点，标签摞成
     *    一摞；② 跨模块走廊里 6 条线 6 个标签全砸在走廊中段互相压线。
     * 根因是「标签位置从来没人管」：绑定标签由渲染器自动居中，不受控。
     *
     * 治法（全确定性代码，不劳 LLM）：
     * ① 去重 —— 同一源节点 + 同名文字的标签只保留首条（mermaid/draw.io
     *    口径：重复标注是噪音不是信息）；
     * ② 落位 —— 有 points 的箭头把标签编译成**独立文字元素**，放在出线
     *    段旁（先取首段，短了取最长段），垂直偏移 16px，沿段滑动找空位，
     *    与节点框/已放标签都不叠；实在放不下才保留绑定标签兜底。
     * 适配层已验证：无 containerId 的 text 元素坐标原样生效。
     */
    private void placeEdgeLabels(JsonNode root, Partition partition) {
        JsonNode elementsNode = root.get("elements");
        if (!(elementsNode instanceof ArrayNode elements)) {
            return;
        }
        // ① 同源同名去重
        Set<String> seen = new LinkedHashSet<>();
        List<ObjectNode> labeled = new ArrayList<>();
        for (JsonNode element : elements) {
            if (!"arrow".equals(text(element, "type"))
                    || !(element instanceof ObjectNode arrow)) {
                continue;
            }
            JsonNode label = arrow.get("label");
            if (label == null || !label.isObject()
                    || text(label, "text").isBlank()) {
                continue;
            }
            String key = bindingId(arrow, "start") + "|"
                    + text(label, "text").trim();
            if (!seen.add(key)) {
                arrow.remove("label");
                continue;
            }
            labeled.add(arrow);
        }
        // ② 落位为独立文字
        List<double[]> placed = new ArrayList<>();
        for (ObjectNode arrow : labeled) {
            JsonNode points = arrow.get("points");
            if (points == null || !points.isArray()
                    || points.size() < 2) {
                continue;
            }
            String content = text(arrow.get("label"), "text").trim();
            double[] size = labelSize(content);
            double ox = arrow.get("x").asDouble();
            double oy = arrow.get("y").asDouble();
            double[] spot = findLabelSpot(
                    points, ox, oy, size, partition.nodes, placed);
            if (spot == null) {
                continue;
            }
            ObjectNode label = objectMapper.createObjectNode();
            label.put("id", arrow.path("id").asText("") + "_lbl");
            label.put("type", "text");
            label.put("x", round1(spot[0] - size[0] / 2));
            label.put("y", round1(spot[1] - size[1] / 2));
            // 不写 width/height：schema text 元素 additionalProperties=false，
            // 适配层按字数（CJK 0.9×fontSize）自算尺寸
            label.put("text", content);
            label.put("fontSize", LABEL_FONT);
            elements.add(label);
            arrow.remove("label");
            placed.add(new double[] {
                spot[0] - size[0] / 2, spot[1] - size[1] / 2,
                spot[0] + size[0] / 2, spot[1] + size[1] / 2});
        }
    }

    /**
     * 估算标签外框：CJK ≈ 0.9×fontSize/字、Latin ≈ 0.45×fontSize/字、
     * 行高 1.25×fontSize（与 sceneAdapter 的宽度真值同源）。
     */
    private static double[] labelSize(String content) {
        return SceneGeometryKit.labelSize(content, LABEL_FONT);
    }

    /**
     * 找一个不压节点框、不压已放标签的落点：候选段（首段优先，短了
     * 依次取后续段）中点两侧各偏 16px，再沿段方向 ±14~42px 滑动。
     */
    private double[] findLabelSpot(
            JsonNode points, double ox, double oy, double[] size,
            List<Node> nodes, List<double[]> placed) {
        int count = points.size();
        for (int i = 0; i < Math.min(count - 1, 4); i++) {
            double x1 = ox + points.get(i).get(0).asDouble();
            double y1 = oy + points.get(i).get(1).asDouble();
            double x2 = ox + points.get(i + 1).get(0).asDouble();
            double y2 = oy + points.get(i + 1).get(1).asDouble();
            double len = Math.hypot(x2 - x1, y2 - y1);
            if (i == 0 && len < 24 && count > 2) {
                continue;
            }
            double mx = (x1 + x2) / 2;
            double my = (y1 + y2) / 2;
            double dx = len < 1e-6 ? 0 : (x2 - x1) / len;
            double dy = len < 1e-6 ? 0 : (y2 - y1) / len;
            double nx = -dy;
            double ny = dx;
            for (int side = 1; side >= -1; side -= 2) {
                for (int step = 0; step <= 3; step++) {
                    // 滑动序列：0、-14、+14、-28（沿段方向交错探测）
                    double slide = step == 0 ? 0
                            : (step % 2 == 1 ? -1 : 1)
                                    * ((step + 1) / 2) * 14.0;
                    double cx = mx + nx * LABEL_GAP * side + dx * slide;
                    double cy = my + ny * LABEL_GAP * side + dy * slide;
                    double[] box = {
                        cx - size[0] / 2, cy - size[1] / 2,
                        cx + size[0] / 2, cy + size[1] / 2};
                    if (spotClean(box, nodes, placed)) {
                        return new double[] {cx, cy};
                    }
                }
            }
        }
        return null;
    }

    /** 落点与所有节点框（外扩 2px）和已放标签（外扩 4px）都不相交 */
    private static boolean spotClean(
            double[] box, List<Node> nodes, List<double[]> placed) {
        for (Node n : nodes) {
            if (box[2] >= n.x - 2 && box[0] <= n.x + n.w + 2
                    && box[3] >= n.y - 2 && box[1] <= n.y + n.h + 2) {
                return false;
            }
        }
        for (double[] p : placed) {
            if (box[2] >= p[0] - 4 && box[0] <= p[2] + 4
                    && box[3] >= p[1] - 4 && box[1] <= p[3] + 4) {
                return false;
            }
        }
        return true;
    }

    /**
     * 自底向上排一个块：先排它的子块，再把"自己的图形"和"子块"一起打包。
     *
 * 图形块内部交给 LayoutEngine（竖排），块与块之间用网格打包
     * 并留出 MODULE_GAP 宽的通道 —— 跨模块长边的标签
     * 中点就落在这条通道里，这是 5.3 验证有效的那个机制。
     */
    private void layoutBlock(
            Block block, LayoutEngine engine, Partition partition,
            boolean horizontal) {
        for (Block child : block.children) {
            layoutBlock(child, engine, partition, horizontal);
        }
        List<Block> items = new ArrayList<>();
        if (!block.shapes.isEmpty()) {
            Block own = layoutOwnShapes(
                    block.shapes, engine, partition, horizontal);
            block.own = own;
            items.add(own);
        }
        items.addAll(block.children);

        if (items.isEmpty()) {
            block.contentW = 0;
            block.contentH = 0;
        } else {
            double[] size = pack(items, LayoutSpec.MODULE_GAP,
                    partition);
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
     * <p>两处 2026-10-16 五图实测的修正：
     *
     * <p><b>① 边标签剥离子场景</b>：模块内箭头不带 label 进布局 ——
     * 标签由 {@link #placeEdgeLabels} 在排版落定后独立落位（放在
     * 线段旁，不压在线上），而布局引擎的层间距 / 兄弟间距会按最长
     * 边标签撑大（认证图 "Authenticated Request (JWT)" 270px 把层
     * 间距撑到 280px，整图 0.64 长宽比）。剥掉后模块内间距只受
     * {@link LayoutSpec#LAYER_GAP} 地板值约束。箭头路由（points）
     * 由引擎写进副本，排完必须回写到原件 —— 最终场景序列化的是
     * 原件，副本上的几何丢了就是"箭头悬空"。
     *
     * <p><b>② 方向由整图试排定</b>：{@code horizontal} 来自
     * {@link #arrangeModules} 的全局取舍，模块自己的长宽比说明不了
     * 该横还是该竖（见 {@link LayoutEngine#layoutModule(String, Set, boolean)}）。
     *
     * <p>返回的占位块只带尺寸（`shapes` 为空）：真正的图形仍归
     * 外层块持有，避免同一次平移被算两遍。
     */
    private Block layoutOwnShapes(
            List<Node> shapes, LayoutEngine engine, Partition partition,
            boolean horizontal) {
        Set<String> ids = new LinkedHashSet<>();
        for (Node node : shapes) {
            ids.add(node.id);
        }
        ArrayNode sub = objectMapper.createArrayNode();
        for (Node node : shapes) {
            sub.add(node.json);
        }
        // 模块内箭头原件（id → 元素）：路由回写的目标
        Map<String, ObjectNode> arrowOriginals = new LinkedHashMap<>();
        for (JsonNode element : partition.rawElements) {
            if (!"arrow".equals(text(element, "type"))) {
                continue;
            }
            String from = bindingId(element, "start");
            String to = bindingId(element, "end");
            if (from != null && to != null
                    && ids.contains(from) && ids.contains(to)) {
                // 剥标签进副本：引擎的间距估算看不到标签，模块内
                // 间距回到地板值；label 留在原件上给 placeEdgeLabels
                ObjectNode copy = ((ObjectNode) element).deepCopy();
                copy.remove("label");
                sub.add(copy);
                arrowOriginals.put(
                        text(element, "id"), (ObjectNode) element);
            }
        }
        ObjectNode scene = objectMapper.createObjectNode();
        scene.set("elements", sub);
        // 模块内用 layoutModule：禁掉"按视口翻转主方向"的启发式，
        // 否则竖链模块（长宽比 0.28）每张图都会被压成横排长条
        String laid = engine.layoutModule(
                scene.toString(), Set.of(), horizontal);

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

        // 路由回写：引擎把 points/x/y 写进了剥标签的副本，原件才是
        // 最终序列化的对象 —— 不回写就等于模块内箭头全部丢几何
        for (Map.Entry<String, ObjectNode> entry
                : arrowOriginals.entrySet()) {
            JsonNode laidArrow = byId.get(entry.getKey());
            if (laidArrow == null) {
                continue;
            }
            ObjectNode original = entry.getValue();
            if (laidArrow.has("points")) {
                original.set("points",
                        laidArrow.get("points").deepCopy());
            }
            if (isNumber(laidArrow, "x")) {
                original.put("x", number(laidArrow, "x"));
            }
            if (isNumber(laidArrow, "y")) {
                original.put("y", number(laidArrow, "y"));
            }
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
        box.ownArrows.addAll(arrowOriginals.values());
        return box;
    }

    /**
     * 网格打包：试遍列数，取长宽比最接近 TARGET_ASPECT 的一种。
     *
 * 确定性是刻意的 —— 同一份输入必须得到同一份坐标，否则
     * SceneDrift（几何保真）与 E2E 断言都无从谈起。
     *
     * @return 打包后的整体尺寸
     */
    private double[] pack(
            List<Block> items, double gap, Partition partition) {
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
                    (size[0] / size[1]) / TARGET_ASPECT))
                    + BACKWARD_EDGE_COST
                    * backwardEdges(items, partition.flow);
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

    /** 把局部坐标累加成全局坐标（`dx/dy` = 本块内容区左上角） */
    private void place(Block block, double dx, double dy) {
        if (block.own != null) {
            double ox = dx + block.own.ox;
            double oy = dy + block.own.oy;
            for (Node node : block.shapes) {
                node.x += ox;
                node.y += oy;
            }
            // 🔴 模块内箭头的 x/y 是"子场景局部坐标"（由 layoutOwnShapes
            // 从引擎写回），必须随模块一起平移。此前只平移了节点，
            // 箭头留在局部坐标上 —— 箭头本体在渲染端按 start/end 绑定
            // 重算几何，看不出问题；但 placeEdgeLabels 是拿箭头 x/y
            // 当绝对原点算标签落点的，于是标签全部飘到图外
            // （2026-10-16 RAG 实测：7 个步骤标签齐刷刷落在左侧空白栏，
            // 距真实边 700px+）。points 是相对首点的偏移，无需改。
            for (ObjectNode arrow : block.own.ownArrows) {
                if (isNumber(arrow, "x")) {
                    arrow.put("x",
                            round1(arrow.get("x").asDouble() + ox));
                }
                if (isNumber(arrow, "y")) {
                    arrow.put("y",
                            round1(arrow.get("y").asDouble() + oy));
                }
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
 * 宽高也要写（修）：layoutOwnShapes 是把
     * 模块内的图形拼成一份子场景交给引擎的，引擎在重新解析出来的副本上
     * 干活 —— 它写回的尺寸只留在那份副本里，不抄回来就等于没写。而尺寸必须
     * 一致：引擎按"渲染尺寸"算的层间距，契约里若留着小一号的旧尺寸，渲染端
     * 会把容器撑高，层间距当场失效（实测题 2 两处图形重叠）。
     */
    private void writeShapes(List<Node> nodes) {
        for (Node node : nodes) {
            // 🔴 吸附后的值必须**写回内存**，不能只写进 json。
            // 下游（routeCrossModuleArrows 的走廊、placeEdgeLabels 的
            // spotClean）都拿 node.x/node.y 当"已渲染的框"来判断
            // 压不压得住 —— 内存里留着小几个 px 的未吸附值，判定就会
            // 说"这里空着"，实际渲染出来却压进去几个 px
            // （2026-10-16 auth 实测：'支付状态' 与 'Inactive' 两个标签
            // 各压住一个节点的边 5px，根因就是这个差值）。
            node.x = LayoutSpec.snap(node.x);
            node.y = LayoutSpec.snap(node.y);
            node.json.put("x", node.x);
            node.json.put("y", node.y);
            node.json.put("width", node.w);
            node.json.put("height", node.h);
        }
    }

    /**
     * 回写画框几何与成员。
     *
 * `children` 由后端算、不交给 LLM —— 它填不准，填错还会连带
     * 影响整批转换（见 `constraint.md`）。成员口径：没有画框的子块
     * 直接把成员上交给外层框，有画框的子块则整体作为外层框的一个成员
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
     * 抹掉"不该由 LLM 填"的 `children`。
     *
 * 只在排版未介入、而画布里确实带着 children 时才走这条路 ——
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
        /** 顶层模块间有向边计数（模块名 → 目标名 → 边数），打包评估逆流惩罚用 */
        private Map<String, Map<String, Integer>> flow = Map.of();

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
    static final class Node {
        final ObjectNode json;
        final String id;
        /**
         * 尺寸：初值是种子尺寸，引擎排完会改写它 —— 引擎写回的是
         * "渲染端最终会画多大"，模块包围盒与 frame 必须按它算，否则框会
         * 按旧尺寸包住新图形（起引擎写回尺寸）。
         */
        double w;
        double h;
        double x;
        double y;

        Node(ObjectNode json, String id, double w, double h) {
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

        /**
         * 本块"内部箭头"的原件（两端都在本块内的箭头）。
         *
         * <p>它们的 x/y 由子场景引擎写成**局部坐标**，必须随模块
         * 一起平移 —— 见 {@link #place}。不记下来就没人平移它们。
         */
        private final List<ObjectNode> ownArrows = new ArrayList<>();
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
