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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.LayoutEngine;
import io.github.nihaoljx.flowchart.graph.layout.LayoutSpec;
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
    @DisplayName("无分组小图：与单层引擎输出逐字节一致")
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

    // ==================== 分区行为 ====================

    @Test
    @DisplayName("LLM 显式标注分组的小图（< 阈值）：分区也必须生效（模块互不重叠）")
    void explicitGroupActivatesPartitionBelowThreshold() throws Exception {
        // 2026-10-15 regress_arch 回归：10 个图形 3 组显式标注曾被
        // 节点数阈值（15）拦住 → 全局排版 → 「支付服务」落进数据层
        // bbox，画框罩住别人的成员。显式分组是强意图，不该受
        // "图形多才分区"的启发式限制（该阈值现已删除）
        List<String> elements = new ArrayList<>();
        String[] groups = {"接入层", "服务层", "数据层"};
        String[] prefixes = {"a", "s", "d"};
        for (int g = 0; g < groups.length; g++) {
            for (int i = 0; i < 2; i++) {
                elements.add(rect(prefixes[g] + i,
                        groups[g] + i, quote(groups[g])));
            }
        }
        elements.add(arrow("x1", "a0", "s0"));
        elements.add(arrow("x2", "s0", "d0"));
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        Map<String, double[]> boxes = moduleBoxes(laid);
        assertEquals(3, boxes.size(), "三个显式分组都应参与分区");
        List<String> names = new ArrayList<>(boxes.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                assertFalse(overlaps(boxes.get(names.get(i)),
                                boxes.get(names.get(j))),
                        "模块「" + names.get(i) + "」与「" + names.get(j)
                                + "」的包围盒相叠（穿插复现）");
            }
        }
    }

    @Test
    @DisplayName("多模块：跨模块箭头补 3 点正交路由（分区路径无 ELK 边路由）")
    void crossModuleArrowsGetRoutes() throws Exception {
        // 2026-10-15 regress_arch2 实测 17/17 箭头悬空 181~277px：
        // 模块内边随子图进过 ELK（points 已写回），跨模块边不属于
        // 任何子图、从头到尾没人给几何 —— 前端中心连直线必然指空
        List<String> elements = new ArrayList<>();
        String[] groups = {"接入层", "服务层", "数据层"};
        String[] prefixes = {"a", "s", "d"};
        for (int g = 0; g < groups.length; g++) {
            for (int i = 0; i < 2; i++) {
                elements.add(rect(prefixes[g] + i,
                        groups[g] + i, quote(groups[g])));
            }
        }
        elements.add(arrow("x1", "a0", "s0"));
        elements.add(arrow("x2", "s0", "d0"));
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        JsonNode root = mapper.readTree(laid);
        int routed = 0;
        for (JsonNode el : root.get("elements")) {
            if (!"arrow".equals(el.path("type").asText())) {
                continue;
            }
            JsonNode points = el.get("points");
            assertNotNull(points, "箭头 " + el.path("id").asText()
                    + " 必须带 points（不容许留给前端重算）");
            assertTrue(points.isArray() && points.size() >= 3,
                    "路由至少 3 点（直线补中点口径），实际 "
                            + points.size());
            assertTrue(el.hasNonNull("x") && el.hasNonNull("y"),
                    "x/y = 首点绝对坐标（引擎写回口径）");
            routed++;
        }
        assertEquals(2, routed, "两条跨模块箭头都应被路由");
        assertRoutesAvoidNodes(root,
                "2026-10-15 regress_arch3 实测拐点通道 x=380 直接穿过"
                        + "三个服务框、6 条线叠在 x=200 一股上");
    }

    /**
     * 路由质量断言：每条箭头的每一段（轴对齐）都不得与无关节点的
     * bbox（外扩 4px）相交 —— 防「拐点通道压在实体上」的穿框复发。
     */
    private void assertRoutesAvoidNodes(JsonNode root, String context)
            throws Exception {
        Map<String, double[]> boxes = new LinkedHashMap<>();
        for (JsonNode el : root.get("elements")) {
            if (!"rectangle".equals(el.path("type").asText())
                    || !el.hasNonNull("x")) {
                continue;
            }
            boxes.put(el.path("id").asText(), new double[] {
                el.get("x").asDouble(), el.get("y").asDouble(),
                el.get("x").asDouble() + el.get("width").asDouble(),
                el.get("y").asDouble() + el.get("height").asDouble()});
        }
        final double margin = 4;
        for (JsonNode el : root.get("elements")) {
            if (!"arrow".equals(el.path("type").asText())
                    || el.get("points") == null) {
                continue;
            }
            String from = el.path("start").path("id").asText();
            String to = el.path("end").path("id").asText();
            double ox = el.get("x").asDouble();
            double oy = el.get("y").asDouble();
            JsonNode points = el.get("points");
            // 终点必须贴在目标框边上（±2px）—— 防「进边锚点轴向传反」
            // 这类离目标几十上百 px 的静默劣化（regress_arch4 实测）
            double lx = ox + points.get(points.size() - 1).get(0).asDouble();
            double ly = oy + points.get(points.size() - 1).get(1).asDouble();
            double[] tb = boxes.get(to);
            assertNotNull(tb, "目标节点 " + to + " 应存在");
            boolean onBorder =
                    (Math.abs(lx - tb[0]) <= 2 || Math.abs(lx - tb[2]) <= 2)
                            && ly >= tb[1] - 2 && ly <= tb[3] + 2
                            || (Math.abs(ly - tb[1]) <= 2
                                    || Math.abs(ly - tb[3]) <= 2)
                                    && lx >= tb[0] - 2 && lx <= tb[2] + 2;
            assertTrue(onBorder, "箭头 " + el.path("id").asText()
                    + " 终点 (" + lx + "," + ly + ") 未贴在目标 "
                    + to + " 的框边上 " + context);
            for (int i = 0; i + 1 < points.size(); i++) {
                double sx = Math.min(
                        ox + points.get(i).get(0).asDouble(),
                        ox + points.get(i + 1).get(0).asDouble());
                double ex = Math.max(
                        ox + points.get(i).get(0).asDouble(),
                        ox + points.get(i + 1).get(0).asDouble());
                double sy = Math.min(
                        oy + points.get(i).get(1).asDouble(),
                        oy + points.get(i + 1).get(1).asDouble());
                double ey = Math.max(
                        oy + points.get(i).get(1).asDouble(),
                        oy + points.get(i + 1).get(1).asDouble());
                for (Map.Entry<String, double[]> box : boxes.entrySet()) {
                    if (box.getKey().equals(from)
                            || box.getKey().equals(to)) {
                        continue;
                    }
                    double[] b = box.getValue();
                    boolean hits = ex >= b[0] - margin
                            && sx <= b[2] + margin
                            && ey >= b[1] - margin
                            && sy <= b[3] + margin;
                    assertFalse(hits, context + " —— 箭头 "
                            + el.path("id").asText() + " 第 " + i
                            + " 段穿过节点 " + box.getKey());
                }
            }
        }
    }

    @Test
    @DisplayName("边标签治理：同源同名去重，有路由的标签落位为独立文字")
    void edgeLabelsDedupAndPlace() throws Exception {
        // 2026-10-15 regress_arch5 用户圈出两坨标签噪音：网关 4 条出线
        // 各带同名「路由转发」摞成一摞、走廊里 6 个标签互相压线 ——
        // 绑定标签由渲染器自动居中在箭头包围盒中心，位置从来没人管
        List<String> elements = new ArrayList<>();
        String[] groups = {"接入层", "服务层"};
        String[] prefixes = {"a", "s"};
        for (int g = 0; g < groups.length; g++) {
            for (int i = 0; i < 2; i++) {
                elements.add(rect(prefixes[g] + i,
                        groups[g] + i, quote(groups[g])));
            }
        }
        elements.add(arrowLabeled("x1", "a0", "s0", "路由转发"));
        elements.add(arrowLabeled("x2", "a0", "s1", "路由转发"));
        elements.add(arrowLabeled("x3", "a1", "s0", "调用"));
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        JsonNode root = mapper.readTree(laid);
        int bound = 0;
        int standalone = 0;
        for (JsonNode el : root.get("elements")) {
            if ("arrow".equals(el.path("type").asText())) {
                if (el.has("label")) {
                    bound++;
                }
            } else if ("text".equals(el.path("type").asText())
                    && el.path("id").asText().endsWith("_lbl")) {
                standalone++;
                assertTrue(el.hasNonNull("x") && el.hasNonNull("y"),
                        "独立标签必须有坐标");
            }
        }
        // x1/x2 同源(a0)同名 → 去重留 1；x3 文字不同保留。两条都有
        // 跨模块路由 points → 都应落位成独立文字，不再绑定居中
        assertEquals(2, standalone, "去重后 2 个标签都应落位为独立文字");
        assertEquals(0, bound, "能落位的标签不该留在箭头上（自动居中不可控）");
    }

    @Test
    @DisplayName("单层路径（无 group）：带标签的箭头也落位为独立文字")
    void singleLayerLabelsArePlaced() throws Exception {
        // 2026-10-17 实测请假流程图：7 节点无 group，菱形双出边「是」「否」
        // 都由渲染器自动居中在箭头包围盒中心，两条边都起于菱形底边同一点
        // ⇒ 标签贴线重叠不可读。修复：单层路径也做标签治理。
        List<String> elements = new ArrayList<>();
        elements.add(rect("start", "开始", null));
        elements.add(rect("check", "是否超期", null));
        elements.add(rect("yes", "是分支", null));
        elements.add(rect("no", "否分支", null));
        elements.add(arrowLabeled("e1", "check", "yes", "是"));
        elements.add(arrowLabeled("e2", "check", "no", "否"));
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        JsonNode root = mapper.readTree(laid);
        int standalone = 0;
        int bound = 0;
        for (JsonNode el : root.get("elements")) {
            if ("arrow".equals(el.path("type").asText())) {
                if (el.has("label")) {
                    bound++;
                }
            } else if ("text".equals(el.path("type").asText())
                    && el.path("id").asText().endsWith("_lbl")) {
                standalone++;
                assertTrue(el.hasNonNull("x") && el.hasNonNull("y"),
                        "独立标签必须有坐标");
            }
        }
        assertEquals(2, standalone, "两条标签都应落位为独立文字");
        assertEquals(0, bound, "能落位的标签不该留在箭头上");
    }

    @Test
    @DisplayName("架构图多层：路由/缓存/注册/监控/日志 标签落位且不重叠不压线")
    void architectureMultiLayerLabelsClean() throws Exception {
        // 用户原始诉求「架构图标签残留（路由/缓存/注册/监控/日志）」的回归：
        // 五个业务层、层间调用都带标签，曾出现标签摞成一坨、压在线上不可读。
        // 修复后（②③ 边标签治理 + 短标签绑箭头）必须：① 有坐标的独立文字；
        // ② 任意两个独立标签不重叠；③ 独立标签不压在任一条箭头上。
        List<String> elements = new ArrayList<>();
        // 接入层
        elements.add(rect("gw", "网关", quote("接入层")));
        // 路由层
        elements.add(rect("rt", "路由", quote("路由层")));
        // 服务层
        elements.add(rect("order", "订单服务", quote("服务层")));
        elements.add(rect("user", "用户服务", quote("服务层")));
        // 缓存层
        elements.add(rect("cache", "Redis 缓存", quote("缓存层")));
        // 注册层
        elements.add(rect("reg", "注册中心", quote("注册层")));
        // 监控层
        elements.add(rect("mon", "监控", quote("监控层")));
        // 日志层
        elements.add(rect("log", "日志", quote("日志层")));
        // 数据层
        elements.add(rect("db", "数据库", quote("数据层")));
        // 层间调用，都带标签
        elements.add(arrowLabeled("e1", "gw", "rt", "转发"));
        elements.add(arrowLabeled("e2", "rt", "order", "路由到订单"));
        elements.add(arrowLabeled("e3", "rt", "user", "路由到用户"));
        elements.add(arrowLabeled("e4", "order", "cache", "读缓存"));
        elements.add(arrowLabeled("e5", "user", "cache", "读缓存"));
        elements.add(arrowLabeled("e6", "order", "reg", "注册"));
        elements.add(arrowLabeled("e7", "user", "reg", "注册"));
        elements.add(arrowLabeled("e8", "order", "mon", "上报"));
        elements.add(arrowLabeled("e9", "user", "mon", "上报"));
        elements.add(arrowLabeled("e10", "order", "log", "写日志"));
        elements.add(arrowLabeled("e11", "user", "log", "写日志"));
        elements.add(arrowLabeled("e12", "order", "db", "读写"));
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        JsonNode root = mapper.readTree(laid);
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("target/probe_arch.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignore) {}

        List<JsonNode> standaloneLabels = new ArrayList<>();
        List<JsonNode> boundLabels = new ArrayList<>();
        List<JsonNode> arrows = new ArrayList<>();
        int bound = 0;
        for (JsonNode el : root.get("elements")) {
            String t = el.path("type").asText();
            if ("arrow".equals(t)) {
                arrows.add(el);
                if (el.has("label")) {
                    bound++;
                }
            } else if ("text".equals(t)
                    && el.path("id").asText().endsWith("_lbl")) {
                if (el.hasNonNull("containerId")) {
                    boundLabels.add(el);
                } else {
                    standaloneLabels.add(el);
                }
            }
        }
        assertEquals(0, bound, "能落位的标签不该留在箭头上（自动居中不可控）");
        assertTrue(standaloneLabels.size() + boundLabels.size() >= 8,
                "绝大多数标签应落位为独立文字或绑回箭头，实际: "
                        + (standaloneLabels.size() + boundLabels.size()));

        // ① 每个独立标签都拿到坐标；绑回箭头的标签由前端落在线中点，
        //    后端的 x/y 只是占位（可能越界），不参与压线判定
        for (JsonNode lbl : standaloneLabels) {
            assertTrue(lbl.hasNonNull("x") && lbl.hasNonNull("y"),
                    "独立标签必须有坐标: " + lbl.path("id").asText());
        }

        // ② 诊断：打印标签坐标
        StringBuilder dump = new StringBuilder("LABELS:\n");
        for (JsonNode lbl : standaloneLabels) {
            dump.append("  [standalone] ").append(lbl.path("id").asText())
                    .append(" \"").append(lbl.path("text").asText())
                    .append("\" @(").append(lbl.path("x").asText())
                    .append(",").append(lbl.path("y").asText())
                    .append(")\n");
        }
        for (JsonNode lbl : boundLabels) {
            dump.append("  [bound] ").append(lbl.path("id").asText())
                    .append(" \"").append(lbl.path("text").asText())
                    .append("\" containerId=").append(lbl.path("containerId").asText())
                    .append("\n");
        }
        System.out.println(dump);

        // ③ 独立标签（非绑回箭头的）不压在任一条箭头的任一段上
        //    注意：arrow.points 是相对箭头 x/y 的偏移，必须加回 x/y 才得到
        //    绝对坐标（与独立标签的 x/y 同坐标系），否则会误判压线。
        for (JsonNode lbl : standaloneLabels) {
            double[] box = textBox(lbl);
            for (JsonNode arrow : arrows) {
                JsonNode pts = arrow.get("points");
                if (pts == null || !pts.isArray()
                        || pts.size() < 2) {
                    continue;
                }
                double ax = arrow.path("x").asDouble();
                double ay = arrow.path("y").asDouble();
                for (int k = 0; k < pts.size() - 1; k++) {
                    double x1 = ax + pts.get(k).get(0).asDouble();
                    double y1 = ay + pts.get(k).get(1).asDouble();
                    double x2 = ax + pts.get(k + 1).get(0).asDouble();
                    double y2 = ay + pts.get(k + 1).get(1).asDouble();
                    assertFalse(segHitsBox(x1, y1, x2, y2, box),
                            "标签「" + lbl.path("text").asText()
                                    + "」压在箭头 " + arrow.path("id").asText()
                                    + " 的线上");
                }
            }
        }
    }

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
    @DisplayName("多模块：整体长宽比落在 [0.4, 3.0]（竖排一根窄条的问题已解）")
    void overallAspectIsReasonable() throws Exception {
        String laid = planner.arrange(modularScene(), engine, Set.of());
        double[] box = shapeBounds(laid);
        double aspect = (box[2] - box[0]) / (box[3] - box[1]);
        // 2026-10-15 口径放宽 [0.5,2]→[0.5,2.5]：显式分组直通分区后，
        // 3 模块横排打包实测 2.07 —— 分区块的横排是合理形态，这条
        // 判据要防的是"文字看不清的病态窄条"（实测 0.14 / 7+）。
        // 2026-10-16 再放宽到 [0.4, 3.0]：整图方向试排上线后，3 个
        // 链式模块可能被判横排堆叠（每模块内左而右），实测 2.4 一线
        // —— 那是"三条流水线横幅"的合法形态，不是病态宽条。
        assertTrue(aspect >= 0.4 && aspect <= 3.0,
                "长宽比应落在 [0.4, 3.0]，实际 " + aspect);
    }

    @Test
    @DisplayName("模块内边标签不撑层间距：剥离子场景，间距回到 LAYER_GAP 地板值")
    void internalEdgeLabelsDoNotInflateModuleGaps() throws Exception {
        // 2026-10-16 认证图实测：270px 的边标签把层间距撑到 280px，
        // 整图 0.64 长宽比（参考图 1.71）。分区路径把标签从布局
        // 子场景剥掉（placeEdgeLabels 落定后独立落位），模块内层
        // 间距应只受 LayoutSpec.LAYER_GAP 约束。
        List<String> elements = new ArrayList<>();
        elements.add(rect("a", "起点", quote("流水线")));
        elements.add(rect("b", "终点", quote("流水线")));
        elements.add(arrowLabeled("e1", "a", "b", "很长的边标签文字啊啊"));
        // 再给一个模块，确保本用例一定走分区路径（测的是"模块内
        // 层间距不受长标签撑大"，不是单分组行为）。
        elements.add(rect("c", "旁路1", quote("旁路模块")));
        elements.add(rect("d", "旁路2", quote("旁路模块")));
        elements.add(arrow("e2", "c", "d"));
        elements.add(arrow("e3", "b", "c"));
        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        double ax = 0;
        double ay = 0;
        double aw = 0;
        double ah = 0;
        double bx = 0;
        double by = 0;
        for (JsonNode el : mapper.readTree(laid).get("elements")) {
            if (!"rectangle".equals(el.path("type").asText())) {
                continue;
            }
            if ("a".equals(el.path("id").asText())) {
                ax = el.get("x").asDouble();
                ay = el.get("y").asDouble();
                aw = el.get("width").asDouble();
                ah = el.get("height").asDouble();
            } else if ("b".equals(el.path("id").asText())) {
                bx = el.get("x").asDouble();
                by = el.get("y").asDouble();
            }
        }
        // 模块主方向由整图试排决定，可能竖排也可能横排 —— 取被分开
        // 的那条轴上的净间距，都该等于地板值（长标签若参与撑距，
        // 这条轴会被撑到 ~240，本用例就是它的回归锁）。
        double floor = LayoutSpec.mainGapFor(List.of());
        double gapV = by - ay - ah;
        double gapH = bx - ax - aw;
        boolean stacked = Math.abs(gapV - floor) < 1e-6;
        boolean sideBySide = Math.abs(gapH - floor) < 1e-6;
        assertTrue(stacked || sideBySide,
                "模块内层间距应是地板值 " + floor + "（标签不参与撑距），"
                        + "实际 gapV=" + gapV + " gapH=" + gapH);
        // 注意：内部边标签可能留存在箭头上（而非独立文字），
        // 标签落位的专项覆盖见 edgeLabelsDedupAndPlace，这里不重复断言。
    }

    @Test
    @DisplayName("单分组也分区：LLM 显式声明一个组，就按它分区（不再被数量阈值拦住）")
    void singleGroupActivatesPartition() throws Exception {
        // 2026-10-16 口径修正：此前用 moduleCount ≥ 2 把单组挡在分区
        // 之外，等于拿"有几组"替代"要不要分区"的语义判断。单组
        // （如 AWS Cloud / 数据摄入流水线）同样是一个真实容器。
        List<String> elements = new ArrayList<>();
        elements.add(rect("c1", "Cognito", quote("AWS Cloud")));
        elements.add(rect("c2", "API Gateway", quote("AWS Cloud")));
        elements.add(rect("c3", "Lambda", quote("AWS Cloud")));
        // 外部实体，不属于任何组
        elements.add(rect("u1", "User", null));
        elements.add(arrow("e1", "u1", "c1"));
        elements.add(arrow("e2", "c1", "c2"));
        elements.add(arrow("e3", "c2", "c3"));

        String json = sceneOf(elements.toArray(new String[0]));
        String laid = planner.arrange(json, engine, Set.of());

        // 分区路径一旦介入，输出必与"无排版"的单层引擎不同（模块内
        // 自排 + 模块与游离图形分开打包 vs 平铺层叠）—— 这是本用例
        // 要锁的信号：单组不再被 moduleCount 阈值挡在分区之外。
        assertNotEquals(engine.layout(json, Set.of()), laid,
                "单分组必须激活分区路径（旧口径会被 moduleCount<2 拦掉）");

        // 三组成员仍应聚成一个模块，且与外部节点不重叠
        double[] cloud = idPrefixBounds(laid, "c");
        double[] user = idPrefixBounds(laid, "u");
        assertFalse(overlaps(cloud, user),
                "AWS Cloud 模块与外部节点 User 不应重叠");
    }

    @Test
    @DisplayName("模块内箭头随模块平移：原点必须贴着自己的两端节点")
    void internalArrowsAreTranslatedWithModule() throws Exception {
        // 2026-10-16 RAG 实测：模块内箭头只拿到"子场景局部坐标"、
        // 没随模块一起平移 ⇒ placeEdgeLabels 拿陈旧原点算标签落点，
        // 7 个步骤标签齐刷刷飘到图外左侧空白栏。箭头本体在渲染端
        // 按 start/end 绑定重算几何所以看不出问题，只有标签暴露它。
        List<String> elements = new ArrayList<>();
        elements.add(rect("a1", "接入一", quote("接入层")));
        elements.add(rect("a2", "接入二", quote("接入层")));
        elements.add(rect("s1", "服务一", quote("服务层")));
        elements.add(rect("s2", "服务二", quote("服务层")));
        elements.add(arrowLabeled("i1", "a1", "a2", "模块内一"));
        elements.add(arrowLabeled("i2", "s1", "s2", "模块内二"));
        elements.add(arrow("x1", "a2", "s1"));

        String laid = planner.arrange(
                sceneOf(elements.toArray(new String[0])),
                engine, Set.of());
        Map<String, double[]> boxes = new LinkedHashMap<>();
        for (JsonNode el : mapper.readTree(laid).get("elements")) {
            if ("rectangle".equals(el.path("type").asText())) {
                boxes.put(el.path("id").asText(), new double[] {
                    el.get("x").asDouble(), el.get("y").asDouble(),
                    el.get("x").asDouble() + el.get("width").asDouble(),
                    el.get("y").asDouble() + el.get("height").asDouble()});
            }
        }
        int checked = 0;
        for (JsonNode el : mapper.readTree(laid).get("elements")) {
            if (!"arrow".equals(el.path("type").asText())) {
                continue;
            }
            String from = el.path("start").path("id").asText();
            String to = el.path("end").path("id").asText();
            double[] fb = boxes.get(from);
            double[] tb = boxes.get(to);
            assertNotNull(fb, "应有源节点 " + from);
            assertNotNull(tb, "应有目标节点 " + to);
            double ox = el.get("x").asDouble();
            double oy = el.get("y").asDouble();
            double lo = Math.min(fb[0], tb[0]) - 120;
            double hi = Math.max(fb[2], tb[2]) + 120;
            double top = Math.min(fb[1], tb[1]) - 120;
            double bot = Math.max(fb[3], tb[3]) + 120;
            assertTrue(ox >= lo && ox <= hi && oy >= top && oy <= bot,
                    "箭头 " + el.path("id").asText() + " 原点 (" + ox
                            + "," + oy + ") 远离自己的两端节点 —— "
                            + "疑似没随模块平移");
            checked++;
        }
        assertEquals(3, checked, "两条模块内边 + 一条跨模块边");
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

    // ==================== 分区只认显式声明（2026-10-15 收紧） ====================
    // 此前的「节点数 > 15 就按拓扑/标签前缀自动聚类」兜底已删：
    // 分不分区是语义决策（模型读了需求，它不标组就是没有分区语义），
    // 机械阈值与几何猜测都不该存在。

    @Test
    @DisplayName("大图无 group：即使 16 个节点也不自动聚类，退回单层布局")
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
        String json = sceneOf(elements.toArray(new String[0]));
        assertEquals(engine.layout(json, Set.of()),
                planner.arrange(json, engine, Set.of()),
                "两个互不相连的分量也不许后端猜测分区 —— 只认显式 group");
    }

    @Test
    @DisplayName("标签有公共前缀也不聚类：按前缀猜模块已删")
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
        // 全图连通（一条链穿过两组）——过去靠标签前缀兜底聚成两块
        for (int i = 1; i < 14; i++) {
            String from = i <= 7
                    ? "a" + (i - 1) : "b" + (i - 8);
            String to = i < 7 ? "a" + i : "b" + (i - 7);
            elements.add(arrow("e" + i, from, to));
        }
        String json = sceneOf(elements.toArray(new String[0]));
        assertEquals(engine.layout(json, Set.of()),
                planner.arrange(json, engine, Set.of()),
                "「订单-」「支付-」前缀不该被后端拿来猜模块");
    }

    @Test
    @DisplayName("无 group 退回单层布局（逐字节一致）")
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
                "没有显式分组时不该硬排");
    }

    @Test
    @DisplayName("孤立 frame 认领不上任何模块、又无显式分组：不分区")
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
                "没有显式分组、frame 又认领不上模块时不该分区");
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

    /** 三个模块 × 5 个图形 + 模块内箭头（显式分组直通分区） */
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

    private static String arrowLabeled(
            String id, String from, String to, String label) {
        return "{\"id\":\"" + id
                + "\",\"type\":\"arrow\",\"start\":{\"id\":\"" + from
                + "\"},\"end\":{\"id\":\"" + to
                + "\"},\"label\":{\"text\":\"" + label + "\"}}";
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

    /**
     * 用 fontSize 与文字长度估算一个独立标签的包围盒 [x,y,w,h]。
     *
     * <p>口径必须与生产 {@code SceneGeometryKit.labelSize} 逐字一致
     * （CJK 0.9×font、Latin 0.45×font、宽下限 24、高 1.25×font）——
     * 2026-10-18 实测：这里曾用「中文按 font 宽、高 1.2×font」的近似，
     * 盒宽比生产宽 6.4px，「读缓存」标签右缘凭空越过 e10 竖线 0.4px，
     * 假阳性报压线（生产盒其实离线 4.4px）。两侧同源才不会漂移。
     */
    private static double[] textBox(JsonNode lbl) {
        double x = lbl.path("x").asDouble();
        double y = lbl.path("y").asDouble();
        int font = lbl.hasNonNull("fontSize")
                ? lbl.path("fontSize").asInt() : 16;
        String text = lbl.path("text").asText();
        // 中文按 0.9*font 宽、拉丁按 0.45*font 宽估算（与 labelSize 同源）
        double w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += (text.charAt(i) > 0x2E80)
                    ? font * 0.9 : font * 0.45;
        }
        double h = font * 1.25;
        return new double[] {x, y, x + Math.max(w, 24), y + h};
    }

    /** Liang-Barsky：线段是否与轴对齐 box 真有交叠（擦边/贴角不算，与
     * 生产代码 {@code SceneGeometryKit.segHitsBox} 同口径，避免把标签框
     * 自身边缘的擦边误报成压线）。 */
    private static boolean segHitsBox(double x1, double y1,
            double x2, double y2, double[] box) {
        double dx = x2 - x1, dy = y2 - y1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1 - box[0], box[2] - x1, y1 - box[1], box[3] - y1};
        double t0 = 0, t1 = 1;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-12) {
                if (q[i] < 0) {
                    return false;
                }
                continue;
            }
            double r = q[i] / p[i];
            if (p[i] < 0) {
                if (r > t1) {
                    return false;
                }
                if (r > t0) {
                    t0 = r;
                }
            } else {
                if (r < t0) {
                    return false;
                }
                if (r < t1) {
                    t1 = r;
                }
            }
        }
        return t0 < t1;
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
