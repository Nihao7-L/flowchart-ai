package io.github.nihaoljx.flowchart.graph.layout;

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

/**
 * generic 家族：通用「节点 + 连线」图（流程/架构/组织/思维导图等）
 *
 * <p>即原 SubmitSpecTool 里的通用路径整体搬过来：
 * {@link ScenePlanner#arrange}（分区 + 布局引擎全量排版）+ 分组合框
 * （frame：bbox 包住同组成员）。行为与搬迁前逐字节一致 —— 这次
 * 只是把它从「工具私有逻辑」变成「一个家族」，给 sequence 等
 * 新图型让出路由位（2026-10-16 多图型骨架）。
 */
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
     * 按分组合成画框（frame）：bbox 包住同组成员、name = 组名、
     * children = 成员 id。放在元素数组最前（z 序最底，框压在成员下面）。
     *
     * <p>为什么在这里合成而不是让模型给：模型填 frame 几何从来填不准
     * （旧路径实测），而 bbox 由布局后的真实坐标算出，天然贴身。
     * 单分组也画框：只要 LLM 显式声明了 group，就尊重它的语义意图。
     */
    private String addGroupFrames(
            String laidOut, Map<String, SpecNode> nodes) {
        try {
            JsonNode root = MAPPER.readTree(laidOut);
            JsonNode elementsNode = root.get("elements");
            if (elementsNode == null || !elementsNode.isArray()) {
                return laidOut;
            }
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
            if (members.isEmpty()) {
                return laidOut;
            }
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

    /** 空白分组名归一为 null（无分组） */
    private static String normalizedGroup(String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        String trimmed = group.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
