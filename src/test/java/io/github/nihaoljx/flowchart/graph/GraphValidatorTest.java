package io.github.nihaoljx.flowchart.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
    @DisplayName("D3: 自环箭头（start.id == end.id）必须报错")
    void selfLoopArrowFails() {
        // 2026-09-16 实测：LLM 删图后为消除悬挂引用把箭头全改绑成 end→end。
        // 零长度自环无意义且会渲染成一段看不到的线段。
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
                i -> i.field().equals("elements[1]")
                        && i.reason().contains("自环箭头")),
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
}
