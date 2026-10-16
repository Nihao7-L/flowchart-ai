package io.github.nihaoljx.flowchart.graph.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * sequence 家族：UML 时序图的确定性排版（v1 核心三件）
 *
 * <p>画法（行业公认，代码写死不猜）：
 * <ol>
 *   <li>参与者按 spec 声明序横排一排，每人一个框；</li>
 *   <li>每人一条竖直虚线生命线（dashed rect，从框底贯到图底）；</li>
 *   <li>edges 数组序 = 时间序，第 j 条消息占第 j 行，画成两生命线
 *       之间的水平箭头，标签贴箭头上。</li>
 * </ol>
 *
 * <p>v1 刻意不做：loop/alt/par 分组框（条件写在消息标签里不丢语义）、
 * 激活条、自消息回环。二期按同一接口加，不动本类。
 *
 * <p>为什么值得一个家族而不是调 ELK：ELK 只懂通用点线，会把 8 个
 * 参与者当普通节点乱摆、23 条消息捆成缆绳（2026-10-16 实测）。
 * 时序图的「横排 + 时间轴」是确定性规则，且**顺序信息只在 edges
 * 数组序里** —— 交给通用布局必然丢失。
 */
public final class SequenceLayout implements LayoutFamily {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SHAPE_TYPES =
            Set.of("rectangle", "ellipse", "diamond");

    /** 参与者列间距在最大节点宽之上再加的量 */
    private static final double COL_EXTRA = 180.0;

    /** 首个参与者框距图顶的距离（给 addTitle 的 80px 留位） */
    private static final double TOP = 120.0;

    /** 参与者框底到第一条消息行的距离 */
    private static final double FIRST_GAP = 90.0;

    /** 消息行高（容纳一行标签 + 箭头） */
    private static final double ROW_HEIGHT = 64.0;

    /** 最后一条消息行到生命线终点的余量 */
    private static final double BOTTOM_PAD = 60.0;

    /** 图左边距 */
    private static final double MARGIN = 80.0;

    /** 生命线颜色（浅灰，不抢框） */
    private static final String LIFELINE_COLOR = "#adb5bd";

    @Override
    public String type() {
        return "sequence";
    }

    @Override
    public String arrange(String sceneJson,
                          Map<String, SpecNode> nodes,
                          List<SpecEdge> edges) {
        try {
            return doArrange(sceneJson, nodes, edges);
        } catch (Exception e) {
            // 家族纪律：内部兜底，异常上抛让注册表降级 generic
            throw new IllegalStateException(
                    "时序图排版失败: " + e.getMessage(), e);
        }
    }

    private String doArrange(String sceneJson,
                             Map<String, SpecNode> nodes,
                             List<SpecEdge> edges) throws Exception {
        JsonNode root = MAPPER.readTree(sceneJson);
        JsonNode elementsNode = root.get("elements");
        if (elementsNode == null || !elementsNode.isArray()) {
            return sceneJson;
        }
        // 参与者 = spec 声明序（nodes 是 LinkedHashMap，保序）
        List<SpecNode> participants = new ArrayList<>(nodes.values());
        if (participants.isEmpty()) {
            return sceneJson;
        }
        // 从占位场景里读布局层算好的节点尺寸（buildScene 已按标签算好）
        Map<String, double[]> sizeById = new LinkedHashMap<>();
        for (JsonNode el : elementsNode) {
            String id = el.path("id").asText(null);
            if (id == null || !SHAPE_TYPES.contains(
                    el.path("type").asText(""))) {
                continue;
            }
            sizeById.put(id, new double[] {
                    el.path("width").asDouble(160),
                    el.path("height").asDouble(48)});
        }
        double maxW = 0;
        double nodeH = 48;
        for (SpecNode p : participants) {
            double[] size = sizeById.get(p.id());
            if (size != null) {
                maxW = Math.max(maxW, size[0]);
                nodeH = Math.max(nodeH, size[1]);
            }
        }
        double colW = maxW + COL_EXTRA;

        // 参与者框：列中心对齐（坐标统一 snap 到栅格；生命线与消息
        // 箭头都以吸附后的框中心为基准，否则互相差半个栅格）
        Map<String, double[]> centerById = new LinkedHashMap<>();
        ArrayNode out = MAPPER.createArrayNode();
        double lastRowY = TOP + nodeH + FIRST_GAP;
        for (int i = 0; i < participants.size(); i++) {
            SpecNode p = participants.get(i);
            double[] size = sizeById.get(p.id());
            double w = size == null ? 160 : size[0];
            double h = size == null ? 48 : size[1];
            double sx = snap(MARGIN + i * colW + colW / 2 - w / 2);
            double scx = sx + w / 2;
            centerById.put(p.id(), new double[] {scx, sx, w, h});
            ObjectNode box = MAPPER.createObjectNode();
            box.put("id", p.id());
            box.put("type", p.type());
            box.put("x", sx);
            box.put("y", snap(TOP));
            box.put("width", snap(w));
            box.put("height", snap(h));
            box.putObject("label").put("text", p.label());
            String group = p.group();
            if (group != null && !group.isBlank()) {
                box.put("group", group);
            }
            if (p.color() != null && !p.color().isBlank()) {
                box.put("color", p.color());
            }
            out.add(box);
        }
        // 生命线：先入数组 = z 序最底（压在消息箭头下面）
        double lifeBottom = TOP + nodeH + FIRST_GAP
                + Math.max(0, edges.size()) * ROW_HEIGHT + BOTTOM_PAD;
        for (Map.Entry<String, double[]> entry
                : centerById.entrySet()) {
            double[] c = entry.getValue();
            ObjectNode life = MAPPER.createObjectNode();
            life.put("id", "life_" + entry.getKey());
            life.put("type", "rectangle");
            // 2px 细线不 snap：吸附会把中心挪离列中心半个多栅格
            life.put("x", c[0] - 1);
            life.put("y", snap(TOP + c[3]));
            life.put("width", 2);
            life.put("height", snap(lifeBottom - TOP - c[3]));
            life.put("backgroundColor", "transparent");
            life.put("strokeColor", LIFELINE_COLOR);
            life.put("strokeWidth", 1);
            life.put("strokeStyle", "dashed");
            life.put("fillStyle", "solid");
            out.add(life);
        }
        // 消息：edges 数组序 = 时间序，逐行水平箭头（显式 points，
        // 不绑端点 —— 绑定会让前端按框边推导几何，行位就丢了）
        for (int j = 0; j < edges.size(); j++) {
            SpecEdge e = edges.get(j);
            double[] from = centerById.get(e.from());
            double[] to = centerById.get(e.to());
            if (from == null || to == null) {
                continue; // 防御：引用了不存在的参与者（校验层已拦）
            }
            double y = TOP + nodeH + FIRST_GAP + j * ROW_HEIGHT;
            lastRowY = y;
            double dx = to[0] - from[0];
            ObjectNode arrow = MAPPER.createObjectNode();
            arrow.put("id", "msg_" + j);
            arrow.put("type", "arrow");
            arrow.put("x", snap(Math.min(from[0], to[0])));
            arrow.put("y", snap(y));
            arrow.put("width", snap(Math.abs(dx)));
            arrow.put("height", 0);
            ArrayNode points = arrow.putArray("points");
            points.addArray().add(0).add(0);
            points.addArray().add(snap(dx)).add(0);
            if (e.label() != null && !e.label().isBlank()) {
                arrow.putObject("label").put("text", e.label());
            }
            if (e.relation() != null && !e.relation().isBlank()) {
                arrow.put("relation", e.relation());
            }
            out.add(arrow);
        }
        ObjectNode newRoot = MAPPER.createObjectNode();
        newRoot.set("elements", out);
        return MAPPER.writeValueAsString(newRoot);
    }

    private static double snap(double value) {
        return Math.round(value / LayoutSpec.GRID)
                * LayoutSpec.GRID;
    }
}
