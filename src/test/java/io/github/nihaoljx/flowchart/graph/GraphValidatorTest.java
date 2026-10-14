package io.github.nihaoljx.flowchart.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;

class GraphValidatorTest {

    private final GraphValidator validator =
            new GraphValidator();

    @Test
    void validScenePasses() {
        String json = """
                {
                  "elements": [
                    {
                      "id": "n1",
                      "type": "rectangle",
                      "x": 0, "y": 0,
                      "width": 120, "height": 60,
                      "label": {"text": "开始"}
                    }
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.isEmpty(),
                "合法 scene 应校验通过，issues: "
                        + issues);
    }

    @Test
    void missingElementsFieldFails() {
        String json = """
                {"foo": "bar"}
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertFalse(issues.isEmpty(),
                "缺 elements 应报错");
        assertEquals("elements",
                issues.get(0).field());
    }

    @Test
    void invalidElementTypeFails() {
        String json = """
                {
                  "elements": [
                    {
                      "id": "n1",
                      "type": "triangle",
                      "x": 0, "y": 0,
                      "width": 100, "height": 100
                    }
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertFalse(issues.isEmpty(),
                "非法 type 应报错");
    }

    @Test
    void legalRelationPasses() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle", "x": 0, "y": 0, "width": 10, "height": 10},
                    {"id": "n2", "type": "rectangle", "x": 0, "y": 0, "width": 10, "height": 10},
                    {"id": "e1", "type": "arrow", "start": {"id": "n1"}, "end": {"id": "n2"}, "relation": "one-to-many"}
                  ]
                }
                """;
        assertTrue(validator.validate(json).isEmpty(),
                "合法 relation 应放行");
    }

    @Test
    void illegalRelationFails() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle", "x": 0, "y": 0, "width": 10, "height": 10},
                    {"id": "n2", "type": "rectangle", "x": 0, "y": 0, "width": 10, "height": 10},
                    {"id": "e1", "type": "arrow", "start": {"id": "n1"}, "end": {"id": "n2"}, "relation": "telepathy"}
                  ]
                }
                """;
        List<ValidationIssue> issues = validator.validate(json);
        assertFalse(issues.isEmpty(),
                "非法 relation 必须判非法（否则 LLM 用了它、整轮被判非法白耗重试）");
    }

    @Test
    void arrowMustHaveStartEndOrPoints() {
        String json = """
                {
                  "elements": [
                    {
                      "id": "a1",
                      "type": "arrow"
                    }
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertFalse(issues.isEmpty(),
                "arrow 缺 start/end 和 points 应报错");
    }

    @Test
    void arrowWithBindingPasses() {
        String json = """
                {
                  "elements": [
                    {
                      "id": "n1",
                      "type": "rectangle",
                      "x": 0, "y": 0,
                      "width": 100, "height": 60
                    },
                    {
                      "id": "n2",
                      "type": "rectangle",
                      "x": 200, "y": 0,
                      "width": 100, "height": 60
                    },
                    {
                      "id": "a1",
                      "type": "arrow",
                      "start": {"id": "n1"},
                      "end": {"id": "n2"}
                    }
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.isEmpty(),
                "绑定式箭头应通过，issues: " + issues);
    }

    @Test
    void invalidJsonReturnsParseError() {
        List<ValidationIssue> issues =
                validator.validate("not json");
        assertEquals(1, issues.size());
        assertEquals("$", issues.get(0).field());
    }

    // ===== v2-32 新增：这些字段此前完全没校验，放行会让画布出可见缺陷 =====

    @Test
    void labelWrittenAsStringFails() {
        // label 写成字符串时 Excalidraw 不报错，而是静默丢掉这段文字
        String json = """
                {
                  "elements": [
                    {
                      "id": "n1", "type": "rectangle",
                      "x": 0, "y": 0, "width": 100, "height": 60,
                      "label": "开始"
                    }
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                        i -> i.field().endsWith(".label")),
                "label 非对象应报错，实际: " + issues);
    }

    @Test
    void labelWithoutTextFails() {
        String json = """
                {
                  "elements": [
                    {
                      "id": "n1", "type": "rectangle",
                      "x": 0, "y": 0, "width": 100, "height": 60,
                      "label": {}
                    }
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                        i -> i.field().endsWith(".label.text")),
                "label 缺 text 应报错，实际: " + issues);
    }

    @Test
    void arrowBindingToMissingElementFails() {
        // 悬空绑定不会让 Excalidraw 抛错，但箭头会失去跟随关系（视觉上"箭头没了"）
        String json = """
                {
                  "elements": [
                    {
                      "id": "a1", "type": "arrow",
                      "start": {"id": "ghost"},
                      "end": {"id": "ghost2"}
                    }
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                        i -> i.field().endsWith(".start.id")),
                "端点悬空应报错，实际: " + issues);
        assertTrue(issues.stream().anyMatch(
                        i -> i.field().endsWith(".end.id")),
                "两个端点都应被检查，实际: " + issues);
    }

    @Test
    void duplicateIdFails() {
        String json = """
                {
                  "elements": [
                    {"id": "dup", "type": "rectangle",
                     "x": 0, "y": 0, "width": 10, "height": 10},
                    {"id": "dup", "type": "rectangle",
                     "x": 20, "y": 0, "width": 10, "height": 10}
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                        i -> i.reason().contains("id 重复")),
                "重复 id 应报错，实际: " + issues);
    }

    @Test
    void pointsMustBePairsOfNumbers() {
        // 非法点不会抛错，而是产生 NaN 坐标 —— 图形被画到画布外
        String json = """
                {
                  "elements": [
                    {"id": "l1", "type": "line", "x": 0, "y": 0,
                     "points": [[0, 0], ["a", 1]]}
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                        i -> i.field().contains(".points[1]")),
                "非法点应报错，实际: " + issues);
    }

    @Test
    void illegalEnumValueFails() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle",
                     "x": 0, "y": 0, "width": 10, "height": 10,
                     "fillStyle": "none"}
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                        i -> i.field().endsWith(".fillStyle")),
                "非法枚举值应报错，实际: " + issues);
    }

    @Test
    void opacityOutOfRangeFails() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle",
                     "x": 0, "y": 0, "width": 10, "height": 10,
                     "opacity": 200}
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                        i -> i.field().endsWith(".opacity")),
                "opacity 超范围应报错，实际: " + issues);
    }

    @Test
    void textElementWithoutTextFails() {
        // 缺 text 会让整个转换器抛错 -> 画布全空
        String json = """
                {
                  "elements": [
                    {"id": "t1", "type": "text", "x": 0, "y": 0}
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                        i -> i.field().endsWith(".text")),
                "text 元素缺 text 应报错，实际: " + issues);
    }

    @Test
    @DisplayName("md v5.8.0 约束3：自环箭头（start.id == end.id）应判非法")
    void selfLoopArrowFails() {
        // 2026-09-24 口径反转（以 system-prompt.md 为准）：v2-41 曾放开自环，现收回。
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle",
                     "x": 0, "y": 0, "width": 100, "height": 60},
                    {"id": "a1", "type": "arrow",
                     "start": {"id": "n1"}, "end": {"id": "n1"}}
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                        i -> i.reason().contains("自环")),
                "自环箭头应报错，实际: " + issues);
    }

    @Test
    @DisplayName("D3: 正常箭头（start != end）应通过")
    void normalArrowPasses() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle",
                     "x": 0, "y": 0, "width": 100, "height": 60},
                    {"id": "n2", "type": "rectangle",
                     "x": 300, "y": 0, "width": 100, "height": 60},
                    {"id": "a1", "type": "arrow",
                     "start": {"id": "n1"}, "end": {"id": "n2"}}
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().noneMatch(
                i -> i.reason().contains("自环箭头")),
                "正常箭头不应报自环错误，实际: " + issues);
    }

    @Test
    @DisplayName("v2-40 P0: zigzag 填充 + 图形虚线描边应通过")
    void zigzagAndShapeStrokeStylePass() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle",
                     "x": 0, "y": 0, "width": 100, "height": 60,
                     "fillStyle": "zigzag", "strokeStyle": "dashed"}
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.isEmpty(),
                "zigzag + 虚线描边应通过，issues: " + issues);
    }

    @Test
    @DisplayName("v2-40 P0: 12 种箭头端点形状应全部通过")
    void allArrowheadsPass() {
        List<String> arrowheads = List.of(
                "arrow", "bar", "dot", "circle", "circle_outline",
                "triangle", "triangle_outline", "diamond",
                "diamond_outline", "crowfoot_one", "crowfoot_many",
                "crowfoot_one_or_many");
        for (String head : arrowheads) {
            String json = """
                    {
                      "elements": [
                        {"id": "n1", "type": "rectangle",
                         "x": 0, "y": 0, "width": 100, "height": 60},
                        {"id": "n2", "type": "rectangle",
                         "x": 300, "y": 0, "width": 100, "height": 60},
                        {"id": "a1", "type": "arrow",
                         "start": {"id": "n1"}, "end": {"id": "n2"},
                         "endArrowhead": "%s"}
                      ]
                    }
                    """.formatted(head);
            List<ValidationIssue> issues =
                    validator.validate(json);
            assertTrue(issues.isEmpty(),
                    "端点形状 " + head + " 应通过，issues: "
                            + issues);
        }
    }

    @Test
    @DisplayName("v2-40 P0: 非法箭头端点形状应报错")
    void invalidArrowheadFails() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle",
                     "x": 0, "y": 0, "width": 100, "height": 60},
                    {"id": "n2", "type": "rectangle",
                     "x": 300, "y": 0, "width": 100, "height": 60},
                    {"id": "a1", "type": "arrow",
                     "start": {"id": "n1"}, "end": {"id": "n2"},
                     "endArrowhead": "sword"}
                  ]
                }
                """;
        List<ValidationIssue> issues =
                validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                i -> i.field().endsWith("endArrowhead")),
                "非法端点形状应报错，实际: " + issues);
    }

    @Test
    @DisplayName("v2-40 P0: 文字 verticalAlign 合法通过、非法报错")
    void textVerticalAlignValidation() {
        String good = """
                {
                  "elements": [
                    {"id": "t1", "type": "text", "x": 0, "y": 0,
                     "text": "说明", "verticalAlign": "middle"}
                  ]
                }
                """;
        assertTrue(validator.validate(good).isEmpty(),
                "verticalAlign=middle 应通过");

        String bad = """
                {
                  "elements": [
                    {"id": "t1", "type": "text", "x": 0, "y": 0,
                     "text": "说明", "verticalAlign": "center"}
                  ]
                }
                """;
        assertTrue(validator.validate(bad).stream().anyMatch(
                i -> i.field().endsWith("verticalAlign")),
                "非法 verticalAlign 应报错");
    }

    // ===== v2-41: 放开 P0 / P1 原生能力限制 =====

    @Test
    @DisplayName("v10.0.0：arrow 允许 start/end 绑定与 points 折线并存（会转弯的箭头）")
    void arrowWithBindingAndPointsPasses() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle", "x": 0, "y": 0, "width": 100, "height": 60},
                    {"id": "n2", "type": "rectangle", "x": 300, "y": 0, "width": 100, "height": 60},
                    {"id": "a1", "type": "arrow", "start": {"id": "n1"}, "end": {"id": "n2"},
                     "points": [[0, 0], [150, 80], [300, 0]]}
                  ]
                }
                """;
        // v5.9.0 起这条被判"只能二选一"，代价是「会转弯的箭头」整个能力消失，
        // 而它本是 Excalidraw 原生形态（编辑器拖中点加拐点就是这个数据结构），
        // 前端 sceneAdapter.withArrowGeometry 对 hasXY && hasPoints 也原样透传。
        // v10.0.0 放开：绑两端保证跟随，points 提供折线顶点。
        List<ValidationIssue> issues = validator.validate(json);
        assertTrue(issues.isEmpty(),
                "绑定 + 折线应放行，实际 issues: " + issues);
    }

    @Test
    @DisplayName("v10.0.0：绑定箭头带非法点仍被打回（放开并存不等于放开点形状）")
    void arrowWithBindingAndMalformedPointsFails() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle", "x": 0, "y": 0, "width": 100, "height": 60},
                    {"id": "n2", "type": "rectangle", "x": 300, "y": 0, "width": 100, "height": 60},
                    {"id": "a1", "type": "arrow", "start": {"id": "n1"}, "end": {"id": "n2"},
                     "points": [[0, 0], ["150", 80]]}
                  ]
                }
                """;
        assertTrue(validator.validate(json).stream().anyMatch(
                        i -> i.field().contains("points[1]")),
                "非法点必须照常打回（否则折线坐标会变 NaN、图形被画到画布外）");
    }

    @Test
    @DisplayName("md v5.8.0 约束3：arrow 不能绑到 frame（画框不能当端点）")
    void arrowBindingToFrameFails() {
        String json = """
                {
                  "elements": [
                    {"id": "f1", "type": "frame", "x": 0, "y": 0, "width": 400, "height": 300, "name": "订单域"},
                    {"id": "n1", "type": "rectangle", "x": 20, "y": 20, "width": 100, "height": 60},
                    {"id": "a1", "type": "arrow", "start": {"id": "f1"}, "end": {"id": "n1"}}
                  ]
                }
                """;
        assertTrue(validator.validate(json).stream().anyMatch(
                        i -> i.reason().contains("不能绑定到frame")),
                "箭头绑 frame 应报错，issues: " + validator.validate(json));
    }

    @Test
    @DisplayName("md v5.8.0 约束7：line 不能用 start/end 绑定（只作装饰分隔线）")
    void lineBindingFails() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle", "x": 0, "y": 0, "width": 100, "height": 60},
                    {"id": "n2", "type": "rectangle", "x": 300, "y": 0, "width": 100, "height": 60},
                    {"id": "l1", "type": "line", "x": 0, "y": 0, "start": {"id": "n1"}, "end": {"id": "n2"}}
                  ]
                }
                """;
        assertTrue(validator.validate(json).stream().anyMatch(
                        i -> i.reason().contains("不能用 start/end 绑定")),
                "绑定式 line 应报错，issues: " + validator.validate(json));
    }

    @Test
    @DisplayName("md v5.8.0 约束7：line 无 points 应报错")
    void lineWithoutGeometryFails() {
        String json = """
                {
                  "elements": [
                    {"id": "l1", "type": "line", "x": 0, "y": 0}
                  ]
                }
                """;
        List<ValidationIssue> issues = validator.validate(json);
        assertTrue(issues.stream().anyMatch(
                i -> i.reason().contains("line 必须有 points 路径")),
                "无几何的 line 应报错，实际: " + issues);
    }

    @Test
    @DisplayName("md v5.8.0 约束6：group 只能贴在矩形/椭圆/菱形上（text/arrow/line 判非法）")
    void groupOnNonShapeFails() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle", "x": 0, "y": 0, "width": 100, "height": 60},
                    {"id": "n2", "type": "rectangle", "x": 300, "y": 0, "width": 100, "height": 60},
                    {"id": "t1", "type": "text", "x": 0, "y": 0, "text": "说明", "group": "订单域"},
                    {"id": "a1", "type": "arrow", "start": {"id": "n1"}, "end": {"id": "n2"}, "group": "订单域"},
                    {"id": "l1", "type": "line", "x": 0, "y": 0, "points": [[0,0],[10,0]], "group": "支付域"}
                  ]
                }
                """;
        List<ValidationIssue> issues = validator.validate(json);
        long cnt = issues.stream()
                .filter(i -> i.reason().contains("group 只能贴在矩形"))
                .count();
        assertTrue(cnt == 3,
                "text / arrow / line 上的 group 都应报错（共 3 条），实际: " + issues);
    }

    @Test
    @DisplayName("v2-41 P1#7: fillStyle 新增 dots / dashed / diagonal 应通过")
    void fillStyleExtensionsPass() {
        for (String fs : new String[]{"dots", "dashed", "diagonal"}) {
            String json = """
                    {
                      "elements": [
                        {"id": "n1", "type": "rectangle", "x": 0, "y": 0,
                         "width": 100, "height": 60, "fillStyle": "%s"}
                      ]
                    }
                    """.formatted(fs);
            assertTrue(validator.validate(json).isEmpty(),
                    "fillStyle=" + fs + " 应通过，issues: "
                            + validator.validate(json));
        }
    }

    @Test
    @DisplayName("v2-41 P1#6: group 数组最多 5 层应通过")
    void groupArrayUpToFivePasses() {
        String json = """
                {
                  "elements": [
                    {"id": "n1", "type": "rectangle", "x": 0, "y": 0, "width": 100, "height": 60,
                     "group": ["L1", "L2", "L3", "L4", "L5"]}
                  ]
                }
                """;
        assertTrue(validator.validate(json).isEmpty(),
                "5 层 group 应通过，issues: " + validator.validate(json));
    }
}
