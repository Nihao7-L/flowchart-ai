package io.github.nihaoljx.flowchart.graph.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宽容归一化的行为测试（2026-10-07）
 *
 * <p>为什么值得单独测：这一步是**在校验之前改模型输出**的，它一旦改错，
 * 用户看到的就是"和需求不符但校验通过"的图 —— 比打回一轮难发现得多。
 * 所以每个 case 都要钉死"改了什么、没改什么"。
 */
class SceneSanitizerTest {

    private final SceneSanitizer sanitizer =
            new SceneSanitizer();

    // ---------------- label 直写字符串 ----------------

    @Test
    @DisplayName("label 直写字符串 → 包成 {\"text\":...}（信息一字不差）")
    void wrapsTextualLabel() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\","
                + "\"label\":\"登录页\"}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"label\":{\"text\":\"登录页\"}"),
                "直写字符串的 label 必须被包成契约形态，实际: " + out);
    }

    @Test
    @DisplayName("label 是空字符串 → 整个键删掉")
    void dropsBlankTextualLabel() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\","
                + "\"label\":\"\"}]}";

        String out = sanitizer.sanitize(in);

        assertFalse(out.contains("label"),
                "空字符串 label 等于没标签，必须整键删除，实际: " + out);
    }

    // ---------------- label 是对象但 text 不合法 ----------------

    @Test
    @DisplayName("label 空壳（{} / 空串 / 全空格）→ 整个键删掉")
    void dropsEmptyLabels() {
        String in = "{\"elements\":["
                + "{\"id\":\"a\",\"type\":\"rectangle\",\"label\":{}},"
                + "{\"id\":\"b\",\"type\":\"rectangle\","
                + "\"label\":{\"text\":\"\"}},"
                + "{\"id\":\"c\",\"type\":\"rectangle\","
                + "\"label\":{\"text\":\"   \"}}]}";

        String out = sanitizer.sanitize(in);

        assertFalse(out.contains("label"),
                "三种空壳都该被丢掉，实际: " + out);
        // 元素本身一个都不能少 —— 修复标签不等于删元素
        assertEquals(3, countOccurrences(out, "\"type\":\"rectangle\""),
                "元素不能被连带删掉，实际: " + out);
    }

    @Test
    @DisplayName("label.text 不是字符串（数字）→ 整个键删掉")
    void dropsNonStringText() {
        String in = "{\"elements\":["
                + "{\"id\":\"a\",\"type\":\"rectangle\","
                + "\"label\":{\"text\":123}}]}";

        String out = sanitizer.sanitize(in);

        assertFalse(out.contains("label"),
                "无从判断模型想写什么字，宁缺勿滥，实际: " + out);
    }

    @Test
    @DisplayName("label 是数字 / 布尔 / 数组 → 整个键删掉")
    void dropsNonTextualNonObjectLabel() {
        String in = "{\"elements\":["
                + "{\"id\":\"a\",\"type\":\"rectangle\",\"label\":7},"
                + "{\"id\":\"b\",\"type\":\"rectangle\",\"label\":true},"
                + "{\"id\":\"c\",\"type\":\"rectangle\",\"label\":[\"x\"]}]}";

        String out = sanitizer.sanitize(in);

        assertFalse(out.contains("label"),
                "非字符串非对象一律丢，实际: " + out);
    }

    // ---------------- 不该动的东西 ----------------

    @Test
    @DisplayName("合法 label 原样保留，且没有改动时返回同一个字符串实例")
    void keepsValidLabelUntouched() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\","
                + "\"label\":{\"text\":\"订单服务\"}}]}";

        String out = sanitizer.sanitize(in);

        assertSame(in, out,
                "没坏就不许动：没有改动时必须返回原字符串（上游靠它判断是否变更）");
    }

    @Test
    @DisplayName("label 为 null / 缺失时不动")
    void ignoresMissingLabel() {
        String in = "{\"elements\":["
                + "{\"id\":\"a\",\"type\":\"rectangle\"},"
                + "{\"id\":\"b\",\"type\":\"rectangle\",\"label\":null}]}";

        assertSame(in, sanitizer.sanitize(in),
                "没有 label 的元素必须原样返回");
    }

    @Test
    @DisplayName("只修 label，其它字段（含 arrow 的绑定与 points）一律不碰")
    void touchesNothingButLabel() {
        String in = "{\"elements\":["
                + "{\"id\":\"n1\",\"type\":\"rectangle\","
                + "\"x\":10,\"y\":20,\"width\":100,\"height\":50,"
                + "\"strokeColor\":\"#1e1e1e\",\"group\":\"订单域\"},"
                + "{\"id\":\"n2\",\"type\":\"rectangle\","
                + "\"x\":300,\"y\":20,\"width\":100,\"height\":50},"
                + "{\"id\":\"a1\",\"type\":\"arrow\",\"start\":{\"id\":\"n1\"},"
                + "\"end\":{\"id\":\"n2\"},\"label\":{\"text\":\"\"}}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"x\":10") && out.contains("\"y\":20")
                        && out.contains("\"group\":\"订单域\""),
                "几何与语义字段必须原样，实际: " + out);
        assertTrue(out.contains("\"start\":{\"id\":\"n1\"}")
                        && out.contains("\"end\":{\"id\":\"n2\"}"),
                "箭头绑定必须原样，实际: " + out);
        assertFalse(out.contains("label"),
                "只有箭头那个空 label 被删，实际: " + out);
    }

    // ---------------- 数字字段被写成字符串 ----------------

    @Test
    @DisplayName("fontFamily 写成字符串 \"1\" → 还原成数字 1（文字类场景首轮必挂的那条）")
    void coercesFontFamilyString() {
        String in = "{\"elements\":["
                + "{\"id\":\"t1\",\"type\":\"text\",\"x\":0,\"y\":0,"
                + "\"text\":\"1+1 等于几\",\"fontFamily\":\"1\"}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"fontFamily\":1"),
                "字符串 \"1\" 必须还原成数字 1，实际: " + out);
        assertFalse(out.contains("\"fontFamily\":\"1\""),
                "还原后不能再留字符串形态，实际: " + out);
    }

    @Test
    @DisplayName("其它数值字段同样还原（width / opacity / x 小数）")
    void coercesOtherNumericStrings() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\",\"x\":\"10.5\","
                + "\"y\":\"20\",\"width\":\"160\",\"height\":\"80\","
                + "\"opacity\":\"80\",\"strokeWidth\":\"2\"}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"x\":10.5"), "小数要还原成 double，实际: " + out);
        assertTrue(out.contains("\"y\":20"), "整数要还原成整型，实际: " + out);
        assertTrue(out.contains("\"width\":160") && out.contains("\"height\":80"),
                "宽高要还原，实际: " + out);
        assertTrue(out.contains("\"opacity\":80")
                        && out.contains("\"strokeWidth\":2"),
                "样式数值要还原，实际: " + out);
    }

    @Test
    @DisplayName("数字字段给了非数字文本 → 一律不动（该被打回的别偷偷改）")
    void leavesNonNumericTextAlone() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                + "\"width\":\"宽一点\",\"height\":80}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"width\":\"宽一点\""),
                "无法确定的文本不许乱改（校验器该报就报），实际: " + out);
    }

    @Test
    @DisplayName("NaN / Infinity / 科学计数法不当数字还原")
    void rejectsNonDecimalNumericText() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\",\"x\":\"NaN\","
                + "\"y\":\"Infinity\",\"width\":\"1e5\",\"height\":80}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"x\":\"NaN\"")
                        && out.contains("\"y\":\"Infinity\"")
                        && out.contains("\"width\":\"1e5\""),
                "严格十进制之外的形态不许还原，实际: " + out);
    }

    @Test
    @DisplayName("漂移守卫：数字字段表必须覆盖 schema 里所有数值属性")
    void numericFieldsCoverSchema() throws Exception {
        com.fasterxml.jackson.databind.JsonNode schema =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        new java.io.File("src/main/resources/schemas/scene.schema.json"));
        java.util.Set<String> fromSchema = new java.util.TreeSet<>();
        com.fasterxml.jackson.databind.JsonNode defs = schema.get("definitions");
        java.util.Iterator<String> branches = defs.fieldNames();
        while (branches.hasNext()) {
            com.fasterxml.jackson.databind.JsonNode props =
                    defs.get(branches.next()).get("properties");
            if (props == null) {
                continue;
            }
            java.util.Iterator<String> names = props.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String type = props.get(name).path("type").asText("");
                if ("number".equals(type) || "integer".equals(type)) {
                    fromSchema.add(name);
                }
            }
        }
        // fontFamily 在 schema 里是 enum（值都是数字），扫不到 type，单独算
        fromSchema.add("fontFamily");

        java.util.Set<String> missing = new java.util.TreeSet<>(fromSchema);
        missing.removeAll(SceneSanitizer.NUMERIC_FIELDS);
        assertTrue(missing.isEmpty(),
                "契约新增了数值字段但 SceneSanitizer.NUMERIC_FIELDS 没跟上，"
                        + "模型写成字符串时又会被打回: " + missing);
    }

    // ---------------- 已绑定箭头上的 points ----------------

    @Test
    @DisplayName("已绑定箭头上的 points 残桩（点数 < 2）→ 摘掉（画不出线，前端会按绑定重推）")
    void removesPointsOnBoundArrow() {
        String in = "{\"elements\":["
                + "{\"id\":\"n1\",\"type\":\"rectangle\","
                + "\"x\":0,\"y\":0,\"width\":100,\"height\":50},"
                + "{\"id\":\"n2\",\"type\":\"rectangle\","
                + "\"x\":300,\"y\":0,\"width\":100,\"height\":50},"
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"start\":{\"id\":\"n1\"},\"end\":{\"id\":\"n2\"},"
                + "\"points\":[[0,0]]}]}";

        String out = sanitizer.sanitize(in);

        assertFalse(out.contains("points"),
                "点数 < 2 的 points 画不出线，属残桩，摘掉，实际: " + out);
        assertTrue(out.contains("\"start\":{\"id\":\"n1\"}")
                        && out.contains("\"end\":{\"id\":\"n2\"}"),
                "绑定本身必须原样保留，实际: " + out);
    }

    @Test
    @DisplayName("绑定箭头只给首尾两点 → 摘掉（那是它在脑算端点，会插进框里）")
    void removesTwoPointStraightOnBoundArrow() {
        // 2026-10-09 实测：模型写两点时表达的是"从 A 连到 B"，却把端点算成
        // **目标框中心**。h_sequence 的 user 框 y 从 50 到 100，它写的端点
        // y 就是 75（正中）；渲染层 hasXY && hasPoints 原样透传 ⇒ 箭头插进
        // 框里压住 label。20 例里 18 处如此。接哪条边交回渲染器算。
        String in = "{\"elements\":["
                + "{\"id\":\"n1\",\"type\":\"rectangle\","
                + "\"x\":0,\"y\":0,\"width\":100,\"height\":50},"
                + "{\"id\":\"n2\",\"type\":\"rectangle\","
                + "\"x\":300,\"y\":0,\"width\":100,\"height\":50},"
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"start\":{\"id\":\"n1\"},\"end\":{\"id\":\"n2\"},"
                + "\"x\":50,\"y\":25,\"points\":[[0,0],[300,25]]}]}";

        String out = sanitizer.sanitize(in);

        assertFalse(out.contains("points"),
                "首尾两点不是折线，是脑算的直线端点，摘掉后由渲染器取边缘交点，"
                        + "实际: " + out);
        assertTrue(out.contains("\"start\":{\"id\":\"n1\"}")
                        && out.contains("\"end\":{\"id\":\"n2\"}"),
                "绑定必须原样保留，实际: " + out);
    }

    @Test
    @DisplayName("绑定箭头带中间折点（点数 ≥ 3）→ 原样保留（「绑定 + 折线」仍放行）")
    void keepsMultiPointPathOnBoundArrow() {
        String in = "{\"elements\":["
                + "{\"id\":\"n1\",\"type\":\"rectangle\","
                + "\"x\":0,\"y\":0,\"width\":100,\"height\":50},"
                + "{\"id\":\"n2\",\"type\":\"rectangle\","
                + "\"x\":400,\"y\":0,\"width\":100,\"height\":50},"
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"start\":{\"id\":\"n1\"},\"end\":{\"id\":\"n2\"},"
                + "\"points\":[[0,0],[200,90],[400,0]]}]}";

        assertSame(in, sanitizer.sanitize(in),
                "会转弯的箭头是 Excalidraw 原生形态。sanitize 排在 validate 之前，"
                        + "这里若摘掉，后端校验器放开也白做 —— 折线永远到不了校验器");
    }

    @Test
    @DisplayName("两端绑到不存在元素的箭头 → points 一律留着（让校验器照常报错）")
    void keepsPointsWhenBindingsUnresolvable() {
        String in = "{\"elements\":["
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"start\":{\"id\":\"ghost1\"},\"end\":{\"id\":\"ghost2\"},"
                + "\"points\":[[0,0]]}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"points\":[[0,0]]"),
                "绑到不存在的元素是真错误，不能被这里顺手掩盖，实际: " + out);
    }

    @Test
    @DisplayName("只写 points 的自由箭头 → points 原样保留")
    void keepsPointsOnFreeArrow() {
        String in = "{\"elements\":["
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"points\":[[0,0],[100,50],[200,0]]}]}";

        assertSame(in, sanitizer.sanitize(in),
                "自由箭头的 points 是它唯一的几何来源，动了就等于改图");
    }

    // ---------------- points 被写成绝对坐标 ----------------

    @Test
    @DisplayName("points 用绝对坐标写（首点 == x/y）→ 减回相对坐标")
    void rebasesAbsolutePoints() {
        // 实测原样（画猫那组）：x/y 就是第一个点，points 里也是绝对坐标，
        // 落点 = x+dx 直接翻倍 → 整组线被画到视野外。
        String in = "{\"elements\":["
                + "{\"id\":\"ear_left\",\"type\":\"line\","
                + "\"x\":170,\"y\":170,"
                + "\"points\":[[170,170],[140,140],[150,140],[170,170]],"
                + "\"closed\":true}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"points\":[[0,0],[-30,-30],[-20,-30],[0,0]]"),
                "绝对坐标的 points 必须减回相对坐标（否则落点翻倍、整张图不见），"
                        + "实际: " + out);
        assertTrue(out.contains("\"x\":170") && out.contains("\"y\":170"),
                "x/y 是元素原点，必须原样不动，实际: " + out);
    }

    @Test
    @DisplayName("自由箭头的 points 同样按绝对坐标写 → 也减回相对坐标")
    void rebasesAbsoluteArrowPoints() {
        String in = "{\"elements\":["
                + "{\"id\":\"a1\",\"type\":\"arrow\","
                + "\"x\":100,\"y\":200,"
                + "\"points\":[[100,200],[300,200],[300,260]]}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"points\":[[0,0],[200,0],[200,60]]"),
                "不带绑定的箭头也是同一套相对坐标语义，实际: " + out);
    }

    @Test
    @DisplayName("本来就是相对坐标（首点 [0,0]）→ 一个字都不动")
    void keepsRelativePointsWhenOriginNonZero() {
        String in = "{\"elements\":["
                + "{\"id\":\"l1\",\"type\":\"line\","
                + "\"x\":170,\"y\":170,"
                + "\"points\":[[0,0],[30,30],[60,0]]}]}";

        assertSame(in, sanitizer.sanitize(in),
                "写对了的别动。误改这里等于把用户要的图形挪了位置");
    }

    @Test
    @DisplayName("首点是别的坐标（既不是 [0,0] 也不等于 x/y）→ 不动，留给校验器")
    void keepsPointsWithUnrecognizedOrigin() {
        String in = "{\"elements\":["
                + "{\"id\":\"l1\",\"type\":\"line\","
                + "\"x\":170,\"y\":170,"
                + "\"points\":[[10,20],[140,140]]}]}";

        assertSame(in, sanitizer.sanitize(in),
                "认不出来就不许猜 —— 这条规则只在「首点与 x/y 完全相等」的铁证下触发");
    }

    @Test
    @DisplayName("原点为 0 时不做无谓改动（减法结果本来就是 [0,0]）")
    void skipsRebaseWhenOriginIsZero() {
        String in = "{\"elements\":["
                + "{\"id\":\"l1\",\"type\":\"line\","
                + "\"x\":0,\"y\":0,"
                + "\"points\":[[0,0],[100,0]]}]}";

        assertSame(in, sanitizer.sanitize(in),
                "x/y 为 0 时绝对与相对坐标重合，没有可改的东西");
    }

    @Test
    @DisplayName("非整数坐标减出来还是数字（不是字符串、不是 NaN）")
    void rebasesFractionalPointsAsNumbers() {
        String in = "{\"elements\":["
                + "{\"id\":\"l1\",\"type\":\"line\","
                + "\"x\":10.5,\"y\":20.25,"
                + "\"points\":[[10.5,20.25],[30.5,25.25]]}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"points\":[[0,0],[20,5]]"),
                "小数减法结果必须还原成数字且整数写整数，实际: " + out);
        assertFalse(out.contains("\"points\":\"["),
                "不许把坐标写成字符串，实际: " + out);
    }

    @Test
    @DisplayName("points 里混进非坐标（字符串 / 长度不足）→ 整条不动")
    void refusesToRebaseMalformedPoints() {
        String in = "{\"elements\":["
                + "{\"id\":\"l1\",\"type\":\"line\","
                + "\"x\":10,\"y\":10,"
                + "\"points\":[[10,10],[\"20\",20],[30]]}]}";

        assertSame(in, sanitizer.sanitize(in),
                "有点不是坐标就认不出来，整条保持原样（该被校验器打回就打回）");
    }

    // ---------------- JSON 里的注释 ----------------

    @Test
    @DisplayName("行注释与块注释都被剥掉（模型写 // 会让下游 JSON.parse 当场失败）")
    void stripsComments() {
        String in = "{// 这是模型加的解释\n\"elements\":[/* 节点 */"
                + "{\"id\":\"r1\",\"type\":\"rectangle\","
                + "\"x\":0,\"y\":0,\"width\":100,\"height\":50}]}";

        String out = sanitizer.sanitize(in);

        assertFalse(out.contains("//") || out.contains("/*"),
                "注释必须全部剥掉，实际: " + out);
        assertTrue(out.contains("\"id\":\"r1\""),
                "剥注释不能伤到数据，实际: " + out);
    }

    @Test
    @DisplayName("注释符出现在字符串里时不许误伤（https:// 会被截断成 \"https:）")
    void keepsSlashesInsideStrings() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\",\"x\":0,\"y\":0,"
                + "\"width\":100,\"height\":50,"
                + "\"link\":\"https://example.com/a//b\"}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("https://example.com/a//b"),
                "URL 里的 // 不是注释，绝不能被砍（按状态机扫字面量才行），实际: " + out);
    }

    @Test
    @DisplayName("转义引号不会让状态机错乱")
    void handlesEscapedQuote() {
        String in = "{\"elements\":["
                + "{\"id\":\"t1\",\"type\":\"text\",\"x\":0,\"y\":0,"
                + "\"text\":\"他说\\\"好\\\"，// 不是注释\"}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("// 不是注释"),
                "字符串里的 // 属于数据，必须原样留着，实际: " + out);
    }

    // ---------------- 类型名别名 ----------------

    @Test
    @DisplayName("type 写成 circle → 归一成 ellipse，几何字段一字不动")
    void aliasesCircleToEllipse() {
        String in = "{\"elements\":["
                + "{\"id\":\"e1\",\"type\":\"circle\",\"x\":10,\"y\":20,"
                + "\"width\":60,\"height\":60,"
                + "\"backgroundColor\":\"#a5d8ff\"}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"type\":\"ellipse\""),
                "circle 是 Excalidraw 词汇，契约名是 ellipse，实际: " + out);
        assertFalse(out.contains("\"circle\""),
                "归一后不许再留 circle，实际: " + out);
        assertTrue(out.contains("\"width\":60") && out.contains("\"height\":60")
                        && out.contains("\"x\":10") && out.contains("\"y\":20"),
                "宽高相等即圆，几何字段必须原样，实际: " + out);
    }

    @Test
    @DisplayName("认不出的类型名一律不动（该被校验器打回的别偷偷改）")
    void leavesUnknownTypeAlone() {
        String in = "{\"elements\":["
                + "{\"id\":\"x1\",\"type\":\"triangle\",\"x\":0,\"y\":0,"
                + "\"width\":60,\"height\":60}]}";

        assertSame(in, sanitizer.sanitize(in),
                "别名表刻意不预撒网：猜错了等于把用户要的图形换成了别的");
    }

    @Test
    @DisplayName("漂移守卫：别名表的目标类型必须是契约里真实存在的类型")
    void typeAliasTargetsAreRealTypes() throws Exception {
        com.fasterxml.jackson.databind.JsonNode schema =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        new java.io.File("src/main/resources/schemas/scene.schema.json"));
        java.util.Set<String> realTypes = new java.util.TreeSet<>();
        com.fasterxml.jackson.databind.JsonNode defs = schema.get("definitions");
        java.util.Iterator<String> branches = defs.fieldNames();
        while (branches.hasNext()) {
            com.fasterxml.jackson.databind.JsonNode props =
                    defs.get(branches.next()).get("properties");
            if (props == null) {
                continue;
            }
            com.fasterxml.jackson.databind.JsonNode typeNode = props.get("type");
            if (typeNode == null) {
                continue;
            }
            if (typeNode.has("const")) {
                realTypes.add(typeNode.get("const").asText());
            }
            if (typeNode.has("enum")) {
                typeNode.get("enum").forEach(v -> realTypes.add(v.asText()));
            }
        }
        for (String target : SceneSanitizer.TYPE_ALIASES.values()) {
            assertTrue(realTypes.contains(target),
                    "别名表把 type 归一成了契约里不存在的类型 " + target
                            + "（契约类型名改了？）");
        }
    }

    // ---------------- 兜底：不许变成新的故障点 ----------------

    @Test
    @DisplayName("坏 JSON / 缺 elements / 顶层不是对象 → 原样返回，绝不抛")
    void neverThrows() {
        String broken = "{\"elements\":[{\"id\":\"a\"";
        assertSame(broken, sanitizer.sanitize(broken),
                "解析失败的输入必须原样返回");

        String noElements = "{\"foo\":1}";
        assertSame(noElements, sanitizer.sanitize(noElements),
                "没有 elements 数组时原样返回");

        String notArray = "{\"elements\":\"nope\"}";
        assertSame(notArray, sanitizer.sanitize(notArray),
                "elements 不是数组时原样返回");

        String empty = "";
        assertSame(empty, sanitizer.sanitize(empty),
                "空串必须原样返回");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    // ---------------- 决策点 → 菱形 ----------------

    @Test
    @DisplayName("矩形 label 命中判断/决策/分支 ⇒ 升级成 diamond")
    void decisionRectBecomesDiamond() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\","
                + "\"label\":{\"text\":\"判断订单是否合法\"}},"
                + "{\"id\":\"r2\",\"type\":\"rectangle\","
                + "\"label\":\"分支路由\"},"
                + "{\"id\":\"r3\",\"type\":\"rectangle\","
                + "\"label\":{\"text\":\"库存校验\"}}]}";

        String out = sanitizer.sanitize(in);

        assertEquals(3, countOccurrences(out, "\"type\":\"diamond\""),
                "三个决策矩形都应变菱形，实际: " + out);
        assertEquals(0, countOccurrences(out, "\"type\":\"rectangle\""),
                "不应再有矩形，实际: " + out);
    }

    @Test
    @DisplayName("普通矩形（无决策语义）保持 rectangle")
    void plainRectStaysRect() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\","
                + "\"label\":{\"text\":\"订单详情\"}}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"type\":\"rectangle\""),
                "普通流程节点不应被改成菱形，实际: " + out);
    }

    // ---------------- 语义配色兜底 ----------------

    @Test
    @DisplayName("未写 color 的异常/失败节点 ⇒ 推断 red")
    void errorNodeGetsRed() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\","
                + "\"label\":{\"text\":\"支付失败\"}},"
                + "{\"id\":\"r2\",\"type\":\"rectangle\","
                + "\"label\":\"库存不足\"}]}";

        String out = sanitizer.sanitize(in);

        assertEquals(2, countOccurrences(out, "\"color\":\"red\""),
                "失败/不足节点应判红，实际: " + out);
    }

    @Test
    @DisplayName("已写 color 的节点不被覆盖")
    void existingColorNotOverridden() {
        String in = "{\"elements\":["
                + "{\"id\":\"r1\",\"type\":\"rectangle\",\"color\":\"green\","
                + "\"label\":{\"text\":\"支付失败\"}}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"color\":\"green\""),
                "已显式写对的 color 必须保留，实际: " + out);
        assertFalse(out.contains("\"color\":\"red\""),
                "不应覆盖模型已写对的配色，实际: " + out);
    }

    @Test
    @DisplayName("终态/等待/并行/正常/历史节点各得对应色")
    void semanticColorsAssigned() {
        String in = "{\"elements\":["
                + "{\"id\":\"a\",\"type\":\"rectangle\","
                + "\"label\":{\"text\":\"已完成\"}},"
                + "{\"id\":\"b\",\"type\":\"rectangle\","
                + "\"label\":\"处理中\"},"
                + "{\"id\":\"c\",\"type\":\"rectangle\","
                + "\"label\":\"并行区\"},"
                + "{\"id\":\"d\",\"type\":\"rectangle\","
                + "\"label\":\"正常服务\"},"
                + "{\"id\":\"e\",\"type\":\"rectangle\","
                + "\"label\":\"历史归档\"}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"color\":\"blue\""), "完成→blue: " + out);
        assertTrue(out.contains("\"color\":\"yellow\""), "进行中→yellow: " + out);
        assertTrue(out.contains("\"color\":\"purple\""), "并行→purple: " + out);
        assertTrue(out.contains("\"color\":\"green\""), "正常→green: " + out);
        assertTrue(out.contains("\"color\":\"gray\""), "历史→gray: " + out);
    }

    @Test
    @DisplayName("children 完全相同的两个框 → 只留先出现的那个（同名框标题会压印成乱码）")
    void duplicateFramesWithSameChildrenDropped() {
        // 实测来源：state-machine 的模型输出里，同一批成员既有
        // `notification` 又有 `processing` 两个框覆盖，位置相同 ⇒ 两个
        // 标题字逐字叠印，渲染成 `poinfessing`（processing 三次压印）
        String in = "{\"elements\":["
                + "{\"id\":\"f1\",\"type\":\"frame\",\"name\":\"notification\","
                + "\"children\":[\"a\",\"b\"]},"
                + "{\"id\":\"f4\",\"type\":\"frame\",\"name\":\"processing\","
                + "\"children\":[\"b\",\"a\"]},"
                + "{\"id\":\"a\",\"type\":\"rectangle\"},"
                + "{\"id\":\"b\",\"type\":\"rectangle\"}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"id\":\"f1\""),
                "先出现的框必须留下: " + out);
        assertFalse(out.contains("\"id\":\"f4\""),
                "children 集合相同（顺序不同也算同一批）的重复框必须删掉: "
                        + out);
        assertTrue(out.contains("\"id\":\"a\"")
                        && out.contains("\"id\":\"b\""),
                "成员节点一个都不能丢: " + out);
    }

    @Test
    @DisplayName("children 不同的框一律不动（嵌套框的外层与内层成员不同）")
    void framesWithDifferentChildrenKept() {
        String in = "{\"elements\":["
                + "{\"id\":\"outer\",\"type\":\"frame\",\"name\":\"order\","
                + "\"children\":[\"inner\"]},"
                + "{\"id\":\"inner\",\"type\":\"frame\",\"name\":\"processing\","
                + "\"children\":[\"a\"]},"
                + "{\"id\":\"a\",\"type\":\"rectangle\"}]}";

        String out = sanitizer.sanitize(in);

        assertTrue(out.contains("\"id\":\"outer\""), "外层框保留: " + out);
        assertTrue(out.contains("\"id\":\"inner\""), "内层框保留: " + out);
    }
}
