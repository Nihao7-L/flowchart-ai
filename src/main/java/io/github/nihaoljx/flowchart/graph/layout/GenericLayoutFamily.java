package io.github.nihaoljx.flowchart.graph.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * generic 家族：通用「节点 + 连线」图（流程/架构/组织/思维导图等）
 *
 * <p>即原 SubmitSpecTool 里的通用路径整体搬过来：
 * {@link ScenePlanner#arrange}（分区 + 布局引擎全量排版）+ 分组合框
 * （frame：bbox 包住同组成员）。行为与搬迁前逐字节一致 —— 这次
 * 只是把它从「工具私有逻辑」变成「一个家族」，给 sequence 等
 * 新图型让出路由位（2026-10-16 多图型骨架）。
 */
@Component
public final class GenericLayoutFamily implements LayoutFamily {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** frame 标题的最大长度（与 ScenePlanner.MAX_GROUP_NAME 同口径） */
    private static final int MAX_GROUP_NAME = 24;

    /** frame 包住成员时的四周留白（LayoutSpec.MODULE_PADDING 同源） */
    private static final double FRAME_PADDING = LayoutSpec.MODULE_PADDING;

    private final ScenePlannerHolder plannerHolder;

    /** planner + engine 成对出现，用持有者避免构造器里藏引擎 */
    public record ScenePlannerHolder(
            io.github.nihaoljx.flowchart.graph.transform.ScenePlanner planner,
            LayoutEngine engine) {
    }

    public GenericLayoutFamily(ScenePlannerHolder holder) {
        this.plannerHolder = holder;
    }

    /** Spring 装配入口：由容器注入 planner + 引擎，内部成对持有 */
    @Autowired
    public GenericLayoutFamily(
            io.github.nihaoljx.flowchart.graph.transform.ScenePlanner planner,
            LayoutEngine engine) {
        this.plannerHolder = new ScenePlannerHolder(planner, engine);
    }

    @Override
    public String type() {
        return "generic";
    }

    @Override
    public String arrange(String sceneJson,
                          Map<String, SpecNode> nodes,
                          List<SpecEdge> edges) {
        String laidOut = plannerHolder.planner().arrange(
                sceneJson, plannerHolder.engine(), Set.of());
        return addGroupFrames(laidOut, nodes);
    }

    /**
     * 按模块路径合成画框（frame）：bbox 包住同组成员、name = 模块名、
     * children = 成员 id（有子框时是子框 id）。放在元素数组最前
     * （z 序最底，框压在成员下面），**外层框在子框之前**。
     *
     * <p>为什么在这里合成而不是让模型给：模型填 frame 几何从来填不准
     * （旧路径实测），而 bbox 由布局后的真实坐标算出，天然贴身。
     * 单分组也画框：只要 LLM 显式声明了 group，就尊重它的语义意图。
     *
     * <p>**层级来自 group 路径**（契约 `oneOf[string,string[]]`）：
     * `["处理中","订单"]` 同时归属「处理中」和「订单」两个容器，
     * 于是合成出「订单框里套着处理中框」的嵌套结构 —— 组合态就长这样。
     * 容器身份用**后缀键**（`处理中/订单`）而不是名字：`a/c` 与 `b/c`
     * 是两个不同的 c，用名字做键会把它们并成一个框。
     */
    private String addGroupFrames(
            String laidOut, Map<String, SpecNode> nodes) {
        try {
            JsonNode root = MAPPER.readTree(laidOut);
            JsonNode elementsNode = root.get("elements");
            if (elementsNode == null || !elementsNode.isArray()) {
                return laidOut;
            }
            Set<String> usedIds = new HashSet<>();
            Map<String, List<String>> members = new LinkedHashMap<>();
            Map<String, double[]> boxes = new LinkedHashMap<>();
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, String> parents = new LinkedHashMap<>();
            Map<String, Integer> outerness = new LinkedHashMap<>();
            for (JsonNode el : elementsNode) {
                String id = el.path("id").asText(null);
                if (id != null) {
                    usedIds.add(id);
                }
                List<String> path = GroupPath.parse(el);
                if (path.isEmpty()) {
                    continue;
                }
                double[] box = geometry(el);
                for (int i = 0; i < path.size(); i++) {
                    String key = GroupPath.containerKey(path, i);
                    members.computeIfAbsent(key, k -> new ArrayList<>());
                    names.putIfAbsent(key, path.get(i));
                    parents.putIfAbsent(key, i + 1 < path.size()
                            ? GroupPath.containerKey(path, i + 1) : "");
                    outerness.putIfAbsent(key, path.size() - 1 - i);
                    if (i == 0 && id != null) {
                        members.get(key).add(id);
                    }
                    if (box != null) {
                        boxes.merge(key, box, GenericLayoutFamily::union);
                    }
                }
            }
            if (members.isEmpty()) {
                return laidOut;
            }
            List<String> order = new ArrayList<>(members.keySet());
            order.sort(Comparator.comparingInt(
                    key -> outerness.getOrDefault(key, 0)));
            Map<String, String> frameIds = new LinkedHashMap<>();
            for (String key : order) {
                String frameId = "frame_" + frameIds.size();
                while (usedIds.contains(frameId)) {
                    frameId = frameId + "_x";
                }
                usedIds.add(frameId);
                frameIds.put(key, frameId);
            }
            ArrayNode frames = MAPPER.createArrayNode();
            for (String key : order) {
                double[] box = boxes.get(key);
                if (box == null) {
                    continue;
                }
                ObjectNode frame = frames.addObject();
                frame.put("id", frameIds.get(key));
                frame.put("type", "frame");
                frame.put("name", truncate(names.get(key)));
                frame.put("x", snap(box[0] - FRAME_PADDING));
                frame.put("y", snap(box[1] - FRAME_PADDING));
                frame.put("width",
                        snap(box[2] + FRAME_PADDING)
                                - snap(box[0] - FRAME_PADDING));
                frame.put("height",
                        snap(box[3] + FRAME_PADDING)
                                - snap(box[1] - FRAME_PADDING));
                ArrayNode children = frame.putArray("children");
                for (String member : members.get(key)) {
                    children.add(member);
                }
                for (Map.Entry<String, String> entry : parents.entrySet()) {
                    if (key.equals(entry.getValue())) {
                        children.add(frameIds.get(entry.getKey()));
                    }
                }
            }
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

    /**
     * 元素的几何包围盒，缺几何时返回 null。
     *
     * <p>箭头的 x/y 是**起点**而不是包围盒，拿它算框会把框拉到箭头
     * 起点上，所以要求 width/height 齐全（图形才有）。
     */
    private static double[] geometry(JsonNode el) {
        if (!el.hasNonNull("x") || !el.hasNonNull("y")
                || !el.hasNonNull("width") || !el.hasNonNull("height")) {
            return null;
        }
        double x = el.path("x").asDouble();
        double y = el.path("y").asDouble();
        return new double[] {
            x, y,
            x + el.path("width").asDouble(),
            y + el.path("height").asDouble(),
        };
    }

    /**
     * 两个包围盒的并集。**不可变**：返回新数组，绝不改写入参。
     *
     * <p>为什么不能就地改（曾经踩的坑）：一个多层级路径的元素
     * （如 {@code ["a","x"]}）在循环里把同一个 {@code box} 数组引用同时
     * 存到了「最内层键 {@code a/x}」和「父键 {@code x}」两个映射项上。
     * 若 {@code union} 就地改写第一个参数，后来其它元素往 {@code x} 上
     * merge 时会顺手把 {@code a/x} 的盒子也撑成全图 —— 于是最内层框
     * 吞掉整张画布（深嵌套必现、浅嵌套偶现）。返回新数组后，merge 只替换
     * 目标键的值、绝不污染与之共享引用的其它键。
     */
    private static double[] union(double[] a, double[] b) {
        return new double[] {
                Math.min(a[0], b[0]),
                Math.min(a[1], b[1]),
                Math.max(a[2], b[2]),
                Math.max(a[3], b[3]),
        };
    }

    private static String truncate(String name) {
        return name.length() <= MAX_GROUP_NAME
                ? name : name.substring(0, MAX_GROUP_NAME);
    }

    private static double snap(double value) {
        return Math.round(value / LayoutSpec.GRID)
                * LayoutSpec.GRID;
    }
}
