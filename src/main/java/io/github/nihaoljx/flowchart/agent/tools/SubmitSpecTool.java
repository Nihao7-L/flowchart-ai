package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.AgentTool;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import io.github.nihaoljx.flowchart.graph.layout.LayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.LayoutSpec;
import io.github.nihaoljx.flowchart.graph.validate.RelationCatalog;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
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
 * <p>双轨并存：结构化图表（流程/架构/时序/组织/思维导图等）用本工具；
 * 自由画（涂鸦/海报/摆位类，位置即语义）仍走 {@link SubmitSceneTool}。
 *
 * <p>流程：spec 校验（机读修复单：field+reason+hint）→ 生成带占位
 * 坐标的场景 JSON → {@link LayoutEngine#layout} 全量排版 → 复用
 * {@link SceneGate} 全管线校验。绑端点箭头不带坐标（前端按绑定
 * 关系推导几何，见 sceneAdapter.withArrowGeometry）。
 */
public final class SubmitSpecTool implements AgentTool {

    public static final String NAME = "submit_spec";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 元素 id 的合法形态（与 GraphValidator 的 ID_PATTERN 同口径） */
    private static final Pattern ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+$");

    /** spec 节点数上限：防跑飞的模型刷爆布局引擎 */
    private static final int MAX_NODES = 200;

    /** 允许的节点形状（与布局/校验两边的 SHAPE_TYPES 同口径） */
    private static final Set<String> SHAPE_TYPES =
            Set.of("rectangle", "ellipse", "diamond");

    /**
     * 分区配色盘（Excalidraw 官方浅色系）。spec 不让模型挑颜色 ——
     * 配色是确定性代码按分组序号给的（2026-10-15 渲染验收：无样式的
     * 黑白框被评「和屎没有区别」，而旧坐标路径的观感优势恰恰是模型
     * 直画时自带的配色；把配色从模型手里拿走，与坐标同理）。
     */
    private static final String[] PALETTE = {
        "#a5d8ff", "#b2f2bb", "#ffec99", "#ffc9c9",
        "#d0bfff", "#ffd8a8", "#99e9f2", "#eebefa",
    };

    /** frame 标题的最大长度（与 ScenePlanner.MAX_GROUP_NAME 同口径） */
    private static final int MAX_GROUP_NAME = 24;

    /** frame 包住成员时的四周留白（LayoutSpec.MODULE_PADDING 同源） */
    private static final double FRAME_PADDING = 40.0;

    private final SceneGate gate;
    private final LayoutEngine layoutEngine;
    private final ScenePlanner planner;

    public SubmitSpecTool(SceneGate gate,
            LayoutEngine layoutEngine, ScenePlanner planner) {
        this.gate = gate;
        this.layoutEngine = layoutEngine;
        this.planner = planner;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "以语义方式提交图表（结构化图表的首选，推荐）。"
                + "只描述节点(nodes)与连线(edges)，不用给任何坐标 —— "
                + "坐标由后端布局引擎自动计算。节点可标 group 模块名"
                + "（自动分区、配色、画框），连线可标 relation 关系语义"
                + "（自动定线型与箭头）。适用：流程图/架构图/组织架构/"
                + "思维导图/状态图等。自由画（涂鸦/海报/摆位类）"
                + "才用 submit_scene。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", "object");
        Map<String, Object> nodeProps = new LinkedHashMap<>();
        nodeProps.put("id", Map.of("type", "string"));
        nodeProps.put("label", Map.of("type", "string"));
        nodeProps.put("type", Map.of("type", "string",
                "enum", List.of(SHAPE_TYPES)));
        nodeProps.put("group", Map.of("type", "string",
                "description", "模块/分区名（可选）。同一 group 的节点"
                        + "会被自动分区、配色并框进同名画框；架构图的"
                        + "分层（接入层/服务层…）务必标注"));
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
        spec.put("properties", new LinkedHashMap<>(Map.of(
                "nodes", Map.of("type", "array", "items", node),
                "edges", Map.of("type", "array", "items", edge))));
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
        Map<String, NodeSpec> nodeSpecs = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            collectNode(nodes.get(i), i, nodeSpecs, issues);
        }
        JsonNode edges = spec.path("edges");
        List<EdgeSpec> edgeSpecs = new ArrayList<>();
        if (edges.isArray()) {
            for (int i = 0; i < edges.size(); i++) {
                collectEdge(edges.get(i), i,
                        nodeSpecs.keySet(), edgeSpecs, issues);
            }
        }
        if (!issues.isEmpty()) {
            return fail(issues);
        }
        String scene = buildScene(nodeSpecs, edgeSpecs);
        // 分区排版（ScenePlanner.arrange）：模块内交给布局引擎、
        // 模块间网格打包、relation 编译成线型/箭头 —— 机制现成，
        // 此前 submit_spec 直接调 layoutEngine 绕过了它（12 图渲染
        // 验收「除 07 全是屎」的根因之一：分区与线型语义全被丢弃）
        String laidOut = planner.arrange(
                scene, layoutEngine, Set.of());
        String framed = addGroupFrames(laidOut, nodeSpecs);
        SceneGate.Outcome out = gate.process(framed, ctx);
        if (!out.parseable()) {
            return Observation.fail(
                    "场景读不通，未受理",
                    payload(out.issues()), out.issues());
        }
        ctx.processedScene(out.json());
        if (!out.issues().isEmpty()) {
            return Observation.fail(
                    "场景受理但校验未通过（"
                            + out.issues().size() + " 个问题）",
                    payload(out.issues()), out.issues());
        }
        ctx.validScene(out.json());
        return Observation.ok(
                "语义提交受理：布局引擎已排版 " + nodeSpecs.size()
                        + " 个节点、" + edgeSpecs.size()
                        + " 条连线，并通过校验，生成完成",
                payload(List.of()));
    }

    // ==================== spec 校验（机读修复单） ====================

    private void collectNode(JsonNode node, int index,
            Map<String, NodeSpec> nodeSpecs,
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
        nodeSpecs.put(id, new NodeSpec(
                id, label, type,
                node.path("group").asText(null)));
    }

    private void collectEdge(JsonNode edge, int index,
            Set<String> ids, List<EdgeSpec> edgeSpecs,
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
        edgeSpecs.add(new EdgeSpec(from, to,
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
    private String buildScene(Map<String, NodeSpec> nodeSpecs,
            List<EdgeSpec> edgeSpecs) {
        ArrayNode elements = MAPPER.createArrayNode();
        List<String> labels = nodeSpecs.values().stream()
                .map(NodeSpec::label).toList();
        double width = LayoutSpec.nodeWidth(labels);
        // 分组配色：按分组首次出现的顺序取色盘（确定性，可单测）
        Map<String, String> colorByGroup = new LinkedHashMap<>();
        for (NodeSpec node : nodeSpecs.values()) {
            String group = normalizedGroup(node.group());
            if (group != null && !colorByGroup.containsKey(group)) {
                colorByGroup.put(group,
                        PALETTE[colorByGroup.size() % PALETTE.length]);
            }
        }
        for (NodeSpec node : nodeSpecs.values()) {
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
            String group = normalizedGroup(node.group());
            if (group != null) {
                shape.put("group", group);
                shape.put("backgroundColor",
                        colorByGroup.get(group));
                shape.put("fillStyle", "solid");
            }
        }
        Set<String> usedIds = new HashSet<>(nodeSpecs.keySet());
        int index = 0;
        for (EdgeSpec edge : edgeSpecs) {
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

    /** 空白分组名归一为 null（无分组） */
    private static String normalizedGroup(String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        String trimmed = group.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 按分组合成画框（frame）：bbox 包住同组成员、name = 组名、
     * children = 成员 id。放在元素数组最前（z 序最底，框压在成员下面）。
     *
     * <p>为什么在这里合成而不是让模型给：模型填 frame 几何从来填不准
     * （旧路径实测），而 bbox 由布局后的真实坐标算出，天然贴身。
     * ≥2 个分组才画 —— 单组没有「分区」可言。ScenePlanner 的
     * claimFramesOnly（gate 管线内）会按名字把框认领给模块并按
     * 其成员口径重填 children，与此处的 id 集合一致。
     */
    private String addGroupFrames(
            String laidOut, Map<String, NodeSpec> nodeSpecs) {
        try {
            JsonNode root = MAPPER.readTree(laidOut);
            JsonNode elementsNode = root.get("elements");
            if (elementsNode == null || !elementsNode.isArray()) {
                return laidOut;
            }
            // 分组成员（首次出现序）与已有 id 集合
            Map<String, List<String>> members = new LinkedHashMap<>();
            Set<String> usedIds = new HashSet<>();
            for (JsonNode el : elementsNode) {
                String id = el.path("id").asText(null);
                if (id != null) {
                    usedIds.add(id);
                }
                String group = normalizedGroup(
                        el.path("group").asText(null));
                if (group != null && id != null) {
                    members.computeIfAbsent(
                            group, k -> new ArrayList<>()).add(id);
                }
            }
            if (members.size() < 2) {
                return laidOut;
            }
            // 每组 bbox
            Map<String, double[]> boxes = new LinkedHashMap<>();
            for (JsonNode el : elementsNode) {
                String group = normalizedGroup(
                        el.path("group").asText(null));
                if (group == null
                        || !members.containsKey(group)
                        || !el.hasNonNull("x")
                        || !el.hasNonNull("y")
                        || !el.hasNonNull("width")
                        || !el.hasNonNull("height")) {
                    continue;
                }
                double x = el.path("x").asDouble();
                double y = el.path("y").asDouble();
                double w = el.path("width").asDouble();
                double h = el.path("height").asDouble();
                double[] box = boxes.get(group);
                if (box == null) {
                    boxes.put(group, new double[] {x, y, x + w, y + h});
                } else {
                    box[0] = Math.min(box[0], x);
                    box[1] = Math.min(box[1], y);
                    box[2] = Math.max(box[2], x + w);
                    box[3] = Math.max(box[3], y + h);
                }
            }
            ArrayNode frames = MAPPER.createArrayNode();
            int index = 0;
            for (Map.Entry<String, List<String>> entry
                    : members.entrySet()) {
                double[] box = boxes.get(entry.getKey());
                if (box == null) {
                    continue;
                }
                String frameId = "frame_" + index;
                while (usedIds.contains(frameId)) {
                    frameId = frameId + "_x";
                }
                usedIds.add(frameId);
                ObjectNode frame = frames.addObject();
                frame.put("id", frameId);
                frame.put("type", "frame");
                frame.put("name", truncate(entry.getKey()));
                frame.put("x", snap(box[0] - FRAME_PADDING));
                frame.put("y", snap(box[1] - FRAME_PADDING));
                frame.put("width",
                        snap(box[2] + FRAME_PADDING)
                                - snap(box[0] - FRAME_PADDING));
                frame.put("height",
                        snap(box[3] + FRAME_PADDING)
                                - snap(box[1] - FRAME_PADDING));
                ArrayNode children = frame.putArray("children");
                entry.getValue().forEach(children::add);
                index++;
            }
            // 拼新数组：frames 在最前（z 序最底）
            ArrayNode merged = MAPPER.createArrayNode();
            merged.addAll(frames);
            elementsNode.forEach(merged::add);
            ((ObjectNode) root).set("elements", merged);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // 画框只是观感件，失败不拦生成
            return laidOut;
        }
    }

    private static String truncate(String name) {
        return name.length() <= MAX_GROUP_NAME
                ? name : name.substring(0, MAX_GROUP_NAME);
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

    /** spec 里的一个节点（id + 文字 + 形状 + 可选模块名，无几何） */
    private record NodeSpec(
            String id, String label, String type, String group) {
    }

    /** spec 里的一条连线（起止节点 id + 可选文字 + 可选关系语义） */
    private record EdgeSpec(
            String from, String to, String label, String relation) {
    }
}
