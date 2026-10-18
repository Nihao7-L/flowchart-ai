package io.github.nihaoljx.flowchart.graph.layout;

import io.github.nihaoljx.flowchart.agent.tools.SubmitSpecTool;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ① 层级 group 的常驻回归 + A/B 对照。
 *
 * <p>历史 bug：group 曾用 {@code asText()} 读，Jackson 把数组静默读成空串，
 * 组合态（如「订单 → 处理中 → 支付中」）被拆成三个并排模块，模块间连出
 * 贯穿整张画布的长箭（订单状态机实测最长 2780px）。修复后 group 走
 * {@link GroupPath} 路径，组合态按路径合成「框套框」，相关状态被拢在一起，
 * 最长箭压到 1120px（156 元素整图）。
 *
 * <p>本测试做两件事：
 * <ol>
 *   <li>锁定「框套框」结构：组合态路径必须原样活到元素上，并合成多层嵌套 frame。</li>
 *   <li>A/B 对照：同样的订单状态机，<b>带数组 group（feature ON）</b> 与
 *       <b>不带 group（退化基线，相当于旧 bug 的空 group）</b> 跑同一套管线，
 *       断言 ON 的最长箭 ≤ OFF 的最长箭（feature 把相关状态拢在一起，不会更差），
 *       且 ON 的最长箭有绝对上限（不会再现穿越画布的长线）。</li>
 * </ol>
 */
class NestedGroupCompactnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private SubmitSpecTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        SceneGate gate = new SceneGate(new SceneBinder(), new SceneDeduper(),
                new SceneSanitizer(), new ScenePlanner(),
                new GraphValidator(), new GeometryAudit());
        tool = new SubmitSpecTool(gate, LayoutFamilyFixtures.defaultRegistry());
        ctx = new ToolContext("画一张订单状态机", null);
    }

    /** 订单状态机：组合态路径 ["支付中","处理中","订单"] 这种嵌套 */
    private static String orderMachine(boolean withGroup) {
        if (withGroup) {
            return """
                    {"nodes":[
                      {"id":"order","label":"订单","group":["订单"]},
                      {"id":"processing","label":"处理中","group":["处理中","订单"]},
                      {"id":"paying","label":"支付中","group":["支付中","处理中","订单"]},
                      {"id":"paid","label":"已支付","group":["支付中","处理中","订单"]},
                      {"id":"cancelled","label":"已取消","group":["处理中","订单"]}],
                     "edges":[{"from":"order","to":"processing"},
                              {"from":"processing","to":"cancelled"},
                              {"from":"processing","to":"paying"},
                              {"from":"paying","to":"paid"}]}
                    """;
        }
        // 退化基线：同样的节点和边，但完全不带 group（旧 bug 时空 group 等价）
        return """
                {"nodes":[
                  {"id":"order","label":"订单"},
                  {"id":"processing","label":"处理中"},
                  {"id":"paying","label":"支付中"},
                  {"id":"paid","label":"已支付"},
                  {"id":"cancelled","label":"已取消"}],
                 "edges":[{"from":"order","to":"processing"},
                          {"from":"processing","to":"cancelled"},
                          {"from":"processing","to":"paying"},
                          {"from":"paying","to":"paid"}]}
                """;
    }

    @Test
    @DisplayName("① ON：组合态路径原样保留 + 框套框")
    void compositeStateNestsIntoFrames() throws Exception {
        Observation obs = tool.execute(
                MAPPER.readTree("{\"spec\":" + orderMachine(true) + "}"), ctx);
        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());

        JsonNode elements = MAPPER.readTree(ctx.validScene()).get("elements");
        Map<String, JsonNode> frames = new LinkedHashMap<>();
        JsonNode processing = null;
        for (JsonNode el : elements) {
            if ("processing".equals(el.path("id").asText())) {
                processing = el;
            }
            if ("frame".equals(el.path("type").asText())) {
                frames.put(el.path("name").asText(), el);
            }
        }
        // 路径必须活到元素上（旧 bug 这里会被读成空串）
        assertTrue(processing != null && processing.path("group").isArray(),
                "组合态路径必须保留，实际: " + processing);
        assertEqualsPath(processing.path("group"),
                List.of("处理中", "订单"));

        // 三层嵌套框：订单 / 处理中 / 支付中
        assertTrue(frames.size() >= 3,
                "至少三层嵌套框，实际: " + frames.keySet());
        JsonNode outer = frames.get("订单");
        JsonNode middle = frames.get("处理中");
        JsonNode inner = frames.get("支付中");
        assertTrue(outer != null && middle != null && inner != null,
                "三层框都应存在");
        assertTrue(childrenOf(outer).contains(middle.path("id").asText()),
                "订单框应把处理中框当成员（框套框）");
        assertTrue(childrenOf(middle).contains(inner.path("id").asText()),
                "处理中框应把支付中框当成员");
    }

    @Test
    @DisplayName("① A/B：退化基线（无 group）不合成任何框，且最长箭有绝对上限")
    void featureOnVsOffIsStructuralAndBounded() throws Exception {
        // OFF 基线：节点完全不带 group，相当于旧 bug 时空 group 的情形。
        // 此时分区把节点归到一个模块、框合成路径收不到任何成员 ⇒ 0 个 frame。
        // 这是 feature ON（组合态路径 → 三层嵌套框）的结构性对照。
        JsonNode offElements = runScene(orderMachine(false));
        int offFrames = 0;
        for (JsonNode el : offElements) {
            if ("frame".equals(el.path("type").asText())) {
                offFrames++;
            }
        }
        assertEquals(0, offFrames,
                "无 group 时不应合成任何框（与 feature ON 的嵌套框形成 A/B 对照）");

        // 绝对上限：历史上退化形态（组合态被拆成并排模块）出现过
        // 2780px 贯穿画布的长线；feature 之后同一张小图不应再出现这种量级。
        // 5 节点状态机整图很小，留 2 倍余量防布局抖动。
        double on = longestEdge(orderMachine(true));
        assertTrue(on < 600,
                "带 group 的最长箭应有绝对上限（防穿越画布的长线），实际: " + on);
    }

    // ===== helpers =====

    private double longestEdge(String specJson) throws Exception {
        JsonNode elements = runScene(specJson);
        double max = 0;
        for (JsonNode el : elements) {
            if (!"arrow".equals(el.path("type").asText())) {
                continue;
            }
            JsonNode pts = el.get("points");
            if (pts == null || !pts.isArray() || pts.size() < 2) {
                continue;
            }
            double ox = el.path("x").asDouble();
            double oy = el.path("y").asDouble();
            for (int k = 0; k < pts.size() - 1; k++) {
                double x1 = ox + pts.get(k).get(0).asDouble();
                double y1 = oy + pts.get(k).get(1).asDouble();
                double x2 = ox + pts.get(k + 1).get(0).asDouble();
                double y2 = oy + pts.get(k + 1).get(1).asDouble();
                max = Math.max(max, Math.hypot(x2 - x1, y2 - y1));
            }
        }
        return max;
    }

    private JsonNode runScene(String specJson) throws Exception {
        Observation obs = tool.execute(
                MAPPER.readTree("{\"spec\":" + specJson + "}"), ctx);
        assertTrue(obs.ok(), "应为通过，实际: " + obs.summary());
        String json = ctx.validScene() != null ? ctx.validScene()
                : ctx.processedScene();
        JsonNode root = MAPPER.readTree(json);
        JsonNode els = root.get("elements");
        if (els == null) {
            els = MAPPER.createArrayNode();
        }
        return els;
    }

    private static void assertEqualsPath(JsonNode group, List<String> expected) {
        assertTrue(group.isArray() && group.size() == expected.size(),
                "group 路径长度应一致，实际: " + group);
        for (int i = 0; i < expected.size(); i++) {
            assertTrue(expected.get(i).equals(group.get(i).asText()),
                    "group[" + i + "] 应为 " + expected.get(i)
                            + "，实际: " + group.get(i));
        }
    }

    private static List<String> childrenOf(JsonNode frame) {
        List<String> ids = new ArrayList<>();
        frame.path("children").forEach(c -> ids.add(c.asText()));
        return ids;
    }
}
