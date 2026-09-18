package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 连线端点吸附（v2-33）
 *
 * <p><b>为什么需要它：</b>Excalidraw 里**只有 arrow 支持端点绑定**
 * （内部按 {@code el.type === "arrow"} 判定），`line` 与"只给 points
 * 的箭头"都不会跟随被连图形移动。2026-09-16 用户实测反馈
 * "拖动图形的时候有的连线会跟着消失/看不到"，根因就在这里：
 * LLM 输出里的"点式连线"拖完就留在原地，视觉上等于断开。
 *
 * <p><b>做法：</b>对"两个端点分别落在两个图形包围盒内"的**两点** arrow，
 * 把它升级成绑定式箭头（写 {@code start.id} / {@code end.id}，
 * 丢掉 {@code points} / {@code x} / {@code y}）。升级后：
 * <ul>
 *   <li>前端按绑定重算几何（见 sceneAdapter.withArrowGeometry）；</li>
 *   <li>用户拖动图形时箭头自动跟随 —— 这正是"连线该有的行为"。</li>
 * </ul>
 *
 * <p><b>刻意不做的三件事（都是"宁缺勿滥"）：</b>
 * <ul>
 *   <li>不做多折线（points &gt; 2）：吸附必须丢掉折点，会把用户要的
 *       绕线路径拉直，代价比"不跟随"更大；</li>
 *   <li>只吸附"两端都命中"的线：只命中一端时保留了 points，
 *       而 points 是相对 x/y 的，改 x/y 会让几何错位；</li>
 *   <li>不处理 `line`：Excalidraw 根本不允许 line 绑定（实测判定只看
 *       arrow），要跟随就只能改成 arrow，而改类型会改视觉
 *       （多出箭头），不能静默做。这条路改由 prompt 引导
 *       （见 PromptService：连图形一律用 arrow）。</li>
 * </ul>
 *
 * <p>吸附失败（JSON 坏、没图形、端点悬空）一律**原样返回**，
 * 绝不因为"想优化"而让生成失败。
 */
@Component
public class SceneBinder {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    SceneBinder.class);

    /**
     * 端点吸附容差（px）
     *
     * <p>LLM 给的端点坐标很少精确落在框边上（常压在图形外沿几像素），
     * 所以判定"落在图形上"时把包围盒向外放宽这么多。
     */
    private static final double SNAP_TOLERANCE = 12.0;

    /** 可以当连线端点的图形类型 */
    private static final Set<String> SHAPE_TYPES =
            Set.of("rectangle", "ellipse", "diamond");

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    /**
     * 就地吸附：把能吸附的箭头升级为绑定式
     *
     * @param json 校验用的场景 JSON
     * @return 吸附后的 JSON；没有改动时**返回原字符串**
     *         （避免无谓的重排与格式化）
     */
    public String bind(String json) {
        try {
            JsonNode root =
                    objectMapper.readTree(json);
            JsonNode elements = root.get("elements");
            if (elements == null
                    || !elements.isArray()) {
                return json;
            }
            List<JsonNode> shapes =
                    collectShapes(elements);
            if (shapes.isEmpty()) {
                return json;
            }

            boolean changed = false;
            for (JsonNode elem : elements) {
                if (elem.isObject()
                        && bindOne(
                                (ObjectNode) elem,
                                shapes)) {
                    changed = true;
                }
            }
            return changed
                    ? objectMapper
                            .writeValueAsString(root)
                    : json;
        } catch (Exception e) {
            // 吸附是"锦上添花"，任何异常都不该影响生成主链路
            LOG.warn("连线吸附跳过（解析失败）: {}",
                    e.getMessage());
            return json;
        }
    }

    private List<JsonNode> collectShapes(
            JsonNode elements) {
        List<JsonNode> shapes = new ArrayList<>();
        for (JsonNode elem : elements) {
            if (!elem.isObject()) {
                continue;
            }
            JsonNode type = elem.get("type");
            if (type == null
                    || !SHAPE_TYPES.contains(
                            type.asText())) {
                continue;
            }
            if (!isFinite(elem, "x")
                    || !isFinite(elem, "y")
                    || !isPositive(elem, "width")
                    || !isPositive(elem, "height")) {
                continue;
            }
            shapes.add(elem);
        }
        return shapes;
    }

    /**
     * 单个箭头的吸附
     *
     * @return true = 改动了这个元素
     */
    private boolean bindOne(
            ObjectNode arrow,
            List<JsonNode> shapes) {
        if (!"arrow".equals(text(arrow, "type"))) {
            return false;
        }
        // 已经是绑定式（两端都有）就不动它
        if (arrow.hasNonNull("start")
                && arrow.hasNonNull("end")) {
            return false;
        }
        JsonNode points = arrow.get("points");
        if (points == null
                || !points.isArray()
                || points.size() != 2) {
            return false;
        }
        if (!isFinite(arrow, "x")
                || !isFinite(arrow, "y")
                || !isPoint(points.get(0))
                || !isPoint(points.get(1))) {
            return false;
        }

        double x = arrow.get("x").asDouble();
        double y = arrow.get("y").asDouble();
        double[] from = {
            x + points.get(0).get(0).asDouble(),
            y + points.get(0).get(1).asDouble(),
        };
        double[] to = {
            x + points.get(1).get(0).asDouble(),
            y + points.get(1).get(1).asDouble(),
        };

        String fromId = smallestShapeAt(
                shapes, from);
        String toId = smallestShapeAt(shapes, to);
        if (fromId == null || toId == null
                || fromId.equals(toId)) {
            return false;
        }

        arrow.putObject("start").put("id", fromId);
        arrow.putObject("end").put("id", toId);
        arrow.remove("points");
        arrow.remove("x");
        arrow.remove("y");
        LOG.info("连线吸附: {} 已绑定 {} -> {}",
                text(arrow, "id"), fromId, toId);
        return true;
    }

    /**
     * 找出包含该点的**最小**图形（面积最小 = 最具体）。
     *
     * <p>取最小而不是"第一个命中"：图形常有嵌套
     * （frame 里的框、分组大框），取最小才落在用户真正连的那个框上。
     *
     * @return 命中的图形 id；没有命中返回 null
     */
    private String smallestShapeAt(
            List<JsonNode> shapes,
            double[] point) {
        String bestId = null;
        double bestArea = Double.MAX_VALUE;
        for (JsonNode shape : shapes) {
            double x = shape.get("x").asDouble();
            double y = shape.get("y").asDouble();
            double w = shape.get("width").asDouble();
            double h = shape.get("height").asDouble();
            boolean inside =
                    point[0] >= x - SNAP_TOLERANCE
                    && point[0] <= x + w
                            + SNAP_TOLERANCE
                    && point[1] >= y - SNAP_TOLERANCE
                    && point[1] <= y + h
                            + SNAP_TOLERANCE;
            if (!inside) {
                continue;
            }
            double area = w * h;
            if (area < bestArea) {
                bestArea = area;
                bestId = text(shape, "id");
            }
        }
        return bestId;
    }

    private boolean isPoint(JsonNode node) {
        return node != null
                && node.isArray()
                && node.size() == 2
                && node.get(0).isNumber()
                && node.get(1).isNumber();
    }

    private boolean isFinite(
            JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null
                && value.isNumber()
                && Double.isFinite(value.asDouble());
    }

    private boolean isPositive(
            JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null
                && value.isNumber()
                && value.asDouble() > 0;
    }

    private String text(
            JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual()
                ? null
                : value.asText();
    }
}
