package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchemaContractRenderer} 的措辞直测。
 *
 * <p>提示词是**逐字节**进模型、且有字节闸门的东西 —— 一个空格、一对括号
 * 写错都可能改变模型输出。这些渲染器原先埋在 PromptService 里只能经
 * {@code buildContractBlock()} 的整段文本间接验证；抽出来后可以直接钉死
 * 每种 schema 形态的写法，改动措辞会立刻在这里变红。
 */
@DisplayName("SchemaContractRenderer 契约渲染措辞")
class SchemaContractRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> HINTS = Map.of(
            "angle", "弧度不是角度",
            "backgroundColor", "要填充就得给，不写等于没填");

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("branchNames：有 enum 展开成 a / b / c，无 enum 取 const")
    void branchNamesPreferEnumThenConst() throws Exception {
        JsonNode byEnum = MAPPER.readTree("""
                {"properties": {"type": {"enum": ["rectangle", "ellipse", "diamond"]}}}
                """);
        assertThat(SchemaContractRenderer.branchNames(byEnum))
                .isEqualTo("rectangle / ellipse / diamond");

        JsonNode byConst = MAPPER.readTree("""
                {"properties": {"type": {"const": "arrow"}}}
                """);
        assertThat(SchemaContractRenderer.branchNames(byConst)).isEqualTo("arrow");
    }

    @Test
    @DisplayName("requiredSuffix：附上必填键、按 schema 顺序、无必填时为空串")
    void requiredSuffixListsKeysInSchemaOrder() throws Exception {
        JsonNode withRequired = MAPPER.readTree("""
                {"required": ["id", "type", "x"]}
                """);
        assertThat(SchemaContractRenderer.requiredSuffix(withRequired))
                .isEqualTo("〔必填：id,type,x〕");

        // 无 required 字段 -> 空串（不产生空括号）
        assertThat(SchemaContractRenderer.requiredSuffix(
                MAPPER.readTree("{}"))).isEmpty();

        // required 是空数组 -> 同样空串
        assertThat(SchemaContractRenderer.requiredSuffix(
                MAPPER.readTree("{\"required\": []}"))).isEmpty();
    }

    @Test
    @DisplayName("renderProp：字符串枚举用 []、数字枚举标 (数字)，两者长得不一样")
    void renderPropDistinguishesNumericEnum() throws Exception {
        JsonNode stringEnum = MAPPER.readTree("""
                {"enum": ["left", "center", "right"]}
                """);
        assertThat(SchemaContractRenderer.renderProp(
                "textAlign", stringEnum, null, HINTS))
                .isEqualTo("textAlign[left|center|right]");

        // 数字枚举必须标 (数字)：否则模型照抄写成 "1" 字符串被校验器打回
        JsonNode numericEnum = MAPPER.readTree("{\"enum\": [1, 2, 3]}");
        assertThat(SchemaContractRenderer.renderProp(
                "fontFamily", numericEnum, null, HINTS))
                .isEqualTo("fontFamily 1|2|3(数字)");
    }

    @Test
    @DisplayName("renderProp：字段提示表以括号后缀追加，表里没有的字段不加")
    void renderPropAppendsHintOnlyWhenPresent() throws Exception {
        JsonNode number = MAPPER.readTree("{\"type\": \"number\"}");

        assertThat(SchemaContractRenderer.renderProp(
                "angle", number, null, HINTS))
                .isEqualTo("angle(弧度不是角度)");

        // 不在表里 -> 原样，不带空括号
        assertThat(SchemaContractRenderer.renderProp(
                "width", number, null, HINTS)).isEqualTo("width");

        // hints 传 null 也不炸（非 Spring 装配路径）
        assertThat(SchemaContractRenderer.renderProp(
                "angle", number, null, null)).isEqualTo("angle");
    }

    @Test
    @DisplayName("renderProp：$ref 展开 label/binding/point，未知 ref 递归取定义")
    void renderPropExpandsKnownRefs() throws Exception {
        JsonNode definitions = MAPPER.readTree("""
                {"label": {"$ref": "#/definitions/labelInner"},
                 "labelInner": {"type": "string"},
                 "arrowhead": {"enum": ["arrow", "dot"]}}
                """);

        assertThat(SchemaContractRenderer.renderProp(
                "label", MAPPER.readTree("{\"$ref\": \"#/definitions/label\"}"),
                definitions, HINTS))
                .isEqualTo("label{\"text\"}");

        assertThat(SchemaContractRenderer.renderProp(
                "startBinding", MAPPER.readTree("{\"$ref\": \"#/definitions/binding\"}"),
                definitions, HINTS))
                .isEqualTo("startBinding{\"id\"}");

        assertThat(SchemaContractRenderer.renderProp(
                "point", MAPPER.readTree("{\"$ref\": \"#/definitions/point\"}"),
                definitions, HINTS))
                .isEqualTo("point[[0,0],[dx,dy]...]");

        // 未知 ref -> 取同名定义继续渲染（labelInner 是 string -> 只报名字）
        assertThat(SchemaContractRenderer.renderProp(
                "label", MAPPER.readTree("{\"$ref\": \"#/definitions/labelInner\"}"),
                definitions, HINTS))
                .isEqualTo("label");

        // 未知 ref 指向枚举 -> 走枚举分支
        assertThat(SchemaContractRenderer.renderProp(
                "endArrowhead", MAPPER.readTree("{\"$ref\": \"#/definitions/arrowhead\"}"),
                definitions, HINTS))
                .isEqualTo("endArrowhead[arrow|dot]");
    }

    @Test
    @DisplayName("renderProp：number 卡死 >0 时标 (>0)；const 只报名字；无 type 只报名字")
    void renderPropHandlesScalarShapes() throws Exception {
        assertThat(SchemaContractRenderer.renderProp(
                "width", MAPPER.readTree(
                        "{\"type\": \"number\", \"exclusiveMinimum\": 0}"), null, HINTS))
                .isEqualTo("width(>0)");

        assertThat(SchemaContractRenderer.renderProp(
                "type", MAPPER.readTree("{\"const\": \"rectangle\"}"), null, HINTS))
                .isEqualTo("type");

        // group 的 oneOf 没有 type，只报名字，别报成对象
        assertThat(SchemaContractRenderer.renderProp(
                "children", MAPPER.readTree("{\"oneOf\": [{\"type\": \"string\"}]}"),
                null, HINTS))
                .isEqualTo("children");
    }

    @Test
    @DisplayName("renderProp：integer 带 maximum 写成 0-max")
    void renderPropRendersIntegerRange() throws Exception {
        assertThat(SchemaContractRenderer.renderProp(
                "opacity", MAPPER.readTree(
                        "{\"type\": \"integer\", \"maximum\": 100}"), null, HINTS))
                .isEqualTo("opacity 0-100");

        assertThat(SchemaContractRenderer.renderProp(
                "z", MAPPER.readTree("{\"type\": \"integer\"}"), null, HINTS))
                .isEqualTo("z");
    }

    @Test
    @DisplayName("arrayShape：items 指向 point 时写出首点约定，其余给 [...]")
    void arrayShapeExplainsRelativePoints() throws Exception {
        // points 是相对偏移、首点必须是 [0,0]；写出来否则模型会填绝对坐标
        assertThat(SchemaContractRenderer.arrayShape(MAPPER.readTree(
                "{\"items\": {\"$ref\": \"#/definitions/point\"}}")))
                .isEqualTo("[[0,0],[dx,dy]...]");

        assertThat(SchemaContractRenderer.arrayShape(MAPPER.readTree(
                "{\"items\": {\"type\": \"string\"}}")))
                .isEqualTo("[...]");

        assertThat(SchemaContractRenderer.arrayShape(MAPPER.readTree("{}")))
                .isEqualTo("[...]");
    }

    @Test
    @DisplayName("isExclusivePositive：只有 exclusiveMinimum 恰为 0 才算")
    void isExclusivePositiveOnlyForZero() throws Exception {
        assertThat(SchemaContractRenderer.isExclusivePositive(
                MAPPER.readTree("{\"exclusiveMinimum\": 0}"))).isTrue();
        assertThat(SchemaContractRenderer.isExclusivePositive(
                MAPPER.readTree("{\"exclusiveMinimum\": 1}"))).isFalse();
        assertThat(SchemaContractRenderer.isExclusivePositive(
                MAPPER.readTree("{\"type\": \"number\"}"))).isFalse();
    }
}
