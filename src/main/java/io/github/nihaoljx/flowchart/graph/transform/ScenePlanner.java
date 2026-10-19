package io.github.nihaoljx.flowchart.graph.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.github.nihaoljx.flowchart.graph.validate.RelationCatalog;
import io.github.nihaoljx.flowchart.graph.layout.GroupPath;
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
            Partition partition = partition(elements,
                    elementsNode instanceof ArrayNode arr ? arr : null);

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
        return partition(elements, null);
    }

    /**
     * 分区，并在需要时把「删掉的元素」同步写回所属数组。
     *
     * `arrange()` 的 elements 是从 root 的 ArrayNode 拆出来的独立 List，
     * 在 List 上删元素不会动到 root —— 而 matchFrames 合并同名框正是
     * 靠删除实现的（2026-10-19：删了 List 没删 ArrayNode，多出来的框
     * 仍旧原样写出，图上「架构」画三遍）。传 owner 进来就两边同步。
     */
    private Partition partition(List<JsonNode> elements, ArrayNode owner) {
        List<Node> nodes = collectNodes(elements);

        // ① LLM 标注：图形自带的 group 直接建模块树
        //
        // 建树前先剔掉「其实是某个节点自己」的层：模型常把层名同时写成
        // 节点 label 和一层 group（2026-10-19 data-pipeline 实跑，
        // data_collection 叫「数据采集层」、flume 又挂在额外的「数据采集层」
        // 层里），不剔的话「大数据处理系统」「架构」会被拆成多套同名框。
        //
        // 判据是「那个同名节点自己就在这一层里吗」：组合态标准写法
        // （order/处理中/支付中 各自 label 等于自己所在层的名字）必须保留，
        // 只有同名节点在别处时那层才是多写的。
        Map<String, List<String>> labelOwners = new LinkedHashMap<>();
        for (Node node : nodes) {
            String label = nodeLabel(node.json);
            if (label != null && !label.isEmpty()) {
                labelOwners.putIfAbsent(label, groupPath(node.json));
            }
        }
        // 层名若是某个图形的 id（模型图省事把 group 写成英文 id：2026-10-19
        // state-machine 实跑外层 group 写 `processing`，而那个节点的
        // label 是「处理中」），替换成它的 label —— 框上写英文 id 用户看不懂。
        // 只在该 id 存在且 label 非空、且两者不同时替换；同名节点自己
        // （label == id）不替换。
        Map<String, String> idLabels = new LinkedHashMap<>();
        for (Node node : nodes) {
            String label = nodeLabel(node.json);
            if (label != null && !label.isEmpty() && !label.equals(node.id)) {
                idLabels.putIfAbsent(node.id, label);
            }
        }
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Node node : nodes) {
            List<String> raw = groupPath(node.json);
            List<String> path = GroupPath.stripNodeLikeLayers(raw, raw, labelOwners);
            path = translateLayers(path, idLabels);
            if (!path.isEmpty()) {
                grouped.put(node.id, path);
            }
        }
        // 把路径方向归一（从内到外）并写回元素本身。写回是必须的：
        // 下游 addGroupFrames / 回灌提示词都读 group 字段，不写回会得到
        // 「树按归一后排、框按原始画」的两套层级（框照样重复、还互相错位）。
        normalizeGroupOrientation(grouped);
        for (Node node : nodes) {
            List<String> path = grouped.get(node.id);
            if (path != null) {
                GroupPath.write((ObjectNode) node.json, path);
            }
        }
        Block root = buildTree(nodes, grouped);
        int moduleCount = countModules(root);

        // ② 后端聚类兜底已删（2026-10-15）：此前「节点数 > 15 就按
        // 拓扑/标签前缀猜模块」。分不分区是语义决策 —— 模型读了用户
        // 需求，它不标组说明需求里没有分区语义；后端按前缀猜错的
        // 模块比不分区更难看（mermaid 的 subgraph 同哲学：只认显式声明）。

        matchFrames(root, elements, owner, idLabels);
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
     *
     * <p>入参路径必须已经是**从内到外**（{@link #normalizeGroupOrientation}
     * 保证），这里只做机械建树。
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

    /**
     * 把「图形 id → 分组名列表」统一排成**从内到外**，原地改写。
     *
     * <p>模型写数组的顺序不可靠：同一份 dataflow，一次给
     * `["架构","大数据处理系统","数据采集层"]`，另一次给
     * `["大数据处理系统","数据采集层"]` —— 同一个「大数据处理系统」前后挪了一位。
     * 按契约（从内到外）硬解释的后果是整张图被拆成六套同名框
     * （2026-10-19 suite15 实测：14 个节点分入 12 个模块、6 个同名根框）。
     *
     * <p>可靠的是**跨路径重复出现的公共层**：`大数据处理系统` 在 6 条路径里
     * 都出现，每条末位的 `数据采集层`/`数据存储层`/… 只出现一次 ——
     * 公共的那个必然在所有分支之上，是外层。判据依次是：
     * 出现次数多者在外 → 成员集合真包含者在外 → 多数投票位置深者在内。
     */
    /**
     * 把分组层名里的「图形 id」换成该图形的 label，其余原样保留。
     *
     * <p>模型会图省事把 group 写成英文 id（2026-10-19 state-machine
     * 实跑：外层 group 是 `processing`，对应节点的 label 是「处理中」，
     * 端上框标题就印成英文 processing）。只替换确实存在同名图形、
     * 且该图形 label 与 id 不同的层。
     */
    private static List<String> translateLayers(
            List<String> path, Map<String, String> idLabels) {
        if (path.isEmpty() || idLabels.isEmpty()) {
            return path;
        }
        List<String> out = new ArrayList<>(path.size());
        for (String name : path) {
            out.add(idLabels.getOrDefault(name, name));
        }
        return out;
    }

    private static void normalizeGroupOrientation(
            Map<String, List<String>> grouped) {
        List<List<String>> paths = new ArrayList<>(grouped.values());
        if (paths.isEmpty()) {
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> votes = new LinkedHashMap<>();
        Map<String, Set<String>> members = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            List<String> path = entry.getValue();
            for (int i = 0; i < path.size(); i++) {
                String name = path.get(i);
                counts.merge(name, 1, Integer::sum);
                // 路径是从内到外排 ⇒ 越靠前越深，给大值
                votes.merge(name, path.size() - i, Integer::sum);
                members.computeIfAbsent(name, k -> new LinkedHashSet<>())
                        .add(entry.getKey());
            }
        }
        List<String> names = new ArrayList<>(counts.keySet());
        names.sort((a, b) -> {
            int byCount = Integer.compare(counts.get(b), counts.get(a));
            if (byCount != 0) {
                return byCount;
            }
            Set<String> sa = members.getOrDefault(a, Set.of());
            Set<String> sb = members.getOrDefault(b, Set.of());
            if (sb.containsAll(sa) && !sa.containsAll(sb)) {
                return 1;
            }
            if (sa.containsAll(sb) && !sb.containsAll(sa)) {
                return -1;
            }
            return Integer.compare(
                    votes.getOrDefault(b, 0), votes.getOrDefault(a, 0));
        });
        // rank 越小 = 越靠数组前面 = 越内层（契约要求从内到外）。
        // 所以出现次数多的公共层要拿到**更大**的 rank，排序后才落到尾部。
        Map<String, Integer> rank = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            rank.put(names.get(i), names.size() - 1 - i);
        }
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            List<String> path = entry.getValue();
            if (path.size() < 2) {
                continue;
            }
            // GroupPath.parse 返回 List.copyOf 的**不可变**列表，
            // 直接 path.sort 会抛 UnsupportedOperationException，被
            // arrange 的 catch 吞成「排版跳过，退回单层布局」——
            // 整条分区路径失效，框退回按原始 group 合成（同名框 x6）。
            // 所以必须拷贝一份再排。
            List<String> sorted = new ArrayList<>(path);
            sorted.sort((a, b) -> Integer.compare(
                    rank.getOrDefault(a, 0), rank.getOrDefault(b, 0)));
            grouped.put(entry.getKey(), sorted);
        }
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

    /**
     * 读图形上的文字，兼容字符串与 `{"text":"..."}` 两种写法
     * （LLM 实跑两种都出现过，2026-10-19 dataflow 全是对象形态）。
     */
    private static String nodeLabel(JsonNode element) {
        JsonNode label = element.get("label");
        if (label == null) {
            return null;
        }
        if (label.isTextual()) {
            return label.asText().trim();
        }
        if (label.isObject()) {
            JsonNode text = label.get("text");
            if (text != null && text.isTextual()) {
                return text.asText().trim();
            }
        }
        return null;
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
    /**
     * 把 LLM 画的 `frame` 认领给同名模块。
     *
     * 先精确匹配，再"包含"匹配（LLM 常把 name 写成「订单域模块」这类）。
     * 匹配不上的一律原样不动 —— 不去猜它的成员：布局会把它原本覆盖的
     * 节点打散到不同模块，按"几何兜底"重算只会得到一个横跨全图的大框，
     * 比不处理更难看。
     *
     * 同名多框合并（2026-10-19 data-pipeline 实测）：模型会把同一个概念
     * 拆成好几个 frame —— 输入里「架构」出现 3 次、「大数据处理系统」也
     * 出现 3 次，每个只收自己那批成员。旧实现「块已被认领就跳过」让后
     * 两个框无处可去、原样留在图上，于是同名框各画三遍、互相错位、线条
     * 横穿整张画布。现在：模块只认第一个同名框（几何由 writeFrames 写成
     * 贴合成员），其余同名框直接删 —— 它们装的是同一概念下的不同成员，
     * 合并后成员都归那一个框，删除不丢信息。
     */
    private void matchFrames(Block root, List<JsonNode> elements, ArrayNode owner,
            Map<String, String> idLabels) {
        Set<String> claimed = new LinkedHashSet<>();
        List<JsonNode> redundant = new ArrayList<>();
        for (JsonNode element : elements) {
            if (!element.isObject()
                    || !"frame".equals(text(element, "type"))) {
                continue;
            }
            String name = text(element, "name");
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            String key = name.trim();
            // 框名若是某个图形的 id（模型画 frame 时也图省事写英文 id：
            // 2026-10-19 state-machine 实跑 frame name=`processing`，
            // 对应节点 label 是「处理中」），换成它的 label —— 框标题
            // 印英文 id 用户看不懂。块名来自 group（已被 translateLayers
            // 换过），所以这里只改框元素自己的 name、不动匹配键。
            String display = idLabels.getOrDefault(key, key);
            Block block = findBlock(root, key);
            if (block == null && !display.equals(key)) {
                // 块名来自 group 路径（已被 translateLayers 换成中文），
                // 框名还是英文 id，只按原名找会对不上 —— 框就成了没人
                // 认领的孤儿框，名字也改不掉（2026-10-19 statemachine
                // 实证：group 写 processing、框写 processing、块叫「处理中」）。
                block = findBlock(root, display);
            }
            if (block != null && block.frame == null) {
                block.frame = (ObjectNode) element;
                if (!display.equals(key)) {
                    block.frame.put("name", display);
                }
                claimed.add(key);
                continue;
            }
            // 同名框又来一个：这个块已被认领（装着同一概念的另一批成员），
            // 或压根没有对应模块 —— 都是多余的框
            if (claimed.contains(key) || block != null) {
                redundant.add(element);
            }
        }
        if (redundant.isEmpty()) {
            return;
        }
        elements.removeAll(redundant);
        if (owner != null) {
            // 同步删掉 root 数组里的副本：上面删的只是拆出来的 List
            for (int i = owner.size() - 1; i >= 0; i--) {
                for (JsonNode gone : redundant) {
                    if (owner.get(i) == gone) {
                        owner.remove(i);
                        break;
                    }
                }
            }
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
            Node fn = froms.get(slot);
            Node tn = tos.get(slot);
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
            writeRoute(arrows.get(slot), route, froms.get(slot), tos.get(slot));
            routedCount++;
            // A* / 模板兜底也可能是穿框解（freeIntervals 走廊不含
            // 端点贴框豁免，crossesNode 口径更严）：写入前再查一次，
            // 净穿的边保持 ELK/router 原线不动 —— 避免覆盖坏答案。
            if (routeCrosses(route, partition.nodes, froms.get(slot),
                    tos.get(slot))) {
                continue;
            }
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

    /**
     * 绝对折线是否净穿任何「非端点」节点框（口径同
     * {@link OrthoAStarRouter#hitsNode}：8px 外扩、端点贴框豁免）。
     */
    private static boolean routeCrosses(double[][] abs,
            List<Node> nodes, Node from, Node to) {
        for (int i = 0; i + 1 < abs.length; i++) {
            double[] a = abs[i];
            double[] e = abs[i + 1];
            for (Node n : nodes) {
                if (n == from || n == to) {
                    continue;
                }
                double px = n.x - 8;
                double py = n.y - 8;
                double qx = n.x + n.w + 8;
                double qy = n.y + n.h + 8;
                boolean horizontal = Math.abs(a[1] - e[1]) < 0.5;
                if (horizontal && a[1] >= py && a[1] <= qy
                        && Math.max(a[0], e[0]) >= px
                        && Math.min(a[0], e[0]) <= qx) {
                    boolean onBox = (e[0] >= px - 0.5 && e[0] <= qx + 0.5
                            && e[1] >= py - 0.5 && e[1] <= qy + 0.5)
                            || (a[0] >= px - 0.5 && a[0] <= qx + 0.5
                            && a[1] >= py - 0.5 && a[1] <= qy + 0.5);
                    if (!onBox) {
                        return true;
                    }
                }
                if (!horizontal && a[0] >= px && a[0] <= qx
                        && Math.max(a[1], e[1]) >= py
                        && Math.min(a[1], e[1]) <= qy) {
                    boolean onBox = (e[0] >= px - 0.5 && e[0] <= qx + 0.5
                            && e[1] >= py - 0.5 && e[1] <= qy + 0.5)
                            || (a[0] >= px - 0.5 && a[0] <= qx + 0.5
                            && a[1] >= py - 0.5 && a[1] <= qy + 0.5);
                    if (!onBox) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    /**
     * 「就近摆」权重：跨模块边的加权平均物理距离记多少分。
     *
     * <p>与长宽比项（|log(长宽比/1.5)|，量级 0.1~1）同量级，且
     * {@link #BACKWARD_EDGE_COST}（2.0）仍然更贵 —— 顺序搜索只有在
     * 「省下的距离」大于「多一条逆流边」时才敢于破坏流向。
     */
    private static final double PROXIMITY_COST = 1.0;

    /** 顺序搜索的改进阈值（浮点抖动不算改进）与轮数上限（保确定性耗时） */
    private static final double ORDER_EPS = 1e-6;

    private static final int ORDER_SEARCH_ROUNDS = 12;

    private static final int ORDER_SEARCH_BUDGET = 4000;

    /**
     * 就近摆局部搜索的总开关（默认开）。回归测试把它关掉，用同一份输入跑
     * 两遍做 A/B，确认强依赖模块在开启时被摆到一起（见
     * {@link ProximityOrderTest}）。
     */
    static boolean proximityEnabled = true;

    /**
     * 主轴续接虚拟边的总开关（默认开）。回归测试把它关掉，用同一份输入
     * 跑两遍做 A/B，确认「跨子块中转的本块内连接」被折叠成一条直连后
     * 主轴不再断成两截。
     */
    static boolean spineEnabled = true;

    /**
     * 块中心到块中心（含画框内边距）的曼哈顿距离，单位与 {@link Block#outerW()}
     * 一致。就近摆的「距离」必须量到盒子，不是量到序号 ——
     * 一个 900px 宽的模块和一个 240px 宽的模块相邻，代价差着 4 倍。
     */
    private static double boxDistance(Block a, Block b) {
        return Math.abs(a.ox + a.outerW() / 2 - (b.ox + b.outerW() / 2))
                + Math.abs(a.oy + a.outerH() / 2 - (b.oy + b.outerH() / 2));
    }

    /**
     * 当前摆法下跨模块边的「加权平均距离」，以平均模块半径为单位。
     *
     * <p>归一化的理由：不归一会让十几条边各自贡献几百 px，把长宽比项
     * 与逆流罚项彻底淹没，搜索就会为了缩短一条线把整图拉成一根长条。
     * 归一后好摆法 ≈1~2、坏摆法 ≈3~5，三者可比。
     */
    private static double proximityCost(
            List<Block> items, Map<String, Map<String, Integer>> flow) {
        if (flow.isEmpty()) {
            return 0;
        }
        double scale = 0;
        for (Block b : items) {
            scale += (b.outerW() + b.outerH()) / 2;
        }
        scale = Math.max(1, scale / items.size());
        Map<String, Block> byName = new LinkedHashMap<>();
        for (Block b : items) {
            byName.put(b.name, b);
        }
        double sum = 0;
        double weights = 0;
        for (Map.Entry<String, Map<String, Integer>> e : flow.entrySet()) {
            Block from = byName.get(e.getKey());
            if (from == null) {
                continue;
            }
            for (Map.Entry<String, Integer> t : e.getValue().entrySet()) {
                Block to = byName.get(t.getKey());
                if (to == null) {
                    continue;
                }
                int w = t.getValue();
                sum += w * boxDistance(from, to);
                weights += w;
            }
        }
        return weights > 0 ? sum / weights / scale : 0;
    }

    /**
     * 某一列数下的打包总分：长宽比 + 逆流罚 + 就近摆。
     *
     * <p>必须把 cols 一起评：模块进的是网格（{@link #applyGrid} 行优先），
     * 序号相邻 ≠ 物理相邻 —— 一行的末位与下一行的首位序号相邻、物理上
     * 却隔着整行。只看 1 维序号的代价函数会把这种摆法当成好摆法。
     */
    private double packScore(
            List<Block> items, int cols, double gap,
            Map<String, Map<String, Integer>> flow) {
        double[] size = applyGrid(items, cols, gap);
        if (size[0] <= 0 || size[1] <= 0) {
            return Double.MAX_VALUE;
        }
        return Math.abs(Math.log((size[0] / size[1]) / TARGET_ASPECT))
                + BACKWARD_EDGE_COST * backwardEdges(items, flow)
                + PROXIMITY_COST * proximityCost(items, flow);
    }

    /** 试遍列数后的最好成绩（局部搜索的评估函数） */
    private double bestPackScore(
            List<Block> items, double gap,
            Map<String, Map<String, Integer>> flow) {
        double best = Double.MAX_VALUE;
        for (int cols = 1; cols <= items.size(); cols++) {
            best = Math.min(best, packScore(items, cols, gap, flow));
        }
        return best;
    }

    /**
     * 顶层模块的「就近摆」局部搜索：交换两块 / 抽出一块插到别处，
     * 只在总分下降时接受，反复到不动点。
     *
     * <p>用户口径（2026-10-17）：「这个已取消为什么不放到右边，这样线
     * 不就可以直接画一根过去，不用弯弯绕绕」。拓扑序只保证上下游顺序
     * （{@link #orderModulesByFlow}），不保证邻接 —— 强依赖的两个模块
     * 被排到带子的两端，边只能横贯整张画布。这里补的就是邻接性：
     * 目标函数就是「跨模块边的加权平均物理距离」，与格式塔的邻近原则
     * 同义（WorkBuddy 准则：频繁连线的两个框物理距离最近）。
     *
     * <p>确定性：候选按固定顺序枚举、只在严格下降时接受、有轮数与
     * 评估次数上限 ⇒ 同一份输入必得同一份顺序（{@link SceneDrift} 与
     * E2E 断言都依赖这一点）。
     */
    private void orderModulesByProximity(
            List<Block> items, double gap, Partition partition,
            Map<String, Map<String, Integer>> flow) {
        if (items.size() < 3 || flow.isEmpty()) {
            return;
        }
        List<Block> current = new ArrayList<>(items);
        double cost = bestPackScore(current, gap, flow);
        int evaluated = 0;
        boolean improved = true;
        for (int round = 0; round < ORDER_SEARCH_ROUNDS
                && improved && evaluated < ORDER_SEARCH_BUDGET; round++) {
            improved = false;
            for (int i = 0; i < current.size() && !improved; i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    List<Block> trial = new ArrayList<>(current);
                    Collections.swap(trial, i, j);
                    double c = bestPackScore(trial, gap, flow);
                    evaluated++;
                    if (c < cost - ORDER_EPS) {
                        current = trial;
                        cost = c;
                        improved = true;
                        break;
                    }
                }
            }
            for (int i = 0; i < current.size() && !improved; i++) {
                for (int j = 0; j < current.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    List<Block> trial = new ArrayList<>(current);
                    Block moved = trial.remove(i);
                    trial.add(j, moved);
                    double c = bestPackScore(trial, gap, flow);
                    evaluated++;
                    if (c < cost - ORDER_EPS) {
                        current = trial;
                        cost = c;
                        improved = true;
                        break;
                    }
                }
            }
        }
        items.clear();
        items.addAll(current);
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

    /**
     * 绝对坐标折线写回箭头（x/y = 首点，points = 相对偏移）。
     *
     * <p>两端若是菱形/椭圆，先把端点收进真实斜边（用户 m06890 截图：
     * 「库存是否充足」菱形出边悬空 10px，探针 ek0 实测 10.0 / ek1 11.5）。
     * 锚点原本按外接矩形算 —— ELK 的 section start/end 落在外接矩形边上，
     * 前端 snapArrowEndsToAnchors 也只会沿当前方向推到 bbox 边，都看不到
     * 菱形斜边，二者叠加就是那 10px 空隙。
     *
     * <p>矩形/未知形状原样返回，因此矩形连线观感零变化。
     *
     * @param arrow 被写回的箭头元素
     * @param abs   绝对坐标折线（会被就地收端点）
     * @param from  源节点（拿 type 与最终 x/y/w/h）
     * @param to    目标节点
     */
    private void writeRoute(ObjectNode arrow, double[][] abs, Node from, Node to) {
        snapEndpointsToShapes(abs, from, to);
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

    /**
     * 把折线首/末点按形状真实轮廓收进菱形/椭圆边界。
     *
     * <p>射线取「端点 → 折线第二个点」的方向：端点一般就在外接矩形边上，
     * 该方向几乎必然穿过形状内部，能取到从外侧进入的那个交点。若求不出
     * 正交点（端点在中心、形状退化等）保持原值不动 —— 不造 NaN。
     */
    private static void snapEndpointsToShapes(double[][] abs, Node from, Node to) {
        if (abs.length < 2) {
            return;
        }
        if (from != null && SceneGeometryKit.hasSlopedBoundary(from.type())) {
            double[] p = SceneGeometryKit.edgePointOnShape(
                    abs[0], abs[1],
                    new double[] {from.x, from.y, from.w, from.h}, from.type());
            if (p != null && p != abs[1]) {
                abs[0] = p;
            }
        }
        if (to != null && SceneGeometryKit.hasSlopedBoundary(to.type())) {
            double[] p = SceneGeometryKit.edgePointOnShape(
                    abs[abs.length - 1], abs[abs.length - 2],
                    new double[] {to.x, to.y, to.w, to.h}, to.type());
            if (p != null && p != abs[abs.length - 2]) {
                abs[abs.length - 1] = p;
            }
        }
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
     * 短标签绑回箭头的宽度上限（px）。超过这个宽度的标签落在线中段可读性
     * 不够，仍走独立文字落位。约 8 个 CJK 字 / 16 个 Latin 字。
     */
    private static final double BIND_MAX_WIDTH = 96;

    /**
     * 边标签治理：去重 + 短标签绑回箭头 + 长标签落位成独立文字。
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
     * ② 短标签绑回箭头 —— 短（宽度 ≤ {@link #BIND_MAX_WIDTH}）且箭头
     *    **中线**处既不压节点框、也不被别的线穿过的标签，编译成带
     *    {@code containerId = 箭头 id} 的文字，由适配层落在线段中点
     *    （绑定即归属，编辑时随箭头整体移动，不再是一坨游离文字）；
     * ③ 长标签 / 中线被占 —— 退化为**独立文字元素**，放在出线段旁
     *    （先取首段，短了取最长段），垂直偏移 16px，沿段滑动找空位，
     *    与节点框/已放标签都不叠、且被其它线穿过最少；实在放不下才
     *    保留绑定标签兜底。
     * 适配层已验证：无 containerId 的 text 元素坐标原样生效；带
     * containerId 指向 arrow/line 的文字会被定位到折线中点。
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
        // ② 短标签绑回箭头；长标签 / 中线被占 → 落位为独立文字
        List<double[]> placed = new ArrayList<>();
        List<double[]> segs = new ArrayList<>();
        List<String> segOwners = new ArrayList<>();
        collectArrowSegments(elements, segs, segOwners);
        Set<String> boundSources = new LinkedHashSet<>();
        for (ObjectNode arrow : labeled) {
            JsonNode points = arrow.get("points");
            if (points == null || !points.isArray()
                    || points.size() < 2) {
                continue;
            }
            String owner = arrow.path("id").asText("");
            String content = text(arrow.get("label"), "text").trim();
            double[] size = labelSize(content);
            double ox = arrow.get("x").asDouble();
            double oy = arrow.get("y").asDouble();
            double[] mid = arrowMidpoint(points, ox, oy);
            boolean binds = size[0] <= BIND_MAX_WIDTH
                    && boundSources.add(owner)
                    && canBindToArrow(
                            arrow, size, mid, segs, segOwners, partition);
            if (binds) {
                ObjectNode label = objectMapper.createObjectNode();
                label.put("id", owner + "_lbl");
                label.put("type", "text");
                // x/y 仅占位：GraphValidator 要求 text 必有坐标，适配层
                // bindContainerTexts 会按折线中点重算并覆盖（见 sceneAdapter）。
                label.put("x", round1(mid[0] - size[0] / 2));
                label.put("y", round1(mid[1] - size[1] / 2));
                label.put("text", content);
                label.put("fontSize", LABEL_FONT);
                label.put("containerId", owner);
                elements.add(label);
                arrow.remove("label");
                continue;
            }
            double[] spot = findLabelSpot(
                    points, ox, oy, size, partition.nodes, placed,
                    owner, segs, segOwners);
            if (spot == null) {
                continue;
            }
            ObjectNode label = objectMapper.createObjectNode();
            label.put("id", owner + "_lbl");
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
     * 折线的长度加权中点（与前端 {@code linearMidpoint} 同口径）：短标签
     * 绑回箭头时落在线中段，而不是整条包围盒中心。
     */
    private static double[] arrowMidpoint(JsonNode points, double ox, double oy) {
        List<double[]> pts = new ArrayList<>();
        for (JsonNode p : points) {
            pts.add(new double[] {
                ox + p.path(0).asDouble(), oy + p.path(1).asDouble()});
        }
        double total = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            total += Math.hypot(
                    pts.get(i + 1)[0] - pts.get(i)[0],
                    pts.get(i + 1)[1] - pts.get(i)[1]);
        }
        if (total < 1e-6) {
            return pts.get(0);
        }
        double half = total / 2;
        double acc = 0;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double segLen = Math.hypot(
                    pts.get(i + 1)[0] - pts.get(i)[0],
                    pts.get(i + 1)[1] - pts.get(i)[1]);
            if (acc + segLen >= half) {
                double t = (half - acc) / segLen;
                return new double[] {
                    pts.get(i)[0] + t * (pts.get(i + 1)[0] - pts.get(i)[0]),
                    pts.get(i)[1] + t * (pts.get(i + 1)[1] - pts.get(i)[1])};
            }
            acc += segLen;
        }
        return pts.get(pts.size() - 1);
    }

    /**
     * 短标签能否绑回这根箭头：箭头中线处的外框不能压节点框、也不能被
     * 别人的线穿过（自己的线整体豁免）。两个条件都满足才绑。
     *
     * <p>自己的线为什么整体豁免、而不是用「真切净框」口径：正交折线的
     * 中点几乎必然坐在某个折点上（每段等长时就正好是折角），标签框是
     * 「中点 ± 半尺寸」，于是框的一半永远盖在那段自己的线上 —— 实测
     * 竖直线 x=80 两段各 100px，中点折角 (80,160) 的标签盒
     * {58.4,150,101.6,170} 被两段真切穿过，inset 1/3/5/7 全 cuts=true，
     * 任何深度阈值都会把「短标签绑回箭头」整个灭掉。前端
     * {@code bindContainerTexts} 也只按线性中点把字摆上去、不避自己的
     * 线（既有的渲染行为），所以这是结构必然而非缺陷。但别人的线一条
     * 都不放过：真压线就别绑，退化成独立文字由 {@link #findLabelSpot}
     * 另找干净落点。
     */
    private boolean canBindToArrow(
            ObjectNode arrow, double[] size, double[] mid,
            List<double[]> segs, List<String> segOwners,
            Partition partition) {
        String id = arrow.path("id").asText("");
        double[] box = {
            mid[0] - size[0] / 2, mid[1] - size[1] / 2,
            mid[0] + size[0] / 2, mid[1] + size[1] / 2};
        for (Node n : partition.nodes) {
            if (box[2] >= n.x - 2 && box[0] <= n.x + n.w + 2
                    && box[3] >= n.y - 2 && box[1] <= n.y + n.h + 2) {
                return false;
            }
        }
        // 自己的线在绑定路径上整体豁免：折线中点恰是折角时标签框有一
        // 半会盖在那零点几 px 的折角上（正交折线的中点几乎必然是节点），
        // 深度内缩判据在这里救不了 —— 实测竖直线 x=80 两段，中点折角
        // (80,160) 的标签盒 {58.4,150,101.6,170} 被两段真切穿过，inset
        // 1/3/5/7 全 cuts=true（2026-10-18 probe 实跑）。前端
        // bindContainerTexts 也只按线性中点把字摆上去、不避自己的线，
        // 这是渲染期既有行为。但**别人的线**一条都不放过，真压线仍会
        // 退化为独立文字。
        int foreign = 0;
        for (int i = 0; i < segs.size(); i++) {
            if (id.equals(segOwners.get(i))) {
                continue;
            }
            if (SceneGeometryKit.segHitsBox(segs.get(i), box)) {
                foreign++;
            }
        }
        return foreign == 0;
    }

    /**
     * 估算标签外框：CJK ≈ 0.9×fontSize/字、Latin ≈ 0.45×fontSize/字、
     * 行高 1.25×fontSize（与 sceneAdapter 的宽度真值同源）。
     */
    private static double[] labelSize(String content) {
        return SceneGeometryKit.labelSize(content, LABEL_FONT);
    }

    /**
     * 找一个不压节点框、不压已放标签的落点，并在这些落点里挑**被别人的
     * 线穿过最少**的那个（2026-10-17 用户口径「线条和文字全部叠在一起」）。
     *
     * <p>候选段（首段优先，短了依次取后续段）中点两侧各偏 16px，再沿段
     * 方向 ±14~42px 滑动。硬冲突仍是节点框与已放标签（台下不了）；线穿字
     * 只作罚分，因为线是布完局才有的、标签不能因此不落位。文字元素没有
     * 底色，线穿字就是不可读，与 draw.io 的 {@code mxEdgeLabelLayout}
     * 只避顶点、不管边（实测就是我们的病）不同向。
     *
     * <p>自己那条边不算：标签本来就贴着它的中段放，与 ELK
     * {@code edgeLabels.inline} 允许压自己的边同口径。
     *
     * <p>包私有（非 private）是为了让 transform 包内的落位单测直接驱动
     * 这个纯函数：坐标由入参决定，不必靠整图排版碰运气。
     */
    double[] findLabelSpot(
            JsonNode points, double ox, double oy, double[] size,
            List<Node> nodes, List<double[]> placed,
            String owner, List<double[]> segs, List<String> segOwners) {
        int count = points.size();
        if (count < 2) {
            return null;
        }
        double[] best = null;
        int bestCross = Integer.MAX_VALUE;
        // 法向偏移（像素）：先近后远，近处更贴合线段、观感更紧
        double gap = LABEL_GAP;
        double[] normalOffsets = {gap, gap * 2, gap * 3};
        // 沿段滑动（像素）：交错探测，覆盖更宽范围以逃离别的线
        double[] slides = {0, -14, 14, -28, 28, -42, 42, -56, 56};
        for (int i = 0; i < count - 1; i++) {
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
                for (int no = 0; no < normalOffsets.length; no++) {
                    double off = normalOffsets[no] * side;
                    for (double slide : slides) {
                        double cx = mx + nx * off + dx * slide;
                        double cy = my + ny * off + dy * slide;
                        double[] box = {
                            cx - size[0] / 2, cy - size[1] / 2,
                            cx + size[0] / 2, cy + size[1] / 2};
                        if (!spotClean(box, nodes, placed)) {
                            continue;
                        }
                        int cross = countSegHits(box, segs, segOwners, owner);
                        if (cross < bestCross) {
                            bestCross = cross;
                            best = new double[] {cx, cy};
                        }
                        if (bestCross == 0) {
                            return best;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * 全部箭头的绝对坐标线段（含无标签的边）。标签落位要避开别人的线，
     * 只避节点框和已放标签是不够的。
     */
    private void collectArrowSegments(
            ArrayNode elements, List<double[]> segs, List<String> owners) {
        for (JsonNode element : elements) {
            if (!"arrow".equals(element.path("type").asText())) {
                continue;
            }
            JsonNode points = element.get("points");
            if (points == null || !points.isArray() || points.size() < 2) {
                continue;
            }
            double ox = element.path("x").asDouble();
            double oy = element.path("y").asDouble();
            String id = element.path("id").asText("");
            for (int i = 0; i + 1 < points.size(); i++) {
                segs.add(new double[] {
                    ox + points.get(i).get(0).asDouble(),
                    oy + points.get(i).get(1).asDouble(),
                    ox + points.get(i + 1).get(0).asDouble(),
                    oy + points.get(i + 1).get(1).asDouble()});
                owners.add(id);
            }
        }
    }

    /** 候选落点被多少条「别人的」线段穿过（罚分口径） */
    private static int countSegHits(
            double[] box, List<double[]> segs, List<String> owners,
            String owner) {
        return SceneGeometryKit.countSegHits(box, segs, owners, owner, true);
    }

    /**
     * 候选落点被多少条线段穿过，可指定自己的线的判定口径：
     * {@code strictOwnSegment} 见 {@link SceneGeometryKit#countSegHits}。
     */
    private static int countSegHits(
            double[] box, List<double[]> segs, List<String> owners,
            String owner, boolean strictOwnSegment) {
        return SceneGeometryKit.countSegHits(
                box, segs, owners, owner, strictOwnSegment);
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
                    block.shapes, engine, partition, horizontal,
                    block.children);
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
    /**
     * 主轴续接用的虚拟箭头：把「经过子块中转的本块内连接」折叠成一条直连。
     *
     * <p>只用于喂布局引擎排层，**不进 arrowOriginals、不会被写回原件**，
     * 所以图上不会多出一条线。判据：
     * <ol>
     *   <li>箭头 {@code u → v}，{@code u} 在本块 own、{@code v} 属于某个子块；</li>
     *   <li>存在箭头 {@code v' → w}，{@code v'} 属于**同一个子块**、
     *       {@code w} 在本块 own；</li>
     *   <li>{@code u != w}，且这条 {@code u → w} 本块内还没有直连箭头。</li>
     * </ol>
     * 命中即补一条 {@code u → w}。flow 图实测（2026-10-19）：本块
     * own 是 `用户下单→库存检查→支付处理` 与 `订单确认→物流处理` 两条
     * 断链 + 孤立节点 `订单完成`，而 `支付处理 →(支付宝/微信/银行卡)→
     * 订单确认` 的中转全在子块里 —— 补上虚拟边后主轴连成一条竖线。
     */
    private List<JsonNode> virtualSpineArrows(
            List<Node> shapes, Set<String> ownIds, Partition partition,
            List<Block> childBlocks) {
        List<JsonNode> out = new ArrayList<>();
        // 中转容器 = 本块自己的子块 + **不属于本块的其他块**（兄弟块）。
        //
        // 判据早期只看 childBlocks，实测无效：flow 图的块树是
        //   根(shapes=[]) → [主流程(6 个主轴节点), 支付处理, 物流处理]
        // 主轴节点全部落在「主流程」块里，而「主流程」自己没有子块，
        // childBlocks 为空 ⇒ 虚拟边一次都没生成（A/B 探针两版坐标逐字节
        // 相同）。真正被隔开的是**兄弟块**：`支付处理 →(支付宝/微信/银行卡)
        // → 订单确认` 的中转在「支付处理」块里，主流程块内因此只剩
        // `用户下单→库存检查→支付处理` 与 `订单确认→物流处理` 两条断链。
        //
        // 所以按「group 归属」算中转容器，不依赖块树形状：一个不在本块的
        // 节点，它的归属容器就是 group 路径（GroupPath.key），主轴节点自己
        // 没有 group（ownIds 覆盖）不会误判。
        Map<String, Set<String>> members = new LinkedHashMap<>();
        if (childBlocks != null) {
            for (Block child : childBlocks) {
                Map<String, Block> owner = new LinkedHashMap<>();
                collectMemberIds(child, owner);
                members.put(child.name == null ? "" : child.name,
                        new LinkedHashSet<>(owner.keySet()));
            }
        }
        for (Node node : partition.nodes) {
            if (ownIds.contains(node.id)) {
                continue;
            }
            String key = GroupPath.key(GroupPath.parse(node.json));
            if (key.isEmpty()) {
                continue;
            }
            members.computeIfAbsent(key, k -> new LinkedHashSet<>())
                    .add(node.id);
        }
        if (members.isEmpty()) {
            return out;
        }
        // 本块内已有的直连（避免重复补边、也避免盖掉真实拓扑）
        Set<String> direct = new LinkedHashSet<>();
        // 子块内「谁从本块进来」「谁往本块出去」
        Map<String, Set<String>> enters = new LinkedHashMap<>();
        Map<String, Set<String>> exits = new LinkedHashMap<>();
        for (JsonNode element : partition.rawElements) {
            if (!"arrow".equals(text(element, "type"))) {
                continue;
            }
            String from = bindingId(element, "start");
            String to = bindingId(element, "end");
            if (from == null || to == null) {
                continue;
            }
            boolean fromOwn = ownIds.contains(from);
            boolean toOwn = ownIds.contains(to);
            if (fromOwn && toOwn) {
                direct.add(from + "\u0000" + to);
                continue;
            }
            for (Map.Entry<String, Set<String>> e : members.entrySet()) {
                Set<String> mem = e.getValue();
                if (!fromOwn && toOwn && mem.contains(from)) {
                    exits.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>())
                            .add(to);
                }
                if (fromOwn && !toOwn && mem.contains(to)) {
                    enters.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>())
                            .add(from);
                }
            }
        }
        for (Map.Entry<String, Set<String>> e : enters.entrySet()) {
            Set<String> outs = exits.get(e.getKey());
            if (outs == null || outs.isEmpty()) {
                continue;
            }
            for (String u : e.getValue()) {
                for (String w : outs) {
                    if (u.equals(w) || direct.contains(u + "\u0000" + w)) {
                        continue;
                    }
                    direct.add(u + "\u0000" + w);
                    ObjectNode virt = objectMapper.createObjectNode();
                    virt.put("id", "__spine_" + u + "_" + w);
                    virt.put("type", "arrow");
                    virt.putObject("start").put("id", u);
                    virt.putObject("end").put("id", w);
                    out.add(virt);
                }
            }
        }
        return out;
    }

    private Block layoutOwnShapes(
            List<Node> shapes, LayoutEngine engine, Partition partition,
            boolean horizontal, List<Block> childBlocks) {
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
        // 主轴续接虚拟边：只喂「两端都在本块」的箭头会让主轴断成几段。
        // 典型形态是 `A → 子块成员… → B`（A、B 在本块，中转成员在子块）：
        // 此时引擎只看到 `…→A` 和 `B→…` 两条互不相连的链，外加孤立节点，
        // 于是把它们并排摆成两列，端上看到「主轴断成两截」。
        // 补一条 A→B 的**虚拟箭头**让引擎按一条链排层，排完即丢弃
        // （不进 arrowOriginals、不写回原件）。
        List<JsonNode> spine = spineEnabled
                ? virtualSpineArrows(shapes, ids, partition, childBlocks)
                : List.of();
        sub.addAll(spine);
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

        // 引擎路由修复（第一道）：ELK 的 EdgeRouting.ORTHOGONAL 只看 bendpoints 增减，
        // 不知道节点框在哪 —— 2026-10-18 dataflow 实测 arrow_3
        // (data_processing → data_service) 被排成 5 折小台阶，横段
        // y=33.6 横穿 data_application(440,20,160,60) 腰部。
        // 修正：箭头几何回写后先做「净穿判定」，穿框的交给
        // OrthoAStarRouter.route 重排（它三档策略 + hitsNode 作废就是
        // 为这件事设计的），重排成功才覆盖 —— 宁多折不穿框，但路由
        // 失败时保留 ELK 原线（有连接比好看重要）。
        // 注意：此处的 Node.x/y 还是**种子坐标**（引擎回写在下面）——
        // 判定往往落到 false，真正兜底靠归一化之后那道。
        Map<String, Node> byNodeId = new LinkedHashMap<>();
        for (Node node : shapes) {
            byNodeId.put(node.id, node);
        }
        for (Map.Entry<String, ObjectNode> entry
                : arrowOriginals.entrySet()) {
            ObjectNode original = entry.getValue();
            Node from = byNodeId.get(bindingId(original, "start"));
            Node to = byNodeId.get(bindingId(original, "end"));
            if (from == null || to == null || from == to) {
                continue;
            }
            if (!crossesNode(original, shapes)) {
                continue;
            }
            List<double[]> fixed = OrthoAStarRouter.route(
                    from, to, 0, 1, 0, 1, shapes,
                    new OrthoAStarRouter.Grid(shapes));
            if (fixed == null || fixed.size() < 2) {
                continue;
            }
            ObjectNode routed = objectMapper.createObjectNode();
            writeRoute(routed, dedupe(fixed.toArray(new double[0][])),
                    from, to);
            if (routed.has("points")) {
                original.set("points", routed.get("points").deepCopy());
            }
            if (isNumber(routed, "x")) {
                original.put("x", number(routed, "x"));
            }
            if (isNumber(routed, "y")) {
                original.put("y", number(routed, "y"));
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
        // 🔴 引擎路由修复（第二道）：这里把 node.x/y 从引擎坐标系归零
        // （减 minX/minY），而「引擎路由修复」段判定的 crossesNode 用的是
        // 归一化前的 Node 内存副本 —— 归一化整条边相对框平移一个常量，
        // 「净穿」结论不变；但若 normalize 之前 Node 已被上面的循环改写，
        // 修复段必须放在归一化**之后**重跑一次才对齐真实输出坐标。
        // 归一化：把 subList 平移回"左上角为 (0,0)"。**箭头必须一起
        // 平移** —— 历史缺陷：这里只挪了节点的 x/y，箭头 x/y 留着引擎
        // 坐标系。placeEdgeLabels 拿箭头 x/y 当绝对原点算标签，而且首点
        // 绝对坐标 = x + points[0]，引擎坐标系平移动画后箭头整体偏移
        // (minX, minY)：实测 Mermaid 库存检查图 minY=-10，四条出边首点
        // 齐刷刷浮在菱形上方 10px（用户 m06890 的「箭头起点悬空」）。
        double maxX = 0;
        double maxY = 0;
        for (Node node : shapes) {
            node.x -= minX;
            node.y -= minY;
            maxX = Math.max(maxX, node.x + node.w);
            maxY = Math.max(maxY, node.y + node.h);
        }
        for (ObjectNode arrow : arrowOriginals.values()) {
            if (isNumber(arrow, "x")) {
                arrow.put("x", round1(
                        arrow.get("x").asDouble() - minX));
            }
            if (isNumber(arrow, "y")) {
                arrow.put("y", round1(
                        arrow.get("y").asDouble() - minY));
            }
        }
        // 🔴 引擎路由修复（收尾）：归一化后 Nodes 与 arrows 已对齐到同一
        // 局部坐标系，上面「引擎路由修复」段用的 Node 内存副本在归一化
        // 循环里被改写（node.x -= minX），crossesNode 用的箭头 points
        // 也已被归一化平移 —— 在这里对仍穿框的边再跑一次 route 兜底，
        // 保证写到 json 的原件几何是干净的。
        for (Map.Entry<String, ObjectNode> entry
                : arrowOriginals.entrySet()) {
            ObjectNode original = entry.getValue();
            Node from = byNodeId.get(bindingId(original, "start"));
            Node to = byNodeId.get(bindingId(original, "end"));
            if (from == null || to == null || from == to) {
                continue;
            }
            if (!crossesNode(original, shapes)) {
                continue;
            }
            List<double[]> fixed = OrthoAStarRouter.route(
                    from, to, 0, 1, 0, 1, shapes,
                    new OrthoAStarRouter.Grid(shapes));
            if (fixed == null || fixed.size() < 2) {
                continue;
            }
            ObjectNode routed = objectMapper.createObjectNode();
            writeRoute(routed, dedupe(fixed.toArray(new double[0][])),
                    from, to);
            if (routed.has("points")) {
                original.set("points", routed.get("points").deepCopy());
            }
            if (isNumber(routed, "x")) {
                original.put("x", number(routed, "x"));
            }
            if (isNumber(routed, "y")) {
                original.put("y", number(routed, "y"));
            }
        }
        Block box = new Block(null);
        box.contentW = maxX;
        box.contentH = maxY;
        box.ownArrows.addAll(arrowOriginals.values());
        return box;
    }

    /**
     * 箭头折线是否净穿任何节点框（8px 外扩），端点贴框/邻近框边豁免。
     *
     * <p>判定口径与 {@link OrthoAStarRouter#hitsNode} 完全一致：引擎
     * layoutModule 写回的箭头几何是子场景相对坐标（归一化前），而这里
     * 判定的 Node 也是同一坐标系的内存副本，直接用相对 points 重算
     * 绝对段即可，不需要管 minX/minY 平移。
     *
     * @see OrthoAStarRouter#hitsNode(List, List)
     */
    private boolean crossesNode(ObjectNode arrow, List<Node> nodes) {
        JsonNode pts = arrow.get("points");
        if (pts == null || !pts.isArray() || pts.isEmpty()) {
            return false;
        }
        double x = isNumber(arrow, "x") ? number(arrow, "x") : 0;
        double y = isNumber(arrow, "y") ? number(arrow, "y") : 0;
        List<double[]> abs = new ArrayList<>();
        abs.add(new double[] {x, y});
        for (JsonNode p : pts) {
            double[] last = abs.get(abs.size() - 1);
            abs.add(new double[] {
                last[0] + (isNumber(p, "0") ? number(p, "0")
                        : p.get(0).asDouble()),
                last[1] + (isNumber(p, "1") ? number(p, "1")
                        : p.get(1).asDouble()),
            });
        }
        String from = bindingId(arrow, "start");
        String to = bindingId(arrow, "end");
        for (int i = 0; i + 1 < abs.size(); i++) {
            double[] a = abs.get(i);
            double[] e = abs.get(i + 1);
            for (Node n : nodes) {
                if (n.id.equals(from) || n.id.equals(to)) {
                    continue;
                }
                double px = n.x - 8;
                double py = n.y - 8;
                double qx = n.x + n.w + 8;
                double qy = n.y + n.h + 8;
                boolean horizontal = Math.abs(a[1] - e[1]) < 0.5;
                if (horizontal && a[1] >= py && a[1] <= qy
                        && Math.max(a[0], e[0]) >= px
                        && Math.min(a[0], e[0]) <= qx) {
                    // 端点贴框豁免：起终点落在框边（含外扩）上不算净穿
                    boolean onBox = (e[0] >= px - 0.5 && e[0] <= qx + 0.5
                            && e[1] >= py - 0.5 && e[1] <= qy + 0.5)
                            || (a[0] >= px - 0.5 && a[0] <= qx + 0.5
                            && a[1] >= py - 0.5 && a[1] <= qy + 0.5);
                    if (!onBox) {
                        return true;
                    }
                }
                if (!horizontal && a[0] >= px && a[0] <= qx
                        && Math.max(a[1], e[1]) >= py
                        && Math.min(a[1], e[1]) <= qy) {
                    boolean onBox = (e[0] >= px - 0.5 && e[0] <= qx + 0.5
                            && e[1] >= py - 0.5 && e[1] <= qy + 0.5)
                            || (a[0] >= px - 0.5 && a[0] <= qx + 0.5
                            && a[1] >= py - 0.5 && a[1] <= qy + 0.5);
                    if (!onBox) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        return pack(items, gap, partition, proximityEnabled);
    }

    /**
     * 打包单个块的内部元素。把「就近摆」开关抽成参数，便于回归测试在关闭状
     * 态下复跑同一份输入做 A/B（见 {@link ProximityOrderTest}）。
     */
    double[] pack(
            List<Block> items, double gap, Partition partition,
            boolean proximity) {
        if (items.size() == 1) {
            items.get(0).ox = 0;
            items.get(0).oy = 0;
            return new double[] {
                items.get(0).outerW(), items.get(0).outerH(),
            };
        }
        int bestCols = 0;
        double bestPenalty = Double.MAX_VALUE;
        // 就近摆的目标函数只评「正在被打包的这一层模块」：跨模块边可能发生在
        // 任意嵌套深度（模型常把整图兜进一个外层 group），必须在打包的这一层
        // 现算 flow，而不是依赖根层 partition.flow（根层往往只有 1 个子块 → 空）。
        Map<String, Map<String, Integer>> flow = flowBetween(items, partition);
        boolean topLevel = !flow.isEmpty();
        if (topLevel && proximity) {
            orderModulesByProximity(items, gap, partition, flow);
        }
        for (int cols = 1; cols <= items.size(); cols++) {
            double penalty = packScore(items, cols, gap, flow);
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

    /**
     * 在被打包的 {@code items} 这一层现算「谁连谁」：每个图形归属到它所在的
     * item 块（递归收成员），跨 item 的箭头计数。名字为 null 的占位块
     * （{@code block.own}）不计入。
     */
    private Map<String, Map<String, Integer>> flowBetween(
            List<Block> items, Partition partition) {
        Map<String, Block> owner = new LinkedHashMap<>();
        for (Block it : items) {
            collectMemberIds(it, owner);
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
            if (bf.name == null || bt.name == null) {
                continue;
            }
            flow.computeIfAbsent(bf.name, k -> new LinkedHashMap<>())
                    .merge(bt.name, 1, Integer::sum);
        }
        return flow;
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

    /** 读文本字段；非文本/缺失返回 null */
    static String text(JsonNode node, String field) {
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

        /**
         * 图形类型（契约里的 type 字段，如 rectangle / ellipse / diamond）。
         *
         * <p>端点吸附要用它区分「矩形按 bbox 就够」与「菱形/椭圆要收进
         * 斜边」（见 {@link #snapEndpointsToShapes}）。
         */
        String type() {
            return text(json, "type");
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
