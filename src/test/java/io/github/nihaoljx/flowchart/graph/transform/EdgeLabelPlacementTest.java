package io.github.nihaoljx.flowchart.graph.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nihaoljx.flowchart.graph.layout.ElkLayoutEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 边标签落位的整图判据：文字不能被别的线穿过。
 *
 * <p>用户口径「线条和文字全部叠在一起」的量化形式：标签外框若被别的
 * 箭头线段穿过就不可读（文字没有底色）。硬冲突仍是节点框与已放标签，
 * 但候选里必须优先挑「没线穿过」的那个 —— 这正是本轮修的。
 */
@DisplayName("ScenePlanner 边标签避线")
class EdgeLabelPlacementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("跨模块带标签箭头：标签不压节点、不被别的线穿过")
    void labelsAvoidOtherArrows() throws Exception {
        String laid = new ScenePlanner().arrange(
                modularSceneWithLabels(), new ElkLayoutEngine(), Set.of());
        JsonNode elements = MAPPER.readTree(laid).get("elements");

        List<double[]> nodeBoxes = new ArrayList<>();
        List<double[]> segs = new ArrayList<>();
        List<String> segOwners = new ArrayList<>();
        List<JsonNode> labels = new ArrayList<>();
        for (JsonNode el : elements) {
            String type = el.path("type").asText();
            if ("rectangle".equals(type)) {
                nodeBoxes.add(new double[] {
                    el.path("x").asDouble(), el.path("y").asDouble(),
                    el.path("x").asDouble() + el.path("width").asDouble(),
                    el.path("y").asDouble() + el.path("height").asDouble()});
            } else if ("arrow".equals(type)) {
                String id = el.path("id").asText();
                List<double[]> pts = absolutePoints(el);
                for (int i = 0; i + 1 < pts.size(); i++) {
                    segs.add(new double[] {pts.get(i)[0], pts.get(i)[1],
                        pts.get(i + 1)[0], pts.get(i + 1)[1]});
                    segOwners.add(id);
                }
            } else if ("text".equals(type)
                    && el.path("id").asText().endsWith("_lbl")) {
                labels.add(el);
            }
        }
        assertThat(labels).as("本场景应有独立边标签").isNotEmpty();

        for (JsonNode label : labels) {
            String content = label.path("text").asText();
            double[] size = SceneGeometryKit.labelSize(content, 16);
            double[] box = {
                label.path("x").asDouble(), label.path("y").asDouble(),
                label.path("x").asDouble() + size[0],
                label.path("y").asDouble() + size[1]};
            // 自己那条边不算：标签贴着自己的线是允许的（绑回箭头的短
            // 标签本来就被前端 bindContainerTexts 摆在折线中点上，而正
            // 交折线的中点几乎必然坐在折点上）
            String owner = label.path("id").asText()
                    .replaceFirst("_lbl$", "");
            int crossed = 0;
            for (int i = 0; i < segs.size(); i++) {
                if (owner.equals(segOwners.get(i))) {
                    continue;
                }
                if (SceneGeometryKit.segHitsBox(segs.get(i), box)) {
                    crossed++;
                }
            }
            assertThat(crossed)
                    .as("标签「%s」%s 被 %d 条别的线穿过", content, box, crossed)
                    .isZero();
            for (double[] node : nodeBoxes) {
                assertThat(SceneGeometryKit.segHitsBox(
                        new double[] {node[0], node[1], node[2], node[1]}, box)
                        || SceneGeometryKit.segHitsBox(
                        new double[] {node[2], node[1], node[2], node[3]}, box)
                        || SceneGeometryKit.segHitsBox(
                        new double[] {node[2], node[3], node[0], node[3]}, box)
                        || SceneGeometryKit.segHitsBox(
                        new double[] {node[0], node[3], node[0], node[1]}, box)
                        || (box[0] >= node[0] && box[2] <= node[2]
                        && box[1] >= node[1] && box[3] <= node[3]))
                        .as("标签「%s」不应压在节点框 %s 上", content, node)
                        .isFalse();
            }
        }
    }

    // ==================== 机制直测：候选里优先挑没线穿过的 ====================

    @Test
    @DisplayName("落位候选里优先挑没被别的线穿过的那一个")
    void findLabelSpotPrefersCandidateWithoutCrossingLines() {
        double[] size = SceneGeometryKit.labelSize("下单", 16);

        // 一条 440px 长的水平边，标签候选落在中段上下各 16px
        var points = MAPPER.createArrayNode();
        points.add(MAPPER.createArrayNode().add(160).add(30));
        points.add(MAPPER.createArrayNode().add(600).add(30));

        // 另一根箭头竖着穿过这条边的中段（x = 380）
        List<double[]> segs = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        segs.add(new double[] {380, -100, 380, 100});
        owners.add("arrow_v");

        double[] spot = new ScenePlanner().findLabelSpot(
                points, 0, 0, size, List.of(), List.of(),
                "arrow_h", segs, owners);
        assertThat(spot).isNotNull();

        double[] box = boxOf(spot, size);
        assertThat(SceneGeometryKit.countSegHits(
                box, segs, owners, "arrow_h"))
                .as("落位 %s 不应被竖线穿过", java.util.Arrays.toString(spot))
                .isZero();
        // 中段候选（沿段方向不滑动）确实会被竖线穿过 —— 这就是修之前
        // 会被选中的那个（首个通过 spotClean 的候选）
        double[] midBox = boxOf(new double[] {380, 30 + 16}, size);
        assertThat(SceneGeometryKit.countSegHits(
                midBox, segs, owners, "arrow_h"))
                .as("对照：中段候选应被竖线穿过").isEqualTo(1);
    }

    @Test
    @DisplayName("没有别的线时落位仍是中段（行为不变）")
    void findLabelSpotKeepsMidpointWhenNothingCrosses() {
        double[] size = SceneGeometryKit.labelSize("下单", 16);
        var points = MAPPER.createArrayNode();
        points.add(MAPPER.createArrayNode().add(160).add(30));
        points.add(MAPPER.createArrayNode().add(600).add(30));

        double[] spot = new ScenePlanner().findLabelSpot(
                points, 0, 0, size, List.of(), List.of(),
                "arrow_h", List.of(), List.of());
        assertThat(spot).isNotNull();
        assertThat(spot[0]).isEqualTo(380.0);
        // 先探 +法向（下侧）
        assertThat(spot[1]).isEqualTo(46.0);
    }

    private static double[] boxOf(double[] center, double[] size) {
        return new double[] {
            center[0] - size[0] / 2, center[1] - size[1] / 2,
            center[0] + size[0] / 2, center[1] + size[1] / 2};
    }

    @Test
    @DisplayName("短标签绑回箭头（带 containerId），长标签落为独立文字")
    void shortLabelsBindToArrowLongLabelsStayLoose() throws Exception {
        String laid = new ScenePlanner().arrange(
                mixedLabelScene(), new ElkLayoutEngine(), Set.of());
        JsonNode elements = MAPPER.readTree(laid).get("elements");

        JsonNode boundLabel = null;
        JsonNode looseLabel = null;
        for (JsonNode el : elements) {
            if (!"text".equals(el.path("type").asText())
                    || !el.path("id").asText().endsWith("_lbl")) {
                continue;
            }
            if ("短标签".equals(el.path("text").asText())) {
                boundLabel = el;
            } else if ("这是一个很长的标签需要落位成独立文字".equals(
                    el.path("text").asText())) {
                looseLabel = el;
            }
        }
        // 短标签：绑回箭头，前端按折线中点定位
        assertThat(boundLabel)
                .as("短标签应编译成带 containerId 的文字").isNotNull();
        assertThat(boundLabel.path("containerId").asText()).isNotEmpty();
        // 长标签：独立文字，仍带 x/y 落位
        assertThat(looseLabel)
                .as("长标签应落为独立文字").isNotNull();
        assertThat(looseLabel.has("containerId"))
                .as("长标签不应绑箭头").isFalse();
        assertThat(looseLabel.path("x").isNumber()).isTrue();
    }

    // ==================== 场景构造 ====================

    /** 三个模块 × 4 个图形：模块内箭头 + 4 条带标签的跨模块箭头 */
    private static String modularSceneWithLabels() {
        List<String> elements = new ArrayList<>();
        String[] groups = {"订单域", "用户域", "支付域"};
        String[] prefixes = {"a", "b", "c"};
        for (int g = 0; g < groups.length; g++) {
            for (int i = 0; i < 4; i++) {
                elements.add(rect(prefixes[g] + i,
                        groups[g] + i, groups[g]));
                if (i > 0) {
                    elements.add(arrow("e" + prefixes[g] + i,
                            prefixes[g] + (i - 1), prefixes[g] + i, null));
                }
            }
        }
        elements.add(arrow("x1", "a3", "b0", "下单"));
        elements.add(arrow("x2", "b3", "c0", "支付"));
        elements.add(arrow("x3", "c3", "a0", "回调"));
        elements.add(arrow("x4", "a0", "c2", "查询"));
        elements.add(arrow("x5", "b0", "a2", "校验"));
        return "{\"elements\":[" + String.join(",", elements) + "]}";
    }

    /** 一根带短标签的箭头（应绑回箭头）+ 一根带长标签的箭头（应落为独立文字） */
    private static String mixedLabelScene() {
        List<String> elements = new ArrayList<>();
        elements.add(rect("n1", "服务A", "域一"));
        elements.add(rect("n2", "服务B", "域二"));
        elements.add(rect("n3", "服务C", "域三"));
        elements.add(rect("n4", "服务D", "域四"));
        // 短标签（≤ BIND_MAX_WIDTH≈96px）：绑回箭头
        elements.add(arrow("e1", "n1", "n2", "短标签"));
        // 长标签（远超 96px）：落为独立文字
        elements.add(arrow("e2", "n3", "n4",
                "这是一个很长的标签需要落位成独立文字"));
        return "{\"elements\":[" + String.join(",", elements) + "]}";
    }

    private static String rect(String id, String label, String group) {
        return "{\"id\":\"" + id + "\",\"type\":\"rectangle\","
                + "\"x\":0,\"y\":0,\"width\":160,\"height\":60,"
                + "\"text\":\"" + label + "\",\"group\":\"" + group + "\"}";
    }

    private static String arrow(String id, String from, String to,
            String label) {
        return "{\"id\":\"" + id + "\",\"type\":\"arrow\","
                + "\"start\":{\"id\":\"" + from + "\"},"
                + "\"end\":{\"id\":\"" + to + "\"}"
                + (label == null ? "" : ",\"label\":{\"text\":\"" + label + "\"}")
                + "}";
    }

    /** 箭头 points 是相对 x/y 的偏移，这里摊成绝对坐标 */
    private static List<double[]> absolutePoints(JsonNode arrow) {
        List<double[]> pts = new ArrayList<>();
        double ox = arrow.path("x").asDouble();
        double oy = arrow.path("y").asDouble();
        for (JsonNode p : arrow.path("points")) {
            pts.add(new double[] {
                ox + p.path(0).asDouble(), oy + p.path(1).asDouble()});
        }
        return pts;
    }
}
