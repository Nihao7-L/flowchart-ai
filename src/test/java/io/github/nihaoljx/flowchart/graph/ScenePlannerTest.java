package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.LayoutEngine;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;

/**
 * 排版层（v2-39 / v2-40 P1）的回归。
 *
 * <p>两组断言，分工明确：
 * <ul>
 *   <li><b>零回归</b>：小图 / 编辑模式 / 无分组必须与"没有排版这一步"的输出
 *       <b>逐字节一致</b> —— 这是本次改造最容易被悄悄破坏的东西；</li>
 *   <li><b>新行为</b>：多模块时的分区、frame 归属、异常宽容。</li>
 * </ul>
 */
class ScenePlannerTest {

    private final ScenePlanner planner = new ScenePlanner();

    private final LayoutEngine engine = new ElkLayoutEngine();

    private final ObjectMapper mapper = new ObjectMapper();

    // ==================== 零回归 ====================

    @Test
    @DisplayName("图形数 ≤ 阈值：与单层引擎输出逐字节一致")
    void smallSceneIsUntouched() {
        String json = sceneOf(
                rect("a", "开始", null),
                rect("b", "结束", null),
                arrow("e1", "a", "b"));
        assertEquals(engine.layout(json, Set.of()),
                planner.arrange(json, engine, Set.of()),
                "小图不该被排版碰一下");
    }

    @Test
    @DisplayName("编辑模式（有冻结 id）：排版不介入，输出与单层引擎一致")
    void editModeIsUntouched() {
        String json = modularScene();
        Set<String> frozen = new LinkedHashSet<>();
        frozen.add("a1");
        assertEquals(engine.layout(json, frozen),
                planner.arrange(json, engine, frozen),
                "编辑模式下既有坐标一像素都不许动");
    }

    @Test
    @DisplayName("复杂图但完全没有 group：退回单层布局")
    void sceneWithoutGroupFallsBack() {
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            elements.add(rect("n" + i, "节点" + i, null));
            if (i > 0) {
                elements.add(arrow("e" + i, "n" + (i - 1), "n" + i));
            }
        }
        String json = sceneOf(elements.toArray(new String[0]));
        assertEquals(engine.layout(json, Set.of()),
                planner.arrange(json, engine, Set.of()),
                "没有分组信息时不该凭空分区");
    }

    @Test
    @DisplayName("只有一个模块：不分区（模块数 < 2）")
    void singleModuleFallsBack() {
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            elements.add(rect("n" + i, "节点" + i, quote("唯一模块")));
        }
        String json = sceneOf(elements.toArray(new String[0]));
        assertEquals(engine.layout(json, Set.of()),
                planner.arrange(json, engine, Set.of()),
                "只有一个模块 = 没有分区可言");
    }

    // ==================== 分区行为 ====================

    @Test
    @DisplayName("多模块：同模块成员聚成一块，模块之间互不重叠")
    void modulesArePackedApart() throws Exception {
        String laid = planner.arrange(modularScene(), engine, Set.of());
        Map<String, double[]> boxes = moduleBoxes(laid);
        assertEquals(3, boxes.size(), "应有三个模块");
        List<String> names = new ArrayList<>(boxes.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                assertFalse(overlaps(boxes.get(names.get(i)),
                                boxes.get(names.get(j))),
                        "模块「" + names.get(i) + "」与「" + names.get(j)
                                + "」的包围盒相叠");
            }
        }
    }

    @Test
    @DisplayName("多模块：整体长宽比落在 [0.5, 2]（竖排一根窄条的问题已解）")
    void overallAspectIsReasonable() throws Exception {
        String laid = planner.arrange(modularScene(), engine, Set.of());
        double[] box = shapeBounds(laid);
        double aspect = (box[2] - box[0]) / (box[3] - box[1]);
        assertTrue(aspect >= 0.5 && aspect <= 2.0,
                "长宽比应落在 [0.5, 2]，实际 " + aspect);
    }

    @Test
    @DisplayName("排版是确定性的：同一份输入两次结果完全一致")
    void layoutIsDeterministic() {
        String json = modularScene();
        assertEquals(planner.arrange(json, engine, Set.of()),
                planner.arrange(json, engine, Set.of()),
                "两次结果不一致 —— SceneDrift 与 E2E 都无从谈起");
    }

    @Test
    @DisplayName("坐标仍吸附在 20px 网格上（保留审计判据）")
    void coordinatesStayOnGrid() throws Exception {
        String laid = planner.arrange(modularScene(), engine, Set.of());
        for (JsonNode element : mapper.readTree(laid).get("elements")) {
            if (!"rectangle".equals(element.path("type").asText())) {
                continue;
            }
            for (String field : new String[] {"x", "y"}) {
                double value = element.get(field).asDouble();
                assertEquals(0,
                        Math.abs(value / 20.0 - Math.round(value / 20.0)),
                        1e-9,
                        element.path("id").asText() + "." + field
                                + " 未落在网格上：" + value);
            }
        }
    }

    // ==================== frame 归属（v2-40 P1） ====================

    @Test
    @DisplayName("frame 与模块同名：框对齐模块、写入 children、且框住全部成员")
    void frameFollowsItsModule() throws Exception {
        String laid = planner.arrange(modularSceneWithFrames(),
                engine, Set.of());
        JsonNode frame = findFrame(laid, "订单域");
        assertNotNull(frame, "应当存在名为「订单域」的 frame");
        JsonNode children = frame.get("children");
        assertNotNull(children, "frame 必须写上 children（归属的唯一途径）");
        assertEquals(5, children.size(),
                "订单域的 5 个图形都应成为它的成员");

        double fx = frame.get("x").asDouble();
        double fy = frame.get("y").asDouble();
        double fw = frame.get("width").asDouble();
        double fh = frame.get("height").asDouble();
        for (JsonNode element : mapper.readTree(laid).get("elements")) {
            if (!"rectangle".equals(element.path("type").asText())
                    || !"订单域".equals(
                            element.path("group").asText(""))) {
                continue;
            }
            double cx = element.get("x").asDouble()
                    + element.get("width").asDouble() / 2;
            double cy = element.get("y").asDouble()
                    + element.get("height").asDouble() / 2;
            assertTrue(cx > fx && cx < fx + fw
                            && cy > fy && cy < fy + fh,
                    "图形 " + element.get("id").asText()
                            + " 应落在「订单域」框内");
        }
    }

    @Test
    @DisplayName("多个 frame：两两不重叠（实测缺陷 5 对的回归锁）")
    void framesDoNotOverlap() throws Exception {
        String laid = planner.arrange(modularSceneWithFrames(),
                engine, Set.of());
        List<double[]> boxes = new ArrayList<>();
        for (JsonNode element : mapper.readTree(laid).get("elements")) {
            if (!"frame".equals(element.path("type").asText())) {
                continue;
            }
            boxes.add(new double[] {
                element.get("x").asDouble(),
                element.get("y").asDouble(),
                element.get("width").asDouble(),
                element.get("height").asDouble(),
            });
        }
        assertEquals(3, boxes.size(), "基准场景有三个 frame");
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                assertFalse(overlaps(boxes.get(i), boxes.get(j)),
                        "frame 相叠：第 " + i + " 个与第 " + j + " 个");
            }
        }
    }

    @Test
    @DisplayName("frame 的名字匹配不上任何模块：原样不动（不猜成员）")
    void unmatchedFrameIsLeftAlone() throws Exception {
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            elements.add(rect("n" + i, "节点" + i,
                    i < 8 ? quote("模块甲") : quote("模块乙")));
        }
        elements.add(frame("f-orphan", "谁也不是的框"));
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        JsonNode orphan = findFrame(laid, "谁也不是的框");
        assertNotNull(orphan);
        assertEquals(0, orphan.get("x").asDouble(), 1e-9,
                "匹配不上的框应保持种子坐标");
        assertTrue(orphan.get("children") == null
                        || orphan.get("children").isEmpty(),
                "匹配不上的框不该被塞成员");
    }

    // ==================== ② 聚类兜底（v2-39 5.2） ====================

    @Test
    @DisplayName("兜底·拓扑：无 group 但分成两块互不相连 → 按连通分量分区")
    void infersModulesFromTopology() throws Exception {
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            elements.add(rect("a" + i, "节点" + i, null));
            if (i > 0) {
                elements.add(arrow("ea" + i, "a" + (i - 1), "a" + i));
            }
        }
        for (int i = 0; i < 8; i++) {
            elements.add(rect("b" + i, "节点" + i, null));
            if (i > 0) {
                elements.add(arrow("eb" + i, "b" + (i - 1), "b" + i));
            }
        }
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());

        // 两块应各占一方（模块名是兜底生成的「模块N」，只能按 id 前缀验）
        assertFalse(overlaps(idPrefixBounds(laid, "a"),
                        idPrefixBounds(laid, "b")),
                "两个连通分量应被排成互不重叠的两块");
        assertFalse(laid.equals(engine.layout(
                        sceneOf(elements.toArray(new String[0])), Set.of())),
                "兜底分区应当真的生效（否则这条测试等于没测）");
    }

    @Test
    @DisplayName("兜底·标签：连通成一片但标签有公共前缀 → 按「订单-创建」聚成模块")
    void infersModulesFromLabelPrefix() throws Exception {
        List<String> elements = new ArrayList<>();
        String[] prefixes = {"订单", "支付"};
        String[] letters = {"a", "b"};
        for (int g = 0; g < prefixes.length; g++) {
            for (int i = 0; i < 7; i++) {
                elements.add(rect(letters[g] + i,
                        prefixes[g] + "-" + i, null));
            }
        }
        // 全图连通（一条链穿过两组）→ 连通分量只有一个，只能靠标签前缀
        for (int i = 1; i < 14; i++) {
            String from = i <= 7
                    ? "a" + (i - 1) : "b" + (i - 8);
            String to = i < 7 ? "a" + i : "b" + (i - 7);
            elements.add(arrow("e" + i, from, to));
        }
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        assertFalse(overlaps(idPrefixBounds(laid, "a"),
                        idPrefixBounds(laid, "b")),
                "「订单」与「支付」两组应各占一方");
    }

    @Test
    @DisplayName("兜底不成形：标签无分隔符 → 退回单层布局（逐字节一致）")
    void unshapedClustersFallBack() {
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            elements.add(rect("n" + i, "节点" + i, null));
            if (i > 0) {
                elements.add(arrow("e" + i, "n" + (i - 1), "n" + i));
            }
        }
        String json = sceneOf(elements.toArray(new String[0]));
        assertEquals(engine.layout(json, Set.of()),
                planner.arrange(json, engine, Set.of()),
                "聚不出成形分区时不该硬排");
    }

    @Test
    @DisplayName("兜底有 frame 时不启用：无名模块认领不了框，硬排反而把框甩开")
    void inferenceIsSkippedWhenFramesExist() {
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            elements.add(rect("a" + i, "节点" + i, null));
            if (i > 0) {
                elements.add(arrow("ea" + i, "a" + (i - 1), "a" + i));
            }
        }
        for (int i = 0; i < 8; i++) {
            elements.add(rect("b" + i, "节点" + i, null));
            if (i > 0) {
                elements.add(arrow("eb" + i, "b" + (i - 1), "b" + i));
            }
        }
        elements.add(frame("f-x", "某个子流程"));
        String json = sceneOf(elements.toArray(new String[0]));
        assertEquals(engine.layout(json, Set.of()),
                planner.arrange(json, engine, Set.of()),
                "场景里有 frame 时不做几何兜底（否则框与内容必然错位）");
    }

    // ==================== 异常宽容 ====================

    @Test
    @DisplayName("group 数字 / 空串 / 超长 / 混合数组：归一化或忽略，不抛异常")
    void malformedGroupNeverThrows() throws Exception {
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            elements.add(rect("n" + i, "节点" + i, quote("正常模块")));
        }
        elements.add(rawRect("bad1", "数字", "123"));
        elements.add(rawRect("bad2", "空串", "\"  \""));
        elements.add(rawRect("bad3", "超长",
                quote("很长很长的模块名字很长很长的模块名字很长很长的模块名字")));
        elements.add(rawRect("bad4", "混合数组",
                "[\"子模块\",123,null,\"父模块\"]"));
        String json = sceneOf(elements.toArray(new String[0]));
        assertDoesNotThrow(() -> planner.arrange(json, engine, Set.of()));
        // 归一化后仍然是合法场景：所有图形都拿到了坐标
        String laid = planner.arrange(json, engine, Set.of());
        for (JsonNode element : mapper.readTree(laid).get("elements")) {
            if ("rectangle".equals(element.path("type").asText())) {
                assertTrue(element.get("x").isNumber(),
                        "每个图形都应有数字坐标");
            }
        }
    }

    @Test
    @DisplayName("嵌套模块（数组 group）：子模块各自成块、互不重叠")
    void nestedModulesAreSupported() throws Exception {
        List<String> elements = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            elements.add(rect("oa" + i, "下单" + i,
                    "[\"下单\",\"订单域\"]"));
        }
        for (int i = 0; i < 6; i++) {
            elements.add(rect("ob" + i, "查询" + i,
                    "[\"查询\",\"订单域\"]"));
        }
        for (int i = 0; i < 6; i++) {
            elements.add(rect("u" + i, "用户" + i, quote("用户域")));
        }
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        JsonNode elements2 = mapper.readTree(laid).get("elements");
        assertEquals(18, elements2.size());

        double[] order = idPrefixBounds(laid, "oa");
        double[] query = idPrefixBounds(laid, "ob");
        double[] user = idPrefixBounds(laid, "u");
        assertFalse(overlaps(order, query), "「下单」与「查询」应各占一块");
        assertFalse(overlaps(order, user), "「下单」与「用户域」应各占一块");
        assertFalse(overlaps(query, user), "「查询」与「用户域」应各占一块");
    }

    // ==================== 场景构造 ====================

    /** 三个模块 × 5 个图形 + 模块内箭头（15 个图形 > 阈值 15） */
    private static String modularScene() {
        return sceneOf(modularElements().toArray(new String[0]));
    }

    /** 同上，外加三个与模块同名的 frame */
    private static String modularSceneWithFrames() {
        List<String> elements = new ArrayList<>();
        elements.add(frame("f-order", "订单域"));
        elements.add(frame("f-user", "用户域"));
        elements.add(frame("f-pay", "支付域"));
        elements.addAll(modularElements());
        return sceneOf(elements.toArray(new String[0]));
    }

    private static List<String> modularElements() {
        List<String> elements = new ArrayList<>();
        String[] groups = {"订单域", "用户域", "支付域"};
        String[] prefixes = {"a", "b", "c"};
        for (int g = 0; g < groups.length; g++) {
            for (int i = 0; i < 5; i++) {
                elements.add(rect(prefixes[g] + i, groups[g] + i,
                        quote(groups[g])));
                if (i > 0) {
                    elements.add(arrow("e" + prefixes[g] + i,
                            prefixes[g] + (i - 1), prefixes[g] + i));
                }
            }
            if (g > 0) {
                elements.add(arrow("x" + g,
                        prefixes[g - 1] + "0", prefixes[g] + "0"));
            }
        }
        return elements;
    }

    private static String sceneOf(String... elements) {
        return "{\"elements\":[" + String.join(",", elements) + "]}";
    }

    private static String quote(String raw) {
        return "\"" + raw + "\"";
    }

    /**
     * 一个图形。
     *
     * @param group 原始 JSON 片段（{@code null} = 不写该字段），
     *              纯文本模块名要用 {@link #quote(String)} 包一层
     */
    private static String rect(String id, String label, String group) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(id)
                .append("\",\"type\":\"rectangle\",\"x\":0,\"y\":0,")
                .append("\"width\":160,\"height\":60,")
                .append("\"label\":{\"text\":\"").append(label)
                .append("\"}");
        if (group != null) {
            sb.append(",\"group\":").append(group);
        }
        return sb.append("}").toString();
    }

    private static String rawRect(String id, String label, String groupRaw) {
        return rect(id, label, groupRaw);
    }

    private static String arrow(String id, String from, String to) {
        return "{\"id\":\"" + id
                + "\",\"type\":\"arrow\",\"start\":{\"id\":\"" + from
                + "\"},\"end\":{\"id\":\"" + to + "\"}}";
    }

    private static String frame(String id, String name) {
        return "{\"id\":\"" + id
                + "\",\"type\":\"frame\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":100,\"name\":\"" + name + "\"}";
    }

    // ==================== 断言小工具 ====================

    /** 按 {@code group} 文本分组，算各模块的包围盒 */
    private Map<String, double[]> moduleBoxes(String json)
            throws Exception {
        Map<String, double[]> boxes = new LinkedHashMap<>();
        for (JsonNode element : mapper.readTree(json).get("elements")) {
            if (!"rectangle".equals(element.path("type").asText())) {
                continue;
            }
            JsonNode group = element.get("group");
            if (group == null || !group.isTextual()) {
                continue;
            }
            double[] box = boxes.get(group.asText());
            if (box == null) {
                box = new double[] {
                    Double.MAX_VALUE, Double.MAX_VALUE,
                    -Double.MAX_VALUE, -Double.MAX_VALUE,
                };
                boxes.put(group.asText(), box);
            }
            double x = element.get("x").asDouble();
            double y = element.get("y").asDouble();
            box[0] = Math.min(box[0], x);
            box[1] = Math.min(box[1], y);
            box[2] = Math.max(box[2],
                    x + element.get("width").asDouble());
            box[3] = Math.max(box[3],
                    y + element.get("height").asDouble());
        }
        return boxes;
    }

    /** 按 id 前缀分组，算一组图形的包围盒 */
    private double[] idPrefixBounds(String json, String prefix)
            throws Exception {
        double[] box = {
            Double.MAX_VALUE, Double.MAX_VALUE,
            -Double.MAX_VALUE, -Double.MAX_VALUE,
        };
        for (JsonNode element : mapper.readTree(json).get("elements")) {
            if (!"rectangle".equals(element.path("type").asText())
                    || !element.path("id").asText().startsWith(prefix)) {
                continue;
            }
            double x = element.get("x").asDouble();
            double y = element.get("y").asDouble();
            box[0] = Math.min(box[0], x);
            box[1] = Math.min(box[1], y);
            box[2] = Math.max(box[2],
                    x + element.get("width").asDouble());
            box[3] = Math.max(box[3],
                    y + element.get("height").asDouble());
        }
        return box;
    }

    /** 全部图形的整体包围盒 */
    private double[] shapeBounds(String json) throws Exception {
        double[] box = {
            Double.MAX_VALUE, Double.MAX_VALUE,
            -Double.MAX_VALUE, -Double.MAX_VALUE,
        };
        for (JsonNode element : mapper.readTree(json).get("elements")) {
            if (!"rectangle".equals(element.path("type").asText())) {
                continue;
            }
            double x = element.get("x").asDouble();
            double y = element.get("y").asDouble();
            box[0] = Math.min(box[0], x);
            box[1] = Math.min(box[1], y);
            box[2] = Math.max(box[2],
                    x + element.get("width").asDouble());
            box[3] = Math.max(box[3],
                    y + element.get("height").asDouble());
        }
        return box;
    }

    private static boolean overlaps(double[] a, double[] b) {
        return a[0] < b[2] && b[0] < a[2]
                && a[1] < b[3] && b[1] < a[3];
    }

    private JsonNode findFrame(String json, String name)
            throws Exception {
        for (JsonNode element : mapper.readTree(json).get("elements")) {
            if ("frame".equals(element.path("type").asText())
                    && name.equals(element.path("name").asText())) {
                return element;
            }
        }
        return null;
    }

    // ==================== v2-41 原生坐标模式 ====================

    @Test
    @DisplayName("v2-41 原生坐标：claimFramesOnly 填 frame.children，但不动任何几何")
    void claimFramesOnlyFillsChildrenKeepsGeometry()
            throws Exception {
        // LLM 原生坐标：一个 frame 把两个同 group 图形包在里面
        String json = sceneOf(
                frame("f-order", "订单域"),
                rect("o1", "创建订单", quote("订单域")),
                rect("o2", "支付订单", quote("订单域")),
                arrow("e1", "o1", "o2"));

        String out = planner.claimFramesOnly(json);

        // 1) frame.children 被后端认领填上成员（拖动画框能带上内容）
        JsonNode frame = findFrame(out, "订单域");
        assertNotNull(frame, "应存在「订单域」框");
        JsonNode children = frame.get("children");
        assertNotNull(children, "frame 必须填上 children");
        assertEquals(2, children.size(),
                "children 应含两个同模块图形");

        // 2) 几何原样保留（不重排）：frame 与图形的 x/y/width/height 与输入一致
        JsonNode in = mapper.readTree(json);
        for (JsonNode el : mapper.readTree(out).get("elements")) {
            String id = el.path("id").asText();
            JsonNode src = null;
            for (JsonNode s : in.get("elements")) {
                if (id.equals(s.path("id").asText())) {
                    src = s;
                }
            }
            assertNotNull(src, "输出不应多出元素: " + id);
            for (String f : new String[]{"x", "y", "width", "height"}) {
                if (src.has(f)) {
                    assertEquals(src.get(f).asDouble(),
                            el.get(f).asDouble(), 0.001,
                            "原生坐标模式不应改写 " + id + "." + f);
                }
            }
        }
    }
}
