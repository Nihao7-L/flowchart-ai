package io.github.nihaoljx.flowchart.graph.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nihaoljx.flowchart.graph.layout.Polyline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 箭头路线几何归一化：把场景里每根箭头的 points 规整成
 * ① 全正交（斜线段拆成直角弯）② 最少拐弯（合并共线冗余弯）。
 *
 * 为什么需要它（2026-10-16）：submit_spec 路径的箭头坐标由 ELK 布局引擎算，
 * 已在 {@code ElkLayoutEngine.writeBackEdgeRoutes} 写回前用
 * {@link Polyline#orthogonalizeAndSimplify} 正交化；但 submit_scene
 * （自由绘制）路径的箭头坐标是 LLM 原生给的 —— 它看不见画布，会把箭头画成
 * 带斜线的"怪物折线"（实测 exam_sm 状态机 21 根箭头里 5 根含斜线段，形如
 * {@code [[0,0],[20,10],...]} 的 2:1 斜杠）。用户明确要求"按官网手画标准：
 * 线不乱、不碰、最少拐弯"，所以自由绘制路径也要补上这一步。
 *
 * 为什么放在 SceneGate 管线（sanitize 之后、claimFramesOnly 之前）：
 * - sanitize 已把 points 归一成相对坐标（首点通常 {@code [0,0]}），
 *   正交化直接可用；
 * - 管线产物就是最终下发的那份 JSON —— 在这里改，校验器判的、
 *   GeometryAudit 量的、前端渲染的都是同一份正交几何
 *   （"校验过的 = 交付的"这条纪律）；
 * - 对 submit_spec 已正交的箭头幂等（无变化原样返回），不重复劳动。
 *
 * 为什么不并进 SceneSanitizer：那边是"零信息损失"的格式归一（七条），
 * 正交化是对"模型想画在哪"的几何重解（与 {@code rebaseAbsolutePoints}
 * 同族、是唯一有推断风险的那类），职责不同；混在一起会稀释它
 * "没坏就不动"的契约，也会让一批"自由箭头 points 不可动"的测试失去意义。
 *
 * 边界（作者意图优先，最小干预）：
 * - 只动 {@code arrow} 的 points；line / freedraw 等其他类型不碰；
 * - 首末点坐标不动（Polyline 保留首点、只拆中段斜线）—— 前端
 *   {@code snapArrowEndsToAnchors} 再把首末点吸到图形边缘，中段保持正交；
 * - points 里有任何一个非法点（非 {@code [数字,数字]}）→ 整条不动，
 *   交给校验器照常报错；
 * - 没有任何箭头被改 → 原样返回入参字符串（引用相等，便于上游判断）。
 *
 * 任何异常都原样返回：几何规整是锦上添花，不能变成新的故障点
 * （与 SceneSanitizer / SceneDeduper 同一条纪律）。
 */
public final class ArrowGeometry {

    private static final Logger LOG =
            LoggerFactory.getLogger(ArrowGeometry.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ArrowGeometry() {
    }

    /**
     * @param json 场景 JSON（应已过 bind / dedup / sanitize）
     * @return 正交化后的 JSON；无改动时返回原字符串
     */
    public static String normalize(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode elements = root.get("elements");
            if (elements == null || !elements.isArray()) {
                return json;
            }
            int changed = 0;
            for (JsonNode element : elements) {
                if (element.isObject()
                        && orthogonalize((ObjectNode) element)) {
                    changed++;
                }
            }
            if (changed == 0) {
                return json;
            }
            LOG.info("箭头几何归一化：正交化 + 最少拐弯 {} 根", changed);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.warn("箭头几何归一化跳过（解析失败）: {}",
                    e.getMessage());
            return json;
        }
    }

    /**
     * @return 真的改动了才 true
     */
    private static boolean orthogonalize(ObjectNode obj) {
        if (!"arrow".equals(obj.path("type").asText())) {
            return false;
        }
        JsonNode points = obj.get("points");
        if (points == null || !points.isArray()
                || points.size() < 2) {
            return false;
        }
        List<double[]> route = new ArrayList<>();
        for (JsonNode point : points) {
            if (!isFinitePair(point)) {
                return false; // 有一个坏点就整条不动，交给校验器报错
            }
            route.add(new double[] {
                    point.get(0).asDouble(),
                    point.get(1).asDouble() });
        }
        // 只处理"带斜线"的箭头：已全正交的路线一律不碰。
        // 这一条是幂等性的关键 —— submit_spec（ELK）产物本就全正交，
        // 若这里再跑一遍 Polyline，会把 ELK 故意保留的"直线边 3 点路由"
        // （首/中/末）又化简塌成 2 点，反噬布局引擎的不变量。
        // 也符合"最小干预：没坏就不动"—— 只有真画斜了才重解。
        if (!hasDiagonal(route)) {
            return false;
        }
        List<double[]> ortho =
                Polyline.orthogonalizeAndSimplify(route);
        if (ortho.size() == route.size() && sameCoords(ortho, route)) {
            return false; // 拆完没变化（理论上到不了，兜底）
        }
        ArrayNode rebuilt = MAPPER.createArrayNode();
        for (double[] p : ortho) {
            ArrayNode pair = rebuilt.addArray();
            appendNumber(pair, p[0]);
            appendNumber(pair, p[1]);
        }
        obj.set("points", rebuilt);
        return true;
    }

    private static boolean sameCoords(
            List<double[]> a, List<double[]> b) {
        for (int i = 0; i < a.size(); i++) {
            if (Math.abs(a.get(i)[0] - b.get(i)[0]) > 1e-6
                    || Math.abs(a.get(i)[1] - b.get(i)[1]) > 1e-6) {
                return false;
            }
        }
        return true;
    }

    /** 路线里有没有斜线段（相邻两点两轴坐标都变） */
    private static boolean hasDiagonal(List<double[]> route) {
        for (int i = 1; i < route.size(); i++) {
            double dx = Math.abs(route.get(i)[0] - route.get(i - 1)[0]);
            double dy = Math.abs(route.get(i)[1] - route.get(i - 1)[1]);
            if (dx > 1e-6 && dy > 1e-6) {
                return true;
            }
        }
        return false;
    }

    /** 是 {@code [有限数字, 有限数字]} 才算合法坐标 */
    private static boolean isFinitePair(JsonNode point) {
        return point != null && point.isArray()
                && point.size() >= 2
                && isFiniteNumber(point.get(0))
                && isFiniteNumber(point.get(1));
    }

    private static boolean isFiniteNumber(JsonNode node) {
        return node != null && node.isNumber()
                && Double.isFinite(node.asDouble());
    }

    /**
     * 整数写整数：不然 {@code [0,0]} 会序列化成 {@code [0.0,0.0]}，
     * 让下发的 JSON 变啰嗦（新坐标多为整数加减，结果九成是整数）。
     */
    private static void appendNumber(ArrayNode target, double value) {
        if (Double.isFinite(value) && value == Math.floor(value)) {
            target.add((long) value);
        } else {
            target.add(value);
        }
    }
}
