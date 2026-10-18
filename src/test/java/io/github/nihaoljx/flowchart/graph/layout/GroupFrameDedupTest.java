import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.GenericLayoutFamily;
import io.github.nihaoljx.flowchart.graph.layout.GenericLayoutFamily.ScenePlannerHolder;
import io.github.nihaoljx.flowchart.graph.layout.SpecNode;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 去重回归：LLM 自己画了 frame 时，后端不再合成同层的合成框。
 *
 * <p>旗标场景：模型在文本回合里给「已完成」画了 frame，同时把节点
 * group 写成 ["已完成","订单"]。修好前两个画面都会画框 ——
 * 外层「订单」框里套一个「已完成」框，端上看就是「重复框」。
 *
 * <p>直调 private static addGroupFrames 而不是走 ScenePlanner.arrange：
 * arrange 的分区路径会把 frame 认领/改写，断言里混进无关噪声。
 */
final class GroupFrameDedupTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Method addGroupFrames;

    static {
        try {
            addGroupFrames = GenericLayoutFamily.class.getDeclaredMethod(
                    "addGroupFrames", String.class, Map.class);
            addGroupFrames.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    @DisplayName("LLM 画了同名 frame：不再合成那层，成员只被一层框包住")
    void drawnFrameWinsOverSynthesizedOne() throws Exception {
        // 「已完成」已被 LLM 画成 frame；同 group 的成员写的是「已完成/订单」
        // —— 端上看到的就是外层「订单」框里还套一个「已完成」框
        String scene = """
            {"elements":[
              {"id":"frame-llm","type":"frame","name":"已完成","x":0,"y":0,"width":100,"height":50},
              {"id":"n1","type":"rectangle","x":40,"y":60,"width":80,"height":40,"label":"已完成","group":["已完成","订单"]},
              {"id":"n2","type":"rectangle","x":200,"y":60,"width":80,"height":40,"label":"已发货","group":["已完成","订单"]}
            ]}
            """;
        JsonNode root = addFrames(scene);

        JsonNode drawn = findFrameByName(root, "已完成");
        assertNotNull(drawn, "LLM 画的 frame 必须保留");
        assertEquals("frame-llm", drawn.path("id").asText(),
                "必须保留 LLM 那个，不能换成合成框");
        assertEquals(2, countFrames(root),
                "「已完成」只有 LLM 那个；外层「订单」仍合成");
    }

    @Test
    @DisplayName("LLM 只画了内层：外层仍在合成，且物理包住内层")
    void outerLayerStillSynthesizedWhenInnerIsDrawn() throws Exception {
        // LLM 画了「已完成」；group 是 ["已完成","订单"] —— 「订单」这层
        // 与 LLM 框不同名，仍应合成，否则外层语义丢掉
        String scene = """
            {"elements":[
              {"id":"frame-llm","type":"frame","name":"已完成","x":0,"y":0,"width":100,"height":50},
              {"id":"n1","type":"rectangle","x":40,"y":60,"width":80,"height":40,"label":"已完成","group":["已完成","订单"]},
              {"id":"n2","type":"rectangle","x":200,"y":60,"width":80,"height":40,"label":"已发货","group":["已完成","订单"]}
            ]}
            """;
        JsonNode root = addFrames(scene);

        JsonNode outer = findFrameByName(root, "订单");
        assertNotNull(outer, "外层「订单」仍应合成");
        JsonNode inner = findFrameByName(root, "已完成");
        assertEquals("frame-llm", inner.path("id").asText());

        // 外层必须同时罩住两个成员；LLM 那个框由端上自己挂到外层
        // （matchFrames 认领 LLM 框给模块、collectChildren 填进去）。
        // bbox 是被合成框的成员 + **该成员自己的 group 路径**算出来的，
        // 不包含 LLM 框自身（它没有 group），所以只断言罩住成员。
        List<String> kids = childrenOf(outer);
        assertTrue(kids.containsAll(List.of("n1", "n2")),
                "外层 children 要含两个成员，实际: " + kids);

        for (String member : List.of("n1", "n2")) {
            JsonNode el = findElement(root, member);
            double[] m = geometry(el);
            double[] o = geometry(outer);
            assertTrue(o[0] <= m[0] && o[1] <= m[1]
                            && o[0] + o[2] >= m[0] + m[2]
                            && o[1] + o[3] >= m[1] + m[3],
                    "外层要物理包住成员 " + member + ": outer=" + fmt(o)
                            + " member=" + fmt(m));
        }
    }

    @Test
    @DisplayName("没有任何 LLM frame：合成行为与从前逐条一致")
    void synthesisUnchangedWhenNothingDrawn() throws Exception {
        // 这是防「去重顺手改坏原行为」的锚点：两组 group，两层容器。
        // 原行为 = 内层框直接收自己的成员，外层通过 parents 反查只收
        // **子框 id**（不重复收成员的 id）—— 这是设计：渲染器一个元素
        // 只能属于一个 frame。
        String scene = """
            {"elements":[
              {"id":"n1","type":"rectangle","x":40,"y":60,"width":80,"height":40,"label":"A","group":["已完成","订单"]},
              {"id":"n2","type":"rectangle","x":200,"y":60,"width":80,"height":40,"label":"B","group":["已完成","订单"]},
              {"id":"n3","type":"rectangle","x":40,"y":200,"width":80,"height":40,"label":"C","group":["处理中","订单"]},
              {"id":"n4","type":"rectangle","x":200,"y":200,"width":80,"height":40,"label":"D","group":["处理中","订单"]}
            ]}
            """;
        JsonNode root = addFrames(scene);

        assertEquals(3, countFrames(root), "两个内层 + 一个外层");
        assertNotNull(findFrameByName(root, "已完成"));
        assertNotNull(findFrameByName(root, "处理中"));
        JsonNode outer = findFrameByName(root, "订单");
        assertNotNull(outer);
        List<String> kids = childrenOf(outer);
        // 用框名反查 id：合成框的编号顺序取决于 outerness 排序，
        // 是内部实现细节，测试不该把 frame_N 写死
        List<String> kidNames = new ArrayList<>();
        for (String kid : kids) {
            if (kid.startsWith("frame")) {
                kidNames.add(frameName(root, kid));
            }
        }
        assertEquals(2, kidNames.size(),
                "外层应只收两个子框 id，实际: " + kids);
        assertTrue(kidNames.containsAll(List.of("已完成", "处理中")),
                "外层 children = 两个子框 id（成员属于子框，不重复出现），实际: "
                        + kidNames);
        JsonNode innerA = findFrameByName(root, "已完成");
        assertTrue(childrenOf(innerA).containsAll(List.of("n1", "n2")),
                "内层 children 直接收成员，实际: " + childrenOf(innerA));
    }

    @Test
    @DisplayName("只画了中间层：外层与内层都仍合成")
    void dedupMatchesAnyPathSegment() throws Exception {
        // LLM 只画「订单」（中间那段），「已完成」照常合成 —— 判据是
        // 「任一层段名命中 frame.name」，不是整条路径相等
        String scene = """
            {"elements":[
              {"id":"frame-llm","type":"frame","name":"订单","x":0,"y":0,"width":400,"height":200},
              {"id":"n1","type":"rectangle","x":40,"y":60,"width":80,"height":40,"label":"已完成","group":["已完成","订单"]}
            ]}
            """;
        JsonNode root = addFrames(scene);

        assertEquals("frame-llm",
                findFrameByName(root, "订单").path("id").asText(),
                "LLM 那个赢");
        assertNotNull(findFrameByName(root, "已完成"),
                "更内层照常合成");
    }

    @Test
    @DisplayName("LLM 画了不相关名字的孤立框：不影响合成")
    void drawnFrameWithoutGroupIsIgnored() throws Exception {
        // 孤立框的名字跟任何 group 段名都不相干，合成路径原样走
        String scene = """
            {"elements":[
              {"id":"frame-llm","type":"frame","name":"孤立框","x":0,"y":0,"width":100,"height":50},
              {"id":"n1","type":"rectangle","x":40,"y":60,"width":80,"height":40,"label":"已完成","group":["已完成"]}
            ]}
            """;
        JsonNode root = addFrames(scene);

        assertEquals("frame-llm",
                findFrameByName(root, "孤立框").path("id").asText());
        assertNotNull(findFrameByName(root, "已完成"),
                "group 命中的容器仍要合成");
    }

    @Test
    @DisplayName("多条 group 路径：被占用的层不合成，兄弟层照常合成")
    void onlyOccupiedSegmentsAreSkipped() throws Exception {
        // 「已完成」被 LLM 画了，「处理中」没有 ⇒ 内层「已完成」跳过、
        // 「处理中」照常合成；两者的共同外层「订单」也照常合成
        String scene = """
            {"elements":[
              {"id":"frame-llm","type":"frame","name":"已完成","x":0,"y":0,"width":100,"height":50},
              {"id":"n1","type":"rectangle","x":40,"y":60,"width":80,"height":40,"label":"已完成","group":["已完成","订单"]},
              {"id":"n2","type":"rectangle","x":200,"y":60,"width":80,"height":40,"label":"处理中","group":["处理中","订单"]}
            ]}
            """;
        JsonNode root = addFrames(scene);

        assertEquals(3, countFrames(root),
                "LLM 那个 + 合成的「处理中」+ 合成的外层「订单」");
        assertEquals("frame-llm",
                findFrameByName(root, "已完成").path("id").asText());
        assertNotNull(findFrameByName(root, "处理中"),
                "没被占用的兄弟层照常合成");
        JsonNode outer = findFrameByName(root, "订单");
        assertNotNull(outer, "共同外层仍合成");

        // 「已完成」那层删掉后，它的两个成员必须被**上交给外层**当
        // children，否则它们变成没有任何框的孤儿元素
        List<String> kids = childrenOf(outer);
        assertTrue(kids.contains("n1"),
                "被删层的成员要上交给外层，实际: " + kids);
        // 「处理中」仍是一个子框被外层收（它的成员 n2 归它自己）
        boolean hasProcessingFrame = false;
        for (String kid : kids) {
            if (kid.startsWith("frame")
                    && "处理中".equals(frameName(root, kid))) {
                hasProcessingFrame = true;
            }
        }
        assertTrue(kids.contains("n2") || hasProcessingFrame,
                "n2 要么直接交给外层、要么归兄弟子框，实际: " + kids);
    }

    @Test
    @DisplayName("节点只有一个 group 也正常合成")
    void emptyNodesIsSafe() throws Exception {
        // 单段路径，arents 里入 ""，别 NPE
        String scene = """
            {"elements":[
              {"id":"n1","type":"rectangle","x":40,"y":60,"width":80,"height":40,"label":"已完成","group":["已完成"]}
            ]}
            """;
        JsonNode root = addFrames(scene);

        assertEquals(1, countFrames(root));
        assertNotNull(findFrameByName(root, "已完成"));
    }

    @Test
    @DisplayName("并行区 group 段带「并行：」前缀：合成框画成紫色虚线，标题去掉前缀")
    void parallelRegionIsStyledPurpleDashed() throws Exception {
        // group 段名 "并行：通知" ⇒ 该层合成框描边紫 + 虚线，
        // 显示标题只剩「通知」（前缀只用于后端识别）
        String scene = """
            {"elements":[
              {"id":"n1","type":"rectangle","x":40,"y":60,"width":80,"height":40,"label":"待通知","group":["并行：通知","订单"]},
              {"id":"n2","type":"rectangle","x":200,"y":60,"width":80,"height":40,"label":"已通知","group":["并行：通知","订单"]}
            ]}
            """;
        JsonNode root = addFrames(scene);

        JsonNode region = findFrameByName(root, "通知");
        assertNotNull(region, "并行区框应去掉前缀只叫「通知」");
        assertEquals("dashed", region.path("strokeStyle").asText(),
                "并行区框必须是虚线");
        assertEquals("#a855f7", region.path("strokeColor").asText(),
                "并行区框必须紫色描边");
        assertEquals(2, region.path("strokeWidth").asInt(),
                "虚线描边宽度 2");
    }

    @Test
    @DisplayName("普通 group 不带前缀：合成框不染紫色虚线")
    void normalGroupIsNotStyled() throws Exception {
        String scene = """
            {"elements":[
              {"id":"n1","type":"rectangle","x":40,"y":60,"width":80,"height":40,"label":"A","group":["处理中","订单"]},
              {"id":"n2","type":"rectangle","x":200,"y":60,"width":80,"height":40,"label":"B","group":["处理中","订单"]}
            ]}
            """;
        JsonNode root = addFrames(scene);

        JsonNode inner = findFrameByName(root, "处理中");
        assertNotNull(inner);
        assertTrue(inner.path("strokeStyle").asText().isEmpty()
                        || !"dashed".equals(inner.path("strokeStyle").asText()),
                "普通 group 框不应是虚线");
        assertTrue(inner.path("strokeColor").asText().isEmpty(),
                "普通 group 框不应有紫色描边");
    }

    // ---------- 测试内 helper（不放 src/test 之外，只服务本类断言） ----------

    private static JsonNode addFrames(String scene) throws Exception {
        GenericLayoutFamily family = new GenericLayoutFamily(
                new ScenePlannerHolder(new ScenePlanner(), new ElkLayoutEngine()));
        String out = (String) addGroupFrames.invoke(family, scene, Map.<String, SpecNode>of());
        return MAPPER.readTree(out);
    }

    private static JsonNode findFrameByName(JsonNode root, String name) {
        for (JsonNode el : root.get("elements")) {
            if ("frame".equals(el.path("type").asText())
                    && name.equals(el.path("name").asText())) {
                return el;
            }
        }
        return null;
    }

    private static JsonNode findElement(JsonNode root, String id) {
        for (JsonNode el : root.get("elements")) {
            if (id.equals(el.path("id").asText())) {
                return el;
            }
        }
        return null;
    }

    private static int countFrames(JsonNode root) {
        int n = 0;
        for (JsonNode el : root.get("elements")) {
            if ("frame".equals(el.path("type").asText())) {
                n++;
            }
        }
        return n;
    }

    private static List<String> childrenOf(JsonNode frame) {
        List<String> ids = new ArrayList<>();
        frame.path("children").forEach(c -> ids.add(c.asText()));
        return ids;
    }

    private static String frameName(JsonNode root, String frameId) {
        for (JsonNode el : root.get("elements")) {
            if (frameId.equals(el.path("id").asText())) {
                return el.path("name").asText();
            }
        }
        return "";
    }

    private static double[] geometry(JsonNode el) {
        return new double[] {
                el.path("x").asDouble(),
                el.path("y").asDouble(),
                el.path("width").asDouble(),
                el.path("height").asDouble()
        };
    }

    private static String fmt(double[] g) {
        return "[" + g[0] + "," + g[1] + " " + g[2] + "x" + g[3] + "]";
    }
}
