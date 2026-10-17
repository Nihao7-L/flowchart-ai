package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.AgentTool;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.graph.layout.GroupPath;
import io.github.nihaoljx.flowchart.graph.layout.LayoutFamilyRegistry;
import io.github.nihaoljx.flowchart.graph.layout.LayoutSpec;
import io.github.nihaoljx.flowchart.graph.layout.SpecEdge;
import io.github.nihaoljx.flowchart.graph.layout.SpecNode;
import io.github.nihaoljx.flowchart.graph.validate.RelationCatalog;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;
import io.github.nihaoljx.flowchart.prompt.DiagramPromptRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * submit_spec：语义提交 —— 只描述"画什么"，坐标交给布局引擎
 *
 * <p>动机（2026-10-15 token 级实测）：让 LLM 直出 x/y/width/height
 * 等于让它当几何排版引擎 —— 模型会在推理里穷举布局、逐条手算连线
 * 是否交叉（实测 glm-5.3-flash 硬架构图 12 分钟写出 11.9 万字推理、
 * 零工具调用、零产出）。本工具把坐标从模型手里整个拿走：spec 里
 * 没有任何几何字段，模型想算都没得算。
 *
     * <p>唯一生成路径：所有结构化图表（流程/架构/时序/组织/思维导图/
     * 自由画摆位等「节点 + 连线」形态）统一用本工具。submit_scene（让
     * LLM 直出 x/y）已在 v10.12.0 冻结，不再注册。
 *
 * <p>多图型家族（2026-10-16）：spec 可声明 {@code diagramType}
 * （sequence = 时序图：参与者横排 + 生命线 + 消息按时间序逐行）。
 * 不声明 / 未注册 ⇒ generic 家族（ELK 通用图），运行时零风险。
 * 布局家族见 {@code io.github.nihaoljx.flowchart.graph.layout}。
 *
 * <p>流程：spec 校验（机读修复单：field+reason+hint）→ 生成带占位
 * 坐标的场景 JSON → 按 diagramType 交布局家族排版 → 复用
 * {@link SceneGate} 全管线校验。绑端点箭头不带坐标（前端按绑定
 * 关系推导几何，见 sceneAdapter.withArrowGeometry）。
 */
public final class SubmitSpecTool implements AgentTool {

    private static final Logger LOG =
            LoggerFactory.getLogger(SubmitSpecTool.class);

    public static final String NAME = "submit_spec";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 元素 id 的合法形态（与 GraphValidator 的 ID_PATTERN 同口径） */
    private static final Pattern ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+$");

    /** spec 节点数上限：防跑飞的模型刷爆布局引擎 */
    private static final int MAX_NODES = 200;

    /** 允许的节点形状（与布局/校验两边的 SHAPE_TYPES 同口径） */
    private static final List<String> SHAPE_TYPES =
            List.of("rectangle", "ellipse", "diamond");

    /**
     * 分区配色盘（Excalidraw 官方浅色系）。spec 不让模型挑颜色 ——
     * 配色是确定性代码按分组序号给的（2026-10-15 渲染验收：无样式的
     * 黑白框被评「和屎没有区别」，而旧坐标路径的观感优势恰恰是模型
     * 直画时自带的配色；把配色从模型手里拿走，与坐标同理）。
     */
    private static final Map<String, String[]> COLORS;

    static {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("blue", new String[] {"#a5d8ff", "#1971c2"});
        m.put("green", new String[] {"#b2f2bb", "#2f9e44"});
        m.put("yellow", new String[] {"#ffec99", "#f08c00"});
        m.put("red", new String[] {"#ffc9c9", "#e03131"});
        m.put("purple", new String[] {"#d0bfff", "#7048e8"});
        m.put("orange", new String[] {"#ffd8a8", "#e8590c"});
        m.put("cyan", new String[] {"#99e9f2", "#0c8599"});
        m.put("pink", new String[] {"#eebefa", "#ae3ec9"});
        m.put("gray", new String[] {"#e9ecef", "#868e96"});
        COLORS = Collections.unmodifiableMap(m);
    }

    /** 具名配色的合法值（与 {@link #COLORS} 同源，顺序固定） */
    private static final List<String> COLOR_NAMES =
            List.copyOf(COLORS.keySet());

    /** 自动分区配色的轮转顺序（分组没点名颜色时按出现序取） */
    private static final List<String> GROUP_CYCLE = List.of(
            "blue", "green", "yellow", "red",
            "purple", "orange", "cyan", "pink");

    /** 具名配色：模型可以点名颜色，但填不了色值（见 COLORS 注释） */
    private static final String COLOR_PROMPT =
            "节点配色（可选）。用户点名了颜色就用这里表达："
                    + "blue/green/yellow/red/purple/orange/cyan/pink/gray，"
                    + "不要为了颜色改用手写坐标（坐标一律交布局引擎）";

    /** 整图标题与全图包围盒上沿的距离（px） */
    private static final double TITLE_GAP = 80.0;

    /** 整图标题字号（比节点字号大一档） */
    private static final int TITLE_FONT_SIZE = 28;

    private final SceneGate gate;
    private final LayoutFamilyRegistry families;
    private final DiagramPromptRegistry prompts;

    /** 旧签名（测试 / 手工装配）：图型表只取 classpath 内置模板。 */
    public SubmitSpecTool(SceneGate gate,
            LayoutFamilyRegistry families) {
        this(gate, families, new DiagramPromptRegistry());
    }

    public SubmitSpecTool(SceneGate gate,
            LayoutFamilyRegistry families,
            DiagramPromptRegistry prompts) {
        this.gate = gate;
        this.families = families;
        this.prompts = prompts;
    }

    /**
     * spec 的 {@code diagramType} 合法值：布局家族 + 已注册图型模板，
     * 外加 generic 兜底。
     *
     * <p>为什么不是只给布局家族：图型是"这是什么图"（25 种），版面是
     * "用哪套排版"（generic / sequence 两套）。只认后者的话，模型照
     * 图型模板声明了 gantt 却被判「未知图型」打回修正轮 —— 加图型的成本
     * 就变成了"每加一种都要写一套排版器"。
     */
    private List<String> knownDiagramTypes() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        all.add("generic");
        all.addAll(families.types());
        all.addAll(prompts.types());
        return List.copyOf(all);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "以语义方式提交图表（唯一生成路径，推荐）。"
                + "只描述节点(nodes)与连线(edges)，不用给任何坐标 —— "
                + "坐标由后端布局引擎自动计算。节点可标 group 模块名"
                + "（自动分区、配色、画框），连线可标 relation 关系语义"
                + "（自动定线型与箭头）。适用：流程图/架构图/组织架构/"
                + "思维导图/状态图/自由画摆位等所有「节点 + 连线」形态。"
                + "时序图（参与者生命线 + 按时间顺序的消息）必须声明 "
                + "diagramType=sequence，并把 edges 按时间先后排序。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "object");
        Map<String, Object> nodeProps = new LinkedHashMap<>();
        nodeProps.put("id", Map.of("type", "string"));
        nodeProps.put("label", Map.of("type", "string"));
        nodeProps.put("type", Map.of("type", "string",
                "enum", SHAPE_TYPES));
        nodeProps.put("color", Map.of("type", "string",
                "enum", COLOR_NAMES, "description", COLOR_PROMPT));
        nodeProps.put("group", Map.of(
                "oneOf", List.of(
                        Map.of("type", "string", "minLength", 1),
                        Map.of("type", "array",
                                "items", Map.of("type", "string",
                                        "minLength", 1))),
                "description", "模块/分区名。用户说「分两层 / 分模块 /"
                        + "分区域 / 上下两部分 / 一条流水线」时**必须**"
                        + "给对应节点写上这个名字 —— 不写就不会画分区框、"
                        + "不会配色，用户点的分区就整块丢了。同一 group 的"
                        + "节点会自动分区、配色并框进同名画框。"
                        + "**模块里还有子模块时写成数组，从内到外**："
                        + "组合态「处理中」的子状态写"
                        + " [\\\"处理中\\\",\\\"订单\\\"]，"
                        + "而「处理中」这个节点本身也写同一条完整路径 ——"
                        + "容器框才会把组合态和它的子状态套进同一个框；"
                        + "只在子状态上写扁平字符串 \"处理中\"（组合态自己写"
                        + " \"订单\"）会把内外层拆成两个并排模块，"
                        + "中间连出贯穿整张画布的长线"));
        node.put("properties", nodeProps);
        node.put("required", List.of("id", "label"));
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("type", "object");
        Map<String, Object> edgeProps = new LinkedHashMap<>();
        edgeProps.put("from", Map.of("type", "string"));
        edgeProps.put("to", Map.of("type", "string"));
        edgeProps.put("label", Map.of("type", "string"));
        edgeProps.put("relation", Map.of("type", "string",
                "enum", RelationCatalog.ALL_RELATIONS.stream()
                        .sorted().toList(),
                "description", "关系语义（可选）：flow 调用流/"
                        + "data 数据流/async 异步/dependency 依赖/"
                        + "inherit 继承/one-to-many 等，决定线型与箭头"));
        edge.put("properties", edgeProps);
        edge.put("required", List.of("from", "to"));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "object");
        Map<String, Object> specProps = new LinkedHashMap<>();
        specProps.put("title", Map.of("type", "string",
                "description", "整图标题（可选）。给了就画在全图正上方"
                        + "一行大字 —— 不要再自己画一个标题节点"));
        specProps.put("diagramType", Map.of("type", "string",
                "enum", knownDiagramTypes(),
                "description", "图型（可选，默认 generic）。"
                        + "system 里若给了「图型模板」段，就照那段里写的"
                        + " diagramType 字面值填；没给就填 generic。"
                        + "填了图型才会按那种图的版面排"));
        specProps.put("nodes", Map.of("type", "array", "items", node));
        specProps.put("edges", Map.of("type", "array", "items", edge));
        spec.put("properties", specProps);
        spec.put("required", List.of("nodes", "edges"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spec", spec);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("spec"));
        return schema;
    }

    @Override
    public Observation execute(JsonNode args, ToolContext ctx) {
        JsonNode spec = args == null ? null : args.get("spec");
        if (spec == null || !spec.isObject()) {
            return fail(List.of(issue("spec",
                    "缺少 spec 参数或它不是 JSON 对象",
                    "把 {nodes:[...], edges:[...]} 放进 spec 参数")));
        }
        List<ValidationIssue> issues = new ArrayList<>();
        JsonNode nodes = spec.path("nodes");
        if (!nodes.isArray() || nodes.isEmpty()) {
            issues.add(issue("spec.nodes",
                    "nodes 缺失或为空：一个节点都没有",
                    "至少给出一个节点（id + label）"));
            return fail(issues);
        }
        if (nodes.size() > MAX_NODES) {
            issues.add(issue("spec.nodes",
                    "节点数 " + nodes.size() + " 超过上限 " + MAX_NODES,
                    "拆成多张图，或精简节点"));
            return fail(issues);
        }
        Map<String, SpecNode> nodeSpecs = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            collectNode(nodes.get(i), i, nodeSpecs, issues);
        }
        JsonNode edges = spec.path("edges");
        List<SpecEdge> edgeSpecs = new ArrayList<>();
        if (edges.isArray()) {
            for (int i = 0; i < edges.size(); i++) {
                collectEdge(edges.get(i), i,
                        nodeSpecs.keySet(), edgeSpecs, issues);
            }
        }
        // 图型声明：既不是布局家族、也不是已注册图型 ⇒ 机读修复单
        //（模型写错图型名时它改得了；与注册表运行时的 generic 兜底是两层）
        String diagramType = spec.path("diagramType").asText("generic");
        if (!families.has(diagramType) && !prompts.isKnown(diagramType)) {
            issues.add(issue("spec.diagramType",
                    "未知图型: " + diagramType,
                    "允许值: " + knownDiagramTypes()
                            + "（不填 = generic 通用图）"));
        }
        if (!issues.isEmpty()) {
            return fail(issues);
        }
        String title = spec.path("title").asText(null);
        String scene = buildScene(nodeSpecs, edgeSpecs);
        // 按图型路由到布局家族（多图型架构，2026-10-16）：
        // generic = 分区排版 + 布局引擎 + 分组合框；
        // sequence = 参与者横排 + 生命线 + 消息按时间序逐行。
        // 图型 → 版面：25 种图型共用两套版面，模板里的 layout 决定用哪套。
        // 家族抛异常 ⇒ 降级 generic，绝不让排版失败炸掉整轮。
        String layout = families.has(diagramType)
                ? diagramType
                : prompts.layoutOf(diagramType);
        String arranged;
        try {
            arranged = families.forType(layout)
                    .arrange(scene, nodeSpecs, edgeSpecs);
        } catch (Exception e) {
            LOG.warn("布局家族 {} 排版失败（{}），降级 generic",
                    layout, e.getMessage());
            arranged = families.forType("generic")
                    .arrange(scene, nodeSpecs, edgeSpecs);
        }
        SceneGate.Outcome out = gate.process(arranged, ctx);
        if (!out.parseable()) {
            return Observation.fail(
                    "场景读不通，未受理",
                    payload(out.issues()), out.issues());
        }
        // 标题在闸门之后追加：它是本工具自己合成的确定性元素，
        // 不该反过来变成一条要模型修的校验问题
        String finalJson = addTitle(out.json(), title);
        ctx.processedScene(finalJson);
        String layoutNote = "sequence".equals(layout)
                ? "时序图已排版 " + nodeSpecs.size()
                        + " 个参与者、" + edgeSpecs.size() + " 条消息"
                : "布局引擎已排版 " + nodeSpecs.size()
                        + " 个节点、" + edgeSpecs.size() + " 条连线";
        if (!out.issues().isEmpty()) {
            // 几何 issue（交叉/穿身/标签被压/端点内插）是布局引擎的
            // 产物，模型在 spec 路径下手里没有任何坐标，修不了 ——
            // 打回去只会换来盲改拓扑重抽（2026-10-15 regress_arch2
            // 实测：62s 生成 → 体检打回 → 27s 修正 → 仍未过 → 下一轮
            // 挂死 300s，全程 6 分钟零产出）。几何留给布局层自愈，
            // 模型只修它真正能修的（结构 / 引用 / 空场景）。
            List<ValidationIssue> actionable = out.issues().stream()
                    .filter(i -> !i.field().startsWith("geometry."))
                    .toList();
            if (!actionable.isEmpty()) {
                List<ValidationIssue> named = new ArrayList<>(actionable);
                long hidden = out.issues().size() - actionable.size();
                if (hidden > 0) {
                    named.add(new ValidationIssue("geometry",
                            "另有 " + hidden
                                    + " 条几何观感问题（由布局层负责，"
                                    + "无需你处理）", "忽略即可"));
                }
                return Observation.fail(
                        "场景受理但校验未通过（"
                                + actionable.size() + " 个问题）",
                        payload(named), named);
            }
            ctx.validScene(finalJson);
            return Observation.ok(
                    "语义提交受理：" + layoutNote
                            + "，生成完成（存在 "
                            + out.issues().size()
                            + " 条几何观感问题，已交布局层自愈）",
                    payload(List.of()));
        }
        ctx.validScene(finalJson);
        return Observation.ok(
                "语义提交受理：" + layoutNote + "，并通过校验，生成完成",
                payload(List.of()));
    }

    // ==================== spec 校验（机读修复单） ====================

    private void collectNode(JsonNode node, int index,
            Map<String, SpecNode> nodeSpecs,
            List<ValidationIssue> issues) {
        String path = "spec.nodes[" + index + "]";
        if (!node.isObject()) {
            issues.add(issue(path, "节点必须是对象",
                    "形如 {\"id\":\"n1\",\"label\":\"开始\"}"));
            return;
        }
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            issues.add(issue(path + ".id", "节点缺少 id",
                    "给每个节点一个短 id（如 n1、n2），连线用 from/to 引用它"));
            return;
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            issues.add(issue(path + ".id", "id 格式非法: " + id,
                    "只允许字母数字下划线连字符（如 n1、user-svc）"));
            return;
        }
        if (nodeSpecs.containsKey(id)) {
            issues.add(issue(path + ".id", "节点 id 重复: " + id,
                    "每个节点的 id 必须唯一"));
            return;
        }
        String label = node.path("label").asText("");
        String type = node.path("type").asText("rectangle");
        if (!SHAPE_TYPES.contains(type)) {
            issues.add(issue(path + ".type", "非法节点 type: " + type,
                    "允许值: " + SHAPE_TYPES));
            return;
        }
        String color = node.path("color").asText(null);
        if (color != null) {
            color = color.trim();
            if (!COLORS.containsKey(color)) {
                issues.add(issue(path + ".color",
                        "非法颜色名: " + color,
                        "允许值: " + COLOR_NAMES));
                return;
            }
        }
        nodeSpecs.put(id, new SpecNode(
                id, label, type,
                GroupPath.parse(node), color));
    }

    private void collectEdge(JsonNode edge, int index,
            Set<String> ids, List<SpecEdge> edgeSpecs,
            List<ValidationIssue> issues) {
        String path = "spec.edges[" + index + "]";
        if (!edge.isObject()) {
            issues.add(issue(path, "连线必须是对象",
                    "形如 {\"from\":\"n1\",\"to\":\"n2\",\"label\":\"点击\"}"));
            return;
        }
        String from = edge.path("from").asText(null);
        String to = edge.path("to").asText(null);
        if (from == null || to == null) {
            issues.add(issue(path, "连线缺少 from 或 to",
                    "from/to 填节点的 id"));
            return;
        }
        if (!ids.contains(from)) {
            issues.add(issue(path + ".from",
                    "from 引用了不存在的节点 id: " + from,
                    "节点 id 清单: " + String.join(", ", ids)));
            return;
        }
        if (!ids.contains(to)) {
            issues.add(issue(path + ".to",
                    "to 引用了不存在的节点 id: " + to,
                    "节点 id 清单: " + String.join(", ", ids)));
            return;
        }
        if (from.equals(to)) {
            issues.add(issue(path, "连线自环（from 与 to 相同）",
                    "起止必须是不同节点"));
            return;
        }
        edgeSpecs.add(new SpecEdge(from, to,
                edge.path("label").asText(null),
                edge.path("relation").asText(null)));
    }

    // ==================== spec → 场景 JSON ====================

    /**
     * 生成带占位坐标的场景：节点统一按 LayoutSpec 算"渲染端最终
     * 会画多大"的尺寸（布局层间距按它算），位置先放原点、由
     * {@link LayoutEngine#layout} 全量重排；箭头只带绑定不带几何
     * （前端按绑定推导，见 sceneAdapter.withArrowGeometry）。
     * label 一律写成 {"text":...} 对象形态（GraphValidator 口径）。
     */
    private String buildScene(Map<String, SpecNode> nodeSpecs,
            List<SpecEdge> edgeSpecs) {
        ArrayNode elements = MAPPER.createArrayNode();
        List<String> labels = nodeSpecs.values().stream()
                .map(SpecNode::label).toList();
        double width = LayoutSpec.nodeWidth(labels);
        // 分组配色：按分组首次出现的顺序取色盘（确定性，可单测）
        Map<String, String> fillByGroup = new LinkedHashMap<>();
        Map<String, String> strokeByGroup = new LinkedHashMap<>();
        for (SpecNode node : nodeSpecs.values()) {
            String group = GroupPath.key(node.group());
            if (!group.isEmpty() && !fillByGroup.containsKey(group)) {
                String[] palette = COLORS.get(GROUP_CYCLE.get(
                        fillByGroup.size() % GROUP_CYCLE.size()));
                fillByGroup.put(group, palette[0]);
                strokeByGroup.put(group, palette[1]);
            }
        }
        for (SpecNode node : nodeSpecs.values()) {
            ObjectNode shape = elements.addObject();
            shape.put("id", node.id());
            shape.put("type", node.type());
            double[] size = LayoutSpec.nodeSize(
                    node.type(), node.label(), width);
            shape.put("x", 0);
            shape.put("y", 0);
            shape.put("width", size[0]);
            shape.put("height", size[1]);
            shape.putObject("label").put("text", node.label());
            String group = GroupPath.key(node.group());
            if (!group.isEmpty()) {
                GroupPath.write(shape, node.group());
                shape.put("backgroundColor", fillByGroup.get(group));
                shape.put("strokeColor", strokeByGroup.get(group));
                shape.put("fillStyle", "solid");
            }
            // 具名色盖过分区自动色：模型点名的颜色是明确的视觉意图
            String[] named = node.color() == null
                    ? null : COLORS.get(node.color());
            if (named != null) {
                shape.put("backgroundColor", named[0]);
                shape.put("strokeColor", named[1]);
                shape.put("fillStyle", "solid");
            }
        }
        Set<String> usedIds = new HashSet<>(nodeSpecs.keySet());
        int index = 0;
        for (SpecEdge edge : edgeSpecs) {
            String arrowId = uniqueArrowId(usedIds, index);
            usedIds.add(arrowId);
            ObjectNode arrow = elements.addObject();
            arrow.put("id", arrowId);
            arrow.put("type", "arrow");
            arrow.putObject("start").put("id", edge.from());
            arrow.putObject("end").put("id", edge.to());
            if (edge.label() != null && !edge.label().isBlank()) {
                arrow.putObject("label").put("text", edge.label());
            }
            if (edge.relation() != null
                    && !edge.relation().isBlank()) {
                arrow.put("relation", edge.relation());
            }
            index++;
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("elements", elements);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "spec 场景序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加整图标题：一个 text 元素，落在全图包围盒上沿之上。
     *
     * <p>为什么由代码合成而不是让模型给：标题的落点也是坐标，而
     * spec 里没有任何几何字段 —— 模型只交文字，落在哪由这里按
     * 布局后的真实包围盒算（参考图 Transformer / RAG 顶部都有一行
     * 标题，缺了它整图少一个视觉锚点）。
     */
    private String addTitle(String json, String title) {
        if (title == null || title.isBlank()) {
            return json;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode elementsNode = root.get("elements");
            if (elementsNode == null || !elementsNode.isArray()) {
                return json;
            }
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            Set<String> usedIds = new HashSet<>();
            for (JsonNode el : elementsNode) {
                usedIds.add(el.path("id").asText(""));
                if (el.hasNonNull("x") && el.hasNonNull("y")) {
                    minX = Math.min(minX, el.path("x").asDouble());
                    minY = Math.min(minY, el.path("y").asDouble());
                }
            }
            if (minX == Double.MAX_VALUE || minY == Double.MAX_VALUE) {
                return json;
            }
            String id = "title";
            while (usedIds.contains(id)) {
                id = id + "_x";
            }
            ObjectNode text = MAPPER.createObjectNode();
            text.put("id", id);
            text.put("type", "text");
            text.put("x", snap(minX));
            text.put("y", snap(minY - TITLE_GAP));
            text.put("text", title.trim());
            text.put("fontSize", TITLE_FONT_SIZE);
            ((ArrayNode) elementsNode).add(text);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // 标题只是观感件，失败不拦生成
            return json;
        }
    }

    private static double snap(double value) {
        return Math.round(value / LayoutSpec.GRID)
                * LayoutSpec.GRID;
    }

    /** 箭头 id 避让节点 id（元素 id 必须全局唯一） */
    private static String uniqueArrowId(
            Set<String> usedIds, int index) {
        String id = "arrow_" + index;
        while (usedIds.contains(id)) {
            id = id + "_x";
        }
        return id;
    }

    private static ValidationIssue issue(
            String field, String reason, String hint) {
        return new ValidationIssue(field, reason, hint);
    }

    private static Observation fail(List<ValidationIssue> issues) {
        return Observation.fail(
                "spec 受理失败（" + issues.size() + " 个问题）",
                payload(issues), issues);
    }

    /** 结构化载荷：issue 三元组（有界，直接可回喂） */
    private static JsonNode payload(List<ValidationIssue> issues) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("issueCount", issues.size());
        var arr = node.putArray("issues");
        for (ValidationIssue item : issues) {
            ObjectNode entry = arr.addObject();
            entry.put("field", item.field());
            entry.put("reason", item.reason());
            entry.put("hint", item.hint());
        }
        return node;
    }
}
