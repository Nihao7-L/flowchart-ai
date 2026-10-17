package io.github.nihaoljx.flowchart.graph.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.agent.tools.SubmitSpecTool;
import io.github.nihaoljx.flowchart.graph.layout.LayoutFamilyFixtures;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;
import io.github.nihaoljx.flowchart.llm.Observation;
import io.github.nihaoljx.flowchart.llm.ToolContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 就近摆（用户口径 ⑦：强依赖模块摆到相邻，避免一根线横贯整张画布）的回归测试。
 *
 * <p>用一份合成 spec：四个模块 P/Q/R/S，其中 P 与 Q 之间有大量双向边（强依赖），
 * R/S 几乎孤立。输入顺序故意把强依赖的 P、Q 排在隔位（P,R,Q,S），这样「只按网格
 * 打包、不重排」的 OFF 摆法会把 P、Q 放到对角（距离最大），而开启就近摆的 ON 摆法
 * 会把 P、Q 重排到相邻（距离最小）。断言：ON 的平均跨模块边跨度 < OFF。
 */
class ProximityOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SceneGate gate;
    private SubmitSpecTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        ScenePlanner.proximityEnabled = true;
        gate = new SceneGate(new SceneBinder(), new SceneDeduper(),
                new SceneSanitizer(), new ScenePlanner(),
                new GraphValidator(), new GeometryAudit());
        tool = new SubmitSpecTool(gate, LayoutFamilyFixtures.defaultRegistry());
        ctx = new ToolContext("画一张流程图", null);
    }

    @AfterEach
    void tearDown() {
        // 静态开关复位，避免影响其它测试
        ScenePlanner.proximityEnabled = true;
    }

    private String layout(boolean proximity, String specJson) throws Exception {
        ScenePlanner.proximityEnabled = proximity;
        Observation obs = tool.execute(
                MAPPER.readTree("{\"spec\":" + specJson + "}"), ctx);
        assertTrue(obs.ok(), "spec 应直通：" + obs.summary());
        String scene = ctx.processedScene() != null
                ? ctx.processedScene() : ctx.validScene();
        assertTrue(scene != null && !scene.isBlank(), "应有场景输出");
        return scene;
    }

    /** 平均跨模块边跨度：每条跨模块箭头的「两端节点中心曼哈顿距离」取均值 */
    private double meanCrossModuleSpan(String sceneJson) throws Exception {
        JsonNode root = MAPPER.readTree(sceneJson);
        JsonNode elements = root.get("elements");
        Map<String, double[]> centers = new HashMap<>();
        Map<String, String> moduleOf = new HashMap<>();
        for (JsonNode el : elements) {
            String type = el.path("type").asText();
            if ("arrow".equals(type)) {
                continue;
            }
            String id = el.path("id").asText(null);
            if (id == null) {
                continue;
            }
            double x = el.path("x").asDouble(0);
            double y = el.path("y").asDouble(0);
            double w = el.path("width").asDouble(0);
            double h = el.path("height").asDouble(0);
            centers.put(id, new double[] {x + w / 2, y + h / 2});
            // module = group 路径的第一段（最内层模块名）
            JsonNode group = el.get("group");
            String mod = "?";
            if (group != null) {
                if (group.isTextual()) {
                    mod = group.asText();
                } else if (group.isArray() && group.size() > 0) {
                    mod = group.get(0).asText("?");
                }
            }
            moduleOf.put(id, mod);
        }
        double sum = 0;
        int count = 0;
        for (JsonNode el : elements) {
            if (!"arrow".equals(el.path("type").asText())) {
                continue;
            }
            String from = endpointId(el, "start");
            String to = endpointId(el, "end");
            if (from == null || to == null) {
                continue;
            }
            String mf = moduleOf.get(from);
            String mt = moduleOf.get(to);
            if (mf == null || mt == null || mf.equals(mt)) {
                continue; // 模块内箭头不计入
            }
            double[] cf = centers.get(from);
            double[] ct = centers.get(to);
            if (cf == null || ct == null) {
                continue;
            }
            sum += Math.abs(cf[0] - ct[0]) + Math.abs(cf[1] - ct[1]);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private static String endpointId(JsonNode arrow, String field) {
        JsonNode binding = arrow.get(field);
        if (binding != null && binding.isObject()) {
            String id = binding.path("id").asText(null);
            if (id != null) {
                return id;
            }
        }
        return arrow.path(field).asText(null);
    }

    @Test
    @DisplayName("就近摆开启时，强依赖模块的平均跨模块边跨度更小")
    void proximityReducesCrossModuleSpan() throws Exception {
        String spec = syntheticSpec();
        double on = meanCrossModuleSpan(layout(true, spec));
        double off = meanCrossModuleSpan(layout(false, spec));
        assertTrue(on < off,
                "就近摆开启的平均跨模块跨度(" + on
                        + ") 应小于关闭时(" + off + ")");
    }

    /** P/Q 强依赖、R/S 孤立；输入顺序 P,R,Q,S 让 OFF 把 P、Q 放到对角 */
    private static String syntheticSpec() {
        StringBuilder nodes = new StringBuilder();
        StringBuilder edges = new StringBuilder();
        // 每个模块 3 个节点
        String[] mods = {"P", "R", "Q", "S"};
        int n = 0;
        for (String m : mods) {
            for (int i = 0; i < 3; i++) {
                String id = "n" + (n++);
                nodes.append("{\"id\":\"").append(id)
                        .append("\",\"label\":\"模块").append(m).append("节点").append(i)
                        .append("\",\"type\":\"rectangle\",\"group\":\"").append(m)
                        .append("\"},");
            }
        }
        // P<->Q 各 8 条双向边（强依赖）；另给 P->R、Q->S 各 2 条，避免被一眼拆开
        for (int i = 0; i < 8; i++) {
            edges.append("{\"from\":\"n0\",\"to\":\"n6\"},"); // P -> Q
            edges.append("{\"from\":\"n6\",\"to\":\"n0\"},"); // Q -> P
        }
        edges.append("{\"from\":\"n1\",\"to\":\"n3\"},"); // P -> R
        edges.append("{\"from\":\"n7\",\"to\":\"n9\"},"); // Q -> S
        return "{\"nodes\":[" + nodes.substring(0, nodes.length() - 1) + "],"
                + "\"edges\":[" + edges.substring(0, edges.length() - 1) + "]}";
    }
}
