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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主轴续接虚拟边（{@link ScenePlanner#virtualSpineArrows}）的回归。
 *
 * <p>病象（用户口径：「主轴断成两截」）：主轴节点 A、B、C、D 都在同一个块里，
 * 但它们之间的连线要经过**兄弟块**里的中转成员（A→B→C→X1→X2→D，X1/X2 在
 * 另一个块）。{@code layoutOwnShapes} 只把「两端都在本块」的箭头喂给引擎，
 * 于是引擎在本块里只看到 4 个孤立节点，把它们摆成一片。
 *
 * <p>**为什么断言的是"虚拟边生成了 a→d"而不是"层差变大"**：LayoutBlock 会
 * 先试竖排再试横排，再按 {@code viewportPenalty} 定稿；块内主方向由这个
 * 视角判定决定，横竖胜负对合成小样本极不稳定（实测同一份 spec 在
 * spine=true 时被判成横排，四个主轴节点排成一行，层差反而更小 —— 那
 * 不是虚拟边失效，是"层差"这个观测量本身被主方向污染了）。虚拟边是
 * 入参，直接断言它比断言一个下游坐标稳健得多；真实 flow 图上主方向是
 * 竖排，A/B 探针实测 6 个主轴节点 x 全相同、y 等差 120。
 */
class SpineArrowOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SubmitSpecTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        ScenePlanner.spineEnabled = true;
        SceneGate gate = new SceneGate(new SceneBinder(), new SceneDeduper(),
                new SceneSanitizer(), new ScenePlanner(),
                new GraphValidator(), new GeometryAudit());
        tool = new SubmitSpecTool(gate, LayoutFamilyFixtures.defaultRegistry());
        ctx = new ToolContext("画一张流程图", null);
    }

    @AfterEach
    void tearDown() {
        ScenePlanner.spineEnabled = true;
    }

    private JsonNode layout(boolean spine) throws Exception {
        ScenePlanner.spineEnabled = spine;
        Observation obs = tool.execute(
                MAPPER.readTree("{\"spec\":" + bridgeSpec() + "}"), ctx);
        assertTrue(obs.ok(), "spec 应直通：" + obs.summary());
        String scene = ctx.processedScene() != null
                ? ctx.processedScene() : ctx.validScene();
        assertTrue(scene != null && !scene.isBlank(), "应有场景输出");
        return MAPPER.readTree(scene);
    }

    /**
     * 复现真实 flow 图的块树：主轴节点归 `spine` 块、中转成员归 `inner` 块，
     * 两个块是**兄弟**（不是「一个块的 own + 它的子块」—— 后者实测不是真实
     * 形态：suite_flow 的块树是 根(shapes=[]) → [主流程, 支付处理, 物流处理]，
     * 主轴节点全在「主流程」块里且该块没有子块，早期只认子块的判据因此一次
     * 都没生成虚拟边，A/B 两版坐标逐字节相同）。
     */
    private static String bridgeSpec() {
        return "{\"nodes\":["
                + node("a", "开始", "spine") + ","
                + node("b", "第二步", "spine") + ","
                + node("c", "第三步", "spine") + ","
                + node("d", "结束", "spine") + ","
                + node("i1", "中间一", "inner") + ","
                + node("i2", "中间二", "inner")
                + "],\"edges\":["
                + edge("a", "b") + ","
                + edge("b", "c") + ","
                + edge("c", "i1") + ","
                + edge("i1", "i2") + ","
                + edge("i2", "d")
                + "]}";
    }

    /**
     * 必须带 width/height：{@link io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine}
     * 的 {@code collectShapes} 要求 {@code finite(x,y) && positive(width,height)}，
     * 缺尺寸的图形会被直接丢掉 —— 丢掉后 shapes 为空、引擎原样返回入参坐标，
     * 虚拟边根本没机会参与排层（测试一度就是这么假绿假红的：坐标来自
     * ScenePlanner 的兜底网格，与开关无关）。
     */
    private static String node(String id, String label, String group) {
        return "{\"id\":\"" + id + "\",\"label\":\"" + label
                + "\",\"type\":\"rectangle\",\"width\":160,\"height\":60,\"x\":0,\"y\":0"
                + (group == null ? "" : ",\"group\":\"" + group + "\"")
                + "}";
    }

    private static String edge(String from, String to) {
        return "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    @Test
    @DisplayName("兄弟块隔开的主轴两端被虚拟边接上")
    void virtualEdgeBridgesSpineEnds() throws Exception {
        Object partition = partitionOf(layout(true));
        List<?> blocks = blocksOf(partition);

        // 找到 own 含 a/b/c/d 的里脊块与它的兄弟块
        int spineIndex = -1;
        List<Object> siblings = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Set<String> ids = shapeIds(blocks.get(i));
            if (ids.contains("a") && ids.contains("d")) {
                spineIndex = i;
            } else {
                siblings.add(blocks.get(i));
            }
        }
        assertTrue(spineIndex >= 0, "应有含 a..d 的里脊块");

        Set<String> own = shapeIds(blocks.get(spineIndex));
        List<JsonNode> virt = invokeSpine(blocks.get(spineIndex), own,
                partition, siblings);
        List<String> pairs = new ArrayList<>();
        for (JsonNode v : virt) {
            pairs.add(v.path("start").path("id").asText()
                    + "->" + v.path("end").path("id").asText());
        }
        System.out.println("[SPINE] own=" + own + " virtual=" + pairs);
        assertTrue(pairs.contains("c->d"),
                "c 出去、d 进来，中间经过兄弟块 ⇒ 应补出 c->d，实际 " + pairs);
    }

    @Test
    @DisplayName("本块内部自己连通的节点不补虚拟边")
    void alreadyConnectedSpineNeedsNoVirtualEdge() throws Exception {
        // a→b→c→d 全部在本块内连通、i1/i2 在兄弟块里且与本块无往来 ⇒
        // enters/exits 都空，不该凭空补边（否则会把无关节点串成一条假链）
        String spec = "{\"nodes\":["
                + node("a", "开始", "spine") + ","
                + node("b", "第二步", "spine") + ","
                + node("c", "第三步", "spine") + ","
                + node("d", "结束", "spine") + ","
                + node("i1", "中间一", "inner") + ","
                + node("i2", "中间二", "inner")
                + "],\"edges\":["
                + edge("a", "b") + "," + edge("b", "c") + "," + edge("c", "d")
                + "]}";
        Observation obs = tool.execute(
                MAPPER.readTree("{\"spec\":" + spec + "}"), ctx);
        assertTrue(obs.ok(), "spec 应直通：" + obs.summary());
        String scene = ctx.processedScene() != null
                ? ctx.processedScene() : ctx.validScene();
        Object partition = partitionOf(MAPPER.readTree(scene));
        List<?> blocks = blocksOf(partition);
        int spineIndex = -1;
        List<Object> siblings = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            Set<String> ids = shapeIds(blocks.get(i));
            if (ids.contains("a") && ids.contains("d")) {
                spineIndex = i;
            } else {
                siblings.add(blocks.get(i));
            }
        }
        assertTrue(spineIndex >= 0, "应有含 a..d 的里脊块");
        assertEquals(0, invokeSpine(blocks.get(spineIndex),
                shapeIds(blocks.get(spineIndex)), partition, siblings).size(),
                "主轴块自己已连通、兄弟块与本块无往来，不该补虚拟边");
    }

    // ==================== 反射取私有结构 ====================

    private Object partitionOf(JsonNode scene) throws Exception {
        // 箭头必须一起传：Partition.rawElements 是虚拟边找「谁进谁出」的唯一
        // 依据，只传图形会让 enters/exits 全空、虚拟边一条都生成不出来。
        List<JsonNode> elements = new ArrayList<>();
        for (JsonNode el : scene.path("elements")) {
            if (!"frame".equals(el.path("type").asText())) {
                elements.add(el);
            }
        }
        java.lang.reflect.Method m = ScenePlanner.class
                .getDeclaredMethod("partition", List.class);
        m.setAccessible(true);
        return m.invoke(new ScenePlanner(), elements);
    }

    private static List<?> blocksOf(Object partition) throws Exception {
        Field f = partition.getClass().getDeclaredField("root");
        f.setAccessible(true);
        Object root = f.get(partition);
        Field c = root.getClass().getDeclaredField("children");
        c.setAccessible(true);
        return (List<?>) c.get(root);
    }

    private static Set<String> shapeIds(Object block) throws Exception {
        Field f = block.getClass().getDeclaredField("shapes");
        f.setAccessible(true);
        Set<String> ids = new LinkedHashSet<>();
        for (Object shape : (List<?>) f.get(block)) {
            Field idf = shape.getClass().getDeclaredField("id");
            idf.setAccessible(true);
            ids.add((String) idf.get(shape));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static List<JsonNode> invokeSpine(Object block, Set<String> own,
            Object partition, List<Object> siblings) throws Exception {
        Field f = block.getClass().getDeclaredField("shapes");
        f.setAccessible(true);
        List<Object> shapes = (List<Object>) f.get(block);
        java.lang.reflect.Method m = ScenePlanner.class.getDeclaredMethod(
                "virtualSpineArrows", List.class, Set.class,
                partition.getClass(), List.class);
        m.setAccessible(true);
        return (List<JsonNode>) m.invoke(new ScenePlanner(), shapes, own,
                partition, new ArrayList<>(siblings));
    }
}
