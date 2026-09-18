package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 连线去重（v2-34）
 *
 * <p><b>它解决的问题：</b>用户删掉一条线、自己手绘一条补上之后 ——
 * 后端模型里那条旧线还在（删除当时没有回写通道），而用户手绘的线
 * 又从来没有进过后端。于是下一轮 LLM 把旧线原样输出，与用户手绘的
 * 那条**并存**，同一对图形之间出现两条线（2026-09-16 用户实测：
 * 「让他删除几个节点后，画出来的图有的地方多加了这么多的线」，
 * 而且「多添加线的地方是我删除一次然后又添加上去的」）。
 *
 * <p><b>两个判重来源：</b>
 * <ul>
 *   <li>集合内：LLM 自己输出了重复的连线；</li>
 *   <li>跨集合：LLM 画的某条线，与用户手绘的连线端点重合。</li>
 * </ul>
 *
 * <p><b>判据刻意保守</b>（误删比漏删难发现得多 —— 用户会去数"多出来的线"，
 * 但不会去数"少了几条"）：
 * <ul>
 *   <li>只处理连线（arrow / line），图形与文字一律不动；</li>
 *   <li>方向敏感：A→B 与 B→A 不互删（双向箭头是合法表达）；</li>
 *   <li>label 必须相同才判重 —— 流程图里 A→B 的「是」「否」两条分支
 *       端点完全一样，只有 label 能区分它们；</li>
 *   <li>跨集合判重额外要求「LLM 那条线没有 label」；</li>
 *   <li>两端都有绑定 id 时比 id 对，否则比端点坐标（容差内）。</li>
 * </ul>
 *
 * <p>任何异常都**原样返回**：去重是锦上添花，不能变成新的故障点。
 */
@Component
public class SceneDeduper {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    SceneDeduper.class);

    /** 端点判同容差（px），与 SceneBinder 的吸附容差保持一致 */
    private static final double SAME_TOLERANCE = 12.0;

    /** 可以判重的连线类型 */
    private static final Set<String> LINK_TYPES =
            Set.of("arrow", "line");

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    /**
     * 去掉重复连线
     *
     * @param json         LLM 输出的场景 JSON（应已做过端点吸附：
     *                     吸附后的线是绑定式，比 id 对比比坐标可靠）
     * @param userElements 用户手绘元素的轻量描述；可为 null
     * @return 去重后的 JSON；没有改动时**返回原字符串**
     */
    public String dedup(
            String json,
            List<Map<String, Object>> userElements) {
        try {
            JsonNode root =
                    objectMapper.readTree(json);
            JsonNode elements = root.get("elements");
            if (elements == null
                    || !elements.isArray()) {
                return json;
            }
            List<Link> userLinks =
                    linksOfUserElements(userElements);

            List<JsonNode> kept = new ArrayList<>();
            List<Link> keptLinks = new ArrayList<>();
            int dropped = 0;
            for (JsonNode elem : elements) {
                Link link = linkOf(elem);
                if (link == null) {
                    kept.add(elem);
                    continue;
                }
                boolean sameAsPeers =
                        matchesAny(link, keptLinks);
                boolean sameAsUser = !sameAsPeers
                        && link.label.isEmpty()
                        && matchesAny(link, userLinks);
                if (sameAsPeers || sameAsUser) {
                    dropped++;
                    LOG.info("连线去重丢弃 {}（{}）",
                            text(elem, "id"),
                            sameAsPeers
                                    ? "输出内重复"
                                    : "与用户手绘重复");
                    continue;
                }
                keptLinks.add(link);
                kept.add(elem);
            }
            if (dropped == 0) {
                return json;
            }
            ((ObjectNode) root).set(
                    "elements", toArrayNode(kept));
            LOG.info("连线去重：丢弃 {} 条", dropped);
            return objectMapper
                    .writeValueAsString(root);
        } catch (Exception e) {
            LOG.warn("连线去重跳过（解析失败）: {}",
                    e.getMessage());
            return json;
        }
    }

    /** 输出里的这类元素是不是连线；是就转成可比较形态 */
    private Link linkOf(JsonNode elem) {
        if (!elem.isObject()
                || !isLinkType(text(elem, "type"))) {
            return null;
        }
        return new Link(
                bindingId(elem, "start"),
                bindingId(elem, "end"),
                endpoint(elem, 0),
                endpoint(elem, 1),
                labelOf(elem));
    }

    /**
     * 用户手绘连线转成可比较形态
     *
     * <p>label 一律按空串处理：跨集合判重在外层已经要求
     * "LLM 那条线没有 label"，用户那条写没写字不影响判定。
     */
    private List<Link> linksOfUserElements(
            List<Map<String, Object>> userElements) {
        List<Link> links = new ArrayList<>();
        if (userElements == null) {
            return links;
        }
        for (Map<String, Object> el : userElements) {
            if (el == null
                    || !isLinkType(str(el.get("type")))) {
                continue;
            }
            links.add(new Link(
                    str(el.get("startId")),
                    str(el.get("endId")),
                    userEndpoint(el, 0),
                    userEndpoint(el, 1),
                    ""));
        }
        return links;
    }

    private boolean matchesAny(
            Link link, List<Link> others) {
        for (Link other : others) {
            if (link.matches(other)) {
                return true;
            }
        }
        return false;
    }

    /** 连线第 index 个端点的绝对坐标 = (x, y) + points[index] */
    private double[] endpoint(JsonNode elem, int index) {
        JsonNode points = elem.get("points");
        if (points == null
                || !points.isArray()
                || points.size() <= index) {
            return null;
        }
        JsonNode point = points.get(index);
        if (point == null
                || !point.isArray()
                || point.size() != 2
                || !point.get(0).isNumber()
                || !point.get(1).isNumber()) {
            return null;
        }
        return new double[] {
            num(elem, "x") + point.get(0).asDouble(),
            num(elem, "y") + point.get(1).asDouble(),
        };
    }

    /** 用户手绘连线第 index 个端点的绝对坐标 */
    private double[] userEndpoint(
            Map<String, Object> el, int index) {
        Object pointsObj = el.get("points");
        if (!(pointsObj instanceof List<?> points)
                || points.size() <= index) {
            return null;
        }
        if (!(points.get(index)
                instanceof List<?> point)
                || point.size() != 2) {
            return null;
        }
        Double px = dbl(point.get(0));
        Double py = dbl(point.get(1));
        Double x = dbl(el.get("x"));
        Double y = dbl(el.get("y"));
        if (px == null || py == null
                || x == null || y == null) {
            return null;
        }
        return new double[] {x + px, y + py};
    }

    /** 连线的文字标签（schema 里 label 是对象，取它的 text） */
    private String labelOf(JsonNode elem) {
        JsonNode label = elem.get("label");
        if (label == null) {
            return "";
        }
        if (label.isTextual()) {
            return label.asText();
        }
        JsonNode text = label.get("text");
        return text != null && text.isTextual()
                ? text.asText()
                : "";
    }

    /** 读绑定字段里的元素 id */
    private String bindingId(
            JsonNode elem, String field) {
        JsonNode binding = elem.get(field);
        if (binding == null
                || !binding.isObject()) {
            return null;
        }
        return text(binding, "id");
    }

    private boolean isLinkType(String type) {
        return type != null
                && LINK_TYPES.contains(type);
    }

    private ArrayNode toArrayNode(
            List<JsonNode> nodes) {
        ArrayNode array =
                objectMapper.createArrayNode();
        nodes.forEach(array::add);
        return array;
    }

    private double num(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber()
                ? value.asDouble()
                : 0.0;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual()
                ? null
                : value.asText();
    }

    private String str(Object value) {
        return value instanceof String s ? s : null;
    }

    private Double dbl(Object value) {
        if (value instanceof Number n) {
            double v = n.doubleValue();
            return Double.isFinite(v) ? v : null;
        }
        return null;
    }

    /**
     * 一条连线的可比较形态。
     *
     * <p>两端都有绑定 id 时比 id 对；否则比端点坐标。两条都拿不到
     * 就认作"不可判重"（宁可漏判）。
     */
    private static final class Link {

        private final String startId;
        private final String endId;
        private final double[] from;
        private final double[] to;
        private final String label;

        private Link(String startId, String endId,
                double[] from, double[] to,
                String label) {
            this.startId = startId;
            this.endId = endId;
            this.from = from;
            this.to = to;
            this.label = label;
        }

        /**
         * 是否同一条连线。
         *
         * <p>label 必须相同 —— 端点完全一样的两条分支线只能靠它区分。
         * 方向敏感：A→B 与 B→A 是两条不同的线。
         */
        private boolean matches(Link other) {
            if (!label.equals(other.label)) {
                return false;
            }
            if (startId != null && endId != null
                    && other.startId != null
                    && other.endId != null) {
                return startId.equals(other.startId)
                        && endId.equals(other.endId);
            }
            return from != null && to != null
                    && other.from != null
                    && other.to != null
                    && near(from, other.from)
                    && near(to, other.to);
        }

        /** 两个点是否落在容差内 */
        private static boolean near(
                double[] a, double[] b) {
            return Math.hypot(
                    a[0] - b[0], a[1] - b[1])
                    <= SAME_TOLERANCE;
        }
    }
}
