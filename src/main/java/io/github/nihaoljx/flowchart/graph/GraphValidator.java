package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 图表 IR 校验器（v2-9 建立，v2-32 补全字段级校验）
 *
 * <p>职责：LLM 输出的 JSON → 按 scene.schema.json 规则逐字段校验
 * → 返回 field 级 issue 列表（空 = 通过）。
 *
 * <p>实现方式：Jackson 解析 + 手写规则（不引入 networknt），
 * 对 6 类元素的必填字段 / 联合约束 / 引用完整性足够精确，且零外部依赖。
 *
 * <p>为什么校验必须"宁严勿松"：前端把 IR 交给 Excalidraw 的
 * {@code convertToExcalidrawElements} 时，**一个坏元素会让整批转换抛错**
 * （2026-09-16 浏览器实测：frame 缺 children →
 * {@code forEach of undefined}；text 缺 text → {@code replace of undefined}），
 * 画布直接全空。校验层是唯一能在坏数据到达浏览器**之前**拦住它的地方，
 * 放行等于让用户看到一片空白且不知道为什么。
 *
 * <p>还有一类更隐蔽的问题：不抛错但静默丢内容（label 传字符串 → 字没了；
 * points 含非数字 → 坐标变 NaN 画到画布外；箭头绑定悬空 → 箭头不再跟随）。
 * 这类同样只能靠校验层拦。
 */
@Component
public class GraphValidator {

    /** 有几何形状的三类 */
    private static final Set<String> SHAPE_TYPES =
            Set.of("rectangle", "ellipse", "diamond");

    /** 契约允许的全部元素类型（与 scene.schema.json 的 oneOf 对齐） */
    private static final Set<String> ALL_TYPES =
            Set.of("rectangle", "ellipse", "diamond",
                    "arrow", "line", "text",
                    "frame", "freedraw");

    /** 契约允许的填充样式（{@code zigzag} 于 v2-40 P0 补入） */
    private static final Set<String> FILL_STYLES =
            Set.of("hachure", "cross-hatch", "solid", "zigzag");

    /** 契约允许的描边样式 */
    private static final Set<String> STROKE_STYLES =
            Set.of("solid", "dashed", "dotted");

    /** 契约允许的文字对齐 */
    private static final Set<String> TEXT_ALIGNS =
            Set.of("left", "center", "right");

    /** 契约允许的文字垂直对齐（v2-40 P0） */
    private static final Set<String> VERTICAL_ALIGNS =
            Set.of("top", "middle", "bottom");

    /**
     * 契约允许的箭头端点形状（v2-40 P0）。
     *
     * <p>与 Excalidraw 的 {@code Arrowhead} 联合类型逐字对齐（12 种）——
     * 少一个值就等于"LLM 用了它、校验判非法、白耗一整轮重试"。
     * {@code crowfoot_*} 是 ER 图的基数标记；端点形状与端点绑定**可共存**
     * （2026-09-18 实测），所以不必为端点形状放弃"拖动跟随"。
     */
    private static final Set<String> ARROWHEADS = Set.of(
            "arrow", "bar", "dot", "circle", "circle_outline",
            "triangle", "triangle_outline", "diamond", "diamond_outline",
            "crowfoot_one", "crowfoot_many", "crowfoot_one_or_many");

    /** 契约允许的字体族（1=手绘 2=Helvetica 3=等宽） */
    private static final Set<Integer> FONT_FAMILIES =
            Set.of(1, 2, 3);

    /** id 合法字符集（与 schema 的 pattern 对齐） */
    private static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    /**
     * 校验 JSON 字符串是否符合 scene 契约
     *
     * @param json LLM 输出的原始 JSON 字符串
     * @return issue 列表（空 = 校验通过）
     */
    public List<ValidationIssue> validate(String json) {
        List<ValidationIssue> issues = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(json);
            validateRoot(root, issues);
        } catch (Exception e) {
            issues.add(new ValidationIssue(
                    "$",
                    "JSON 解析失败: " + e.getMessage(),
                    "请确保输出的是合法 JSON 对象"));
        }
        return issues;
    }

    private void validateRoot(
            JsonNode root,
            List<ValidationIssue> issues) {
        if (!root.has("elements")) {
            issues.add(new ValidationIssue(
                    "elements",
                    "顶层缺少 elements 字段",
                    "必须包含 elements 数组"));
            return;
        }
        JsonNode elements = root.get("elements");
        if (!elements.isArray()) {
            issues.add(new ValidationIssue(
                    "elements",
                    "elements 不是数组",
                    "elements 必须是数组类型"));
            return;
        }

        // 第一遍：收集 id，顺带查重。
        // 箭头端点是否"悬空"必须等全集 id 齐了才能判断，
        // 所以引用完整性检查和字段校验分成两遍走。
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < elements.size(); i++) {
            JsonNode elem = elements.get(i);
            if (!elem.isObject()) {
                continue;
            }
            JsonNode idNode = elem.get("id");
            if (idNode == null || !idNode.isTextual()) {
                continue;
            }
            String id = idNode.asText();
            if (!ids.add(id)) {
                issues.add(new ValidationIssue(
                        "elements[" + i + "].id",
                        "id 重复: " + id,
                        "每个元素的 id 必须唯一（箭头靠它绑定端点）"));
            }
        }

        // 第二遍：逐元素字段校验
        for (int i = 0; i < elements.size(); i++) {
            validateElement(
                    elements.get(i), i, ids, issues);
        }
    }

    private void validateElement(
            JsonNode elem,
            int index,
            Set<String> ids,
            List<ValidationIssue> issues) {
        String path = "elements[" + index + "]";

        if (!elem.isObject()) {
            issues.add(new ValidationIssue(
                    path,
                    "元素不是 JSON 对象",
                    "每个元素必须是对象"));
            return;
        }

        // ===== id（所有类型必填）=====
        if (!elem.has("id")) {
            issues.add(new ValidationIssue(
                    path + ".id",
                    "缺少必填字段 id",
                    "每个元素必须有唯一 id"));
        } else if (!elem.get("id").isTextual()) {
            issues.add(new ValidationIssue(
                    path + ".id",
                    "id 不是字符串",
                    "id 必须是字符串"));
        } else {
            String id = elem.get("id").asText();
            if (!id.matches(ID_PATTERN)) {
                issues.add(new ValidationIssue(
                        path + ".id",
                        "id 格式非法: " + id,
                        "只允许字母数字下划线连字符"));
            }
        }

        // ===== type =====
        if (!elem.has("type")) {
            issues.add(new ValidationIssue(
                    path + ".type",
                    "缺少必填字段 type",
                    "必须指定元素类型"));
            return;
        }
        String type = elem.get("type").asText();
        if (!ALL_TYPES.contains(type)) {
            issues.add(new ValidationIssue(
                    path + ".type",
                    "非法 type: " + type,
                    "允许值: " + ALL_TYPES));
            return;
        }

        // ===== 按类型校验特有字段 =====
        if (SHAPE_TYPES.contains(type)) {
            validateShape(elem, path, issues);
        } else if ("arrow".equals(type)) {
            validateArrow(elem, path, ids, issues);
        } else if ("line".equals(type)) {
            validateLine(elem, path, issues);
        } else if ("text".equals(type)) {
            validateText(elem, path, issues);
        } else if ("frame".equals(type)) {
            validateFrame(elem, path, issues);
        } else if ("freedraw".equals(type)) {
            validateFreedraw(elem, path, issues);
        }
    }

    private void validateShape(
            JsonNode elem,
            String path,
            List<ValidationIssue> issues) {
        checkRequiredNumber(elem, path, "x", issues);
        checkRequiredNumber(elem, path, "y", issues);
        checkRequiredPositive(elem, path, "width", issues);
        checkRequiredPositive(elem, path, "height", issues);
        checkLabel(elem, path, issues);
        checkOptionalNumber(elem, path, "angle", issues);
        checkOptionalEnum(
                elem, path, "fillStyle", FILL_STYLES, issues);
        checkOptionalEnum(
                elem, path, "strokeStyle", STROKE_STYLES, issues);
        checkOptionalNonNegative(
                elem, path, "strokeWidth", issues);
        checkOptionalNonNegative(
                elem, path, "roughness", issues);
        checkOptionalPercent(elem, path, "opacity", issues);
    }

    private void validateArrow(
            JsonNode elem,
            String path,
            Set<String> ids,
            List<ValidationIssue> issues) {
        boolean hasStart = elem.has("start")
                && elem.get("start").has("id");
        boolean hasEnd = elem.has("end")
                && elem.get("end").has("id");
        boolean hasPoints = elem.has("points")
                && elem.get("points").isArray()
                && elem.get("points").size() >= 2;

        if (!(hasStart && hasEnd) && !hasPoints) {
            issues.add(new ValidationIssue(
                    path,
                    "arrow 必须有 start+end 绑定 或 points 路径",
                    "二选一: start.id+end.id 或 points 数组"));
        }

        // 端点引用必须落在本场景内。Excalidraw 对悬空绑定不报错，
        // 只是箭头不再跟随目标、退化成一段孤立线段 —— 视觉上就是"箭头没了"。
        checkBinding(elem, path, "start", ids, issues);
        checkBinding(elem, path, "end", ids, issues);
        // D3: 自环箭头（start.id == end.id）无意义且会渲染出零长度线段，
        // 2026-09-16 实测：LLM 删图后为消除悬挂引用把箭头全改绑成 end→end。
        if (hasStart && hasEnd) {
            String startId = elem.get("start").get("id").asText();
            String endId = elem.get("end").get("id").asText();
            if (startId.equals(endId)) {
                issues.add(new ValidationIssue(
                        path,
                        "自环箭头: start.id == end.id == " + startId,
                        "箭头必须连接两个不同元素"));
            }
        }
        checkOptionalPoints(elem, path, issues);
        checkOptionalNumber(elem, path, "x", issues);
        checkOptionalNumber(elem, path, "y", issues);
        checkLabel(elem, path, issues);
        checkOptionalEnum(
                elem, path, "strokeStyle", STROKE_STYLES, issues);
        checkOptionalEnum(
                elem, path, "startArrowhead", ARROWHEADS, issues);
        checkOptionalEnum(
                elem, path, "endArrowhead", ARROWHEADS, issues);
        checkOptionalNonNegative(
                elem, path, "strokeWidth", issues);
        checkOptionalPercent(elem, path, "opacity", issues);
    }

    private void validateLine(
            JsonNode elem,
            String path,
            List<ValidationIssue> issues) {
        checkRequiredNumber(elem, path, "x", issues);
        checkRequiredNumber(elem, path, "y", issues);
        checkRequiredPoints(elem, path, issues);
        checkOptionalEnum(
                elem, path, "strokeStyle", STROKE_STYLES, issues);
        checkOptionalNonNegative(
                elem, path, "strokeWidth", issues);
        checkOptionalPercent(elem, path, "opacity", issues);
    }

    private void validateText(
            JsonNode elem,
            String path,
            List<ValidationIssue> issues) {
        checkRequiredNumber(elem, path, "x", issues);
        checkRequiredNumber(elem, path, "y", issues);
        if (!elem.has("text")
                || !elem.get("text").isTextual()
                || elem.get("text").asText().isBlank()) {
            issues.add(new ValidationIssue(
                    path + ".text",
                    "text 元素缺少 text 字段",
                    "text 元素必须有非空 text"
                            + "（缺了会让转换器整批抛错，画布全空）"));
        }
        checkOptionalMin(elem, path, "fontSize", 1, issues);
        checkOptionalFontFamily(elem, path, issues);
        checkOptionalEnum(
                elem, path, "textAlign", TEXT_ALIGNS, issues);
        checkOptionalEnum(
                elem, path, "verticalAlign", VERTICAL_ALIGNS, issues);
        checkOptionalNumber(elem, path, "angle", issues);
    }

    /**
     * frame 的字段校验。
     *
     * <p>⚠️ <b>为什么这里不校验 {@code children}</b>（2026-09-17 拍板动机、
     * 2026-09-18 修正落法）：渲染器 {@code convertToExcalidrawElements} 对
     * frame 强制访问 {@code children.forEach}，契约却（刻意）不含该字段。
     * 动机仍成立 —— <b>不让 LLM 填</b> children（它填不准，填错会连带
     * 影响整批转换）。
     *
     * <p>但 2026-09-18 的对照实测推翻了对 children 性质的判断：它<b>不是</b>
     * "渲染器实现细节、无语义"，而是「框 ↔ 内容」归属的唯一途径 ——
     * 传 {@code children:['n1','n2']} 时成员 {@code frameId} 被反填为
     * frame 的 id；传 {@code []}（旧做法）时成员 {@code frameId} 恒为 null，
     * 框与内容在数据模型里毫无关系（拖框带不走内容）。
     * 新落法：由<b>后端</b>按分组与几何算出成员列表、适配层填入
     * （见 {@code plans/underway/v2-40-contract-capacity.md} 的 P1）。
     */
    private void validateFrame(
            JsonNode elem,
            String path,
            List<ValidationIssue> issues) {
        checkRequiredNumber(elem, path, "x", issues);
        checkRequiredNumber(elem, path, "y", issues);
        checkRequiredPositive(elem, path, "width", issues);
        checkRequiredPositive(elem, path, "height", issues);
        if (elem.has("name")
                && !elem.get("name").isNull()
                && !elem.get("name").isTextual()) {
            issues.add(new ValidationIssue(
                    path + ".name",
                    "name 不是字符串",
                    "name 必须是字符串，或直接省略"));
        }
    }

    private void validateFreedraw(
            JsonNode elem,
            String path,
            List<ValidationIssue> issues) {
        checkRequiredNumber(elem, path, "x", issues);
        checkRequiredNumber(elem, path, "y", issues);
        checkRequiredPoints(elem, path, issues);
        checkOptionalNonNegative(
                elem, path, "strokeWidth", issues);
        checkOptionalPercent(elem, path, "opacity", issues);
    }

    // ===== 语义检查（schema 表达不了的部分）=====

    /**
     * label 必须写成 {@code {"text":"..."}} 对象。
     *
     * <p>2026-09-16 浏览器实测：直接写成字符串时 Excalidraw 不报错，
     * 而是**静默丢掉这段文字** —— 用户看到"图形在、里面的字没了"，
     * 比直接报错更难排查，所以必须在校验层拦住。
     */
    private void checkLabel(
            JsonNode elem,
            String path,
            List<ValidationIssue> issues) {
        if (!elem.has("label") || elem.get("label").isNull()) {
            return;
        }
        JsonNode label = elem.get("label");
        if (!label.isObject()) {
            issues.add(new ValidationIssue(
                    path + ".label",
                    "label 不是对象（收到 "
                            + label.getNodeType() + "）",
                    "label 必须写成 {\"text\":\"文字\"}，"
                            + "不能直接写字符串"));
            return;
        }
        JsonNode text = label.get("text");
        if (text == null
                || !text.isTextual()
                || text.asText().isBlank()) {
            issues.add(new ValidationIssue(
                    path + ".label.text",
                    "label 缺少非空 text",
                    "label 必须形如 {\"text\":\"文字\"}，"
                            + "且 text 非空"));
        }
    }

    /**
     * 端点绑定的引用完整性：{@code start.id} / {@code end.id}
     * 必须指向本场景里真实存在的元素。
     */
    private void checkBinding(
            JsonNode elem,
            String path,
            String field,
            Set<String> ids,
            List<ValidationIssue> issues) {
        if (!elem.has(field) || elem.get(field).isNull()) {
            return;
        }
        JsonNode binding = elem.get(field);
        if (!binding.isObject()
                || !binding.has("id")
                || !binding.get("id").isTextual()) {
            issues.add(new ValidationIssue(
                    path + "." + field,
                    field + " 必须是 {\"id\":\"目标元素id\"}",
                    "端点绑定要写成对象形式，且含字符串 id"));
            return;
        }
        String target = binding.get("id").asText();
        if (!ids.contains(target)) {
            issues.add(new ValidationIssue(
                    path + "." + field + ".id",
                    field + ".id 指向不存在的元素: " + target,
                    "端点必须绑定到本场景中已存在的图形 id"));
        }
    }

    // ===== 通用检查工具方法 =====

    private void checkRequiredNumber(
            JsonNode elem,
            String path,
            String field,
            List<ValidationIssue> issues) {
        if (!elem.has(field)) {
            issues.add(new ValidationIssue(
                    path + "." + field,
                    "缺少必填字段 " + field,
                    field + " 必须是数字"));
        } else if (!elem.get(field).isNumber()) {
            issues.add(new ValidationIssue(
                    path + "." + field,
                    field + " 不是数字",
                    field + " 必须是数字类型"));
        }
    }

    private void checkRequiredPositive(
            JsonNode elem,
            String path,
            String field,
            List<ValidationIssue> issues) {
        if (!elem.has(field)) {
            issues.add(new ValidationIssue(
                    path + "." + field,
                    "缺少必填字段 " + field,
                    field + " 必须是正数"));
            return;
        }
        JsonNode val = elem.get(field);
        if (!val.isNumber() || val.asDouble() <= 0) {
            issues.add(new ValidationIssue(
                    path + "." + field,
                    field + " 必须 > 0",
                    field + " 必须是正数"));
        }
    }

    private void checkRequiredPoints(
            JsonNode elem,
            String path,
            List<ValidationIssue> issues) {
        if (!elem.has("points")) {
            issues.add(new ValidationIssue(
                    path + ".points",
                    "缺少必填字段 points",
                    "points 必须是至少 2 个点的数组"));
            return;
        }
        checkPointsShape(
                elem.get("points"), path, issues);
    }

    private void checkOptionalPoints(
            JsonNode elem,
            String path,
            List<ValidationIssue> issues) {
        if (!elem.has("points")
                || elem.get("points").isNull()) {
            return;
        }
        checkPointsShape(
                elem.get("points"), path, issues);
    }

    /**
     * points 必须是 {@code [[dx,dy], ...]}，每一项都是两个数字。
     *
     * <p>2026-09-16 浏览器实测：非法点（如 {@code ["a",1]}）不会让转换器抛错，
     * 而是产生 NaN 坐标 —— 图形被画到画布之外，用户看到的同样是空白。
     */
    private void checkPointsShape(
            JsonNode points,
            String path,
            List<ValidationIssue> issues) {
        if (!points.isArray() || points.size() < 2) {
            issues.add(new ValidationIssue(
                    path + ".points",
                    "points 数组长度 < 2",
                    "至少需要 2 个点"));
            return;
        }
        for (int i = 0; i < points.size(); i++) {
            JsonNode point = points.get(i);
            boolean wellFormed = point.isArray()
                    && point.size() == 2
                    && point.get(0).isNumber()
                    && point.get(1).isNumber();
            if (!wellFormed) {
                issues.add(new ValidationIssue(
                        path + ".points[" + i + "]",
                        "点不是 [数字, 数字]",
                        "points 每一项必须是形如 [dx, dy] 的两个数字"));
            }
        }
    }

    private void checkOptionalNumber(
            JsonNode elem,
            String path,
            String field,
            List<ValidationIssue> issues) {
        if (!elem.has(field) || elem.get(field).isNull()) {
            return;
        }
        if (!elem.get(field).isNumber()) {
            issues.add(new ValidationIssue(
                    path + "." + field,
                    field + " 不是数字",
                    field + " 必须是数字类型"));
        }
    }

    private void checkOptionalMin(
            JsonNode elem,
            String path,
            String field,
            double min,
            List<ValidationIssue> issues) {
        if (!elem.has(field) || elem.get(field).isNull()) {
            return;
        }
        JsonNode val = elem.get(field);
        if (!val.isNumber() || val.asDouble() < min) {
            issues.add(new ValidationIssue(
                    path + "." + field,
                    field + " 不能小于 " + min,
                    field + " 必须是 >= " + min + " 的数字"));
        }
    }

    private void checkOptionalNonNegative(
            JsonNode elem,
            String path,
            String field,
            List<ValidationIssue> issues) {
        checkOptionalMin(elem, path, field, 0, issues);
    }

    private void checkOptionalPercent(
            JsonNode elem,
            String path,
            String field,
            List<ValidationIssue> issues) {
        if (!elem.has(field) || elem.get(field).isNull()) {
            return;
        }
        JsonNode val = elem.get(field);
        if (!val.isNumber()
                || val.asDouble() < 0
                || val.asDouble() > 100) {
            issues.add(new ValidationIssue(
                    path + "." + field,
                    field + " 必须在 0~100 之间",
                    field + " 是百分比，取值 0~100"));
        }
    }

    private void checkOptionalFontFamily(
            JsonNode elem,
            String path,
            List<ValidationIssue> issues) {
        if (!elem.has("fontFamily")
                || elem.get("fontFamily").isNull()) {
            return;
        }
        JsonNode val = elem.get("fontFamily");
        if (!val.isInt()
                || !FONT_FAMILIES.contains(val.asInt())) {
            issues.add(new ValidationIssue(
                    path + ".fontFamily",
                    "非法 fontFamily: " + val.asText(),
                    "允许值: 1(手绘) / 2(Helvetica) / 3(等宽)"));
        }
    }

    private void checkOptionalEnum(
            JsonNode elem,
            String path,
            String field,
            Set<String> allowed,
            List<ValidationIssue> issues) {
        if (!elem.has(field) || elem.get(field).isNull()) {
            return;
        }
        String value = elem.get(field).asText();
        if (!allowed.contains(value)) {
            issues.add(new ValidationIssue(
                    path + "." + field,
                    "非法取值: " + value,
                    "允许值: " + String.join(" / ", allowed)));
        }
    }
}
