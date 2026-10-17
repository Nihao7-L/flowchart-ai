package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 契约段（schema）→ 提示词文本的渲染器（从 PromptService 抽出，report 3.2）。
 *
 * <p>报告 3.2 点名 `PromptService` 过大。但它的"大"分两种：一种是**业务
 * 判断**（编辑模式该贴全文还是贴摘要、哪些元素被用户点名、修复轮该说什么），
 * 这一半必须留在服务里，因为它要读会话与请求语义；另一种是**纯格式变换**
 * ——把 `scene.schema.json` 的一个分支翻译成一行紧凑写法。后者只吃
 * {@link JsonNode}、只吐 {@link String}，与请求、会话、Spring 全都无关，
 * 抽出来既减行数又让它能单独测（见 {@code SchemaContractRendererTest}）。
 *
 * <p>本类**无状态、全静态**。现场唯一的可变输入是字段提示表 {@code hints}，
 * 由调用方传入而不是在这里持有一份 —— 那张表是"要让模型知道什么"的产品
 * 判断，归属仍在 PromptService，它的漂移守卫（每个键必须是 schema 真实字段）
 * 也挂在那里。本类只负责把表贴在对应字段后面。
 *
 * <p>行为与抽出前逐字等价：措辞、括号、分隔符、数字枚举与字符串枚举的
 * 区分口径一律未改 —— 提示词是逐字节进模型、且有字节闸门的东西，任何一个
 * 空格都可能改变输出。
 */
final class SchemaContractRenderer {

    private SchemaContractRenderer() {
    }

    /**
     * 分支的展示名：`shape` 展开成「rectangle / ellipse / diamond」
     * （取自它 `type` 的 enum），其余分支用自己 `type` 的 const。
     *
     * @param def 分支定义（必须含 {@code properties.type}）
     * @return 展示名
     */
    static String branchNames(JsonNode def) {
        JsonNode typeNode = def.get("properties").get("type");
        JsonNode enumNode = typeNode.get("enum");
        if (enumNode != null) {
            List<String> names = new ArrayList<>();
            for (JsonNode value : enumNode) {
                names.add(value.asText());
            }
            return String.join(" / ", names);
        }
        return typeNode.get("const").asText();
    }

    /**
     * 必填键后缀：把契约里的 `required` 附在行尾
     * （模型漏写必填键会被直接打回）。
     *
     * <p>⚠️ 起**不再剔除 type**：原先认为它"自明"（行首就写着
     * rectangle / arrow…）而省掉，但这恰恰让模型以为 type 不属于该写的键
     * —— 实战里 15 模块大图第一轮 38 个 issue 全是"缺少必填字段 type"。
     * 真值是 schema 的 required，就照抄，别再自作聪明。
     *
     * @param def 分支定义
     * @return 形如 {@code 〔必填：id,type〕}；无必填时为空串
     */
    static String requiredSuffix(JsonNode def) {
        JsonNode required = def.get("required");
        if (required == null || required.isEmpty()) {
            return "";
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode key : required) {
            keys.add(key.asText());
        }
        return "〔必填：" + String.join(",", keys) + "〕";
    }

    /**
     * 单个字段的紧凑写法：`fillStyle` → `fillStyle[hachure|...]`，
     * `opacity` → `opacity 0-100`，`label` → `label{"text"}`。
     *
     * @param name        字段名
     * @param node        字段 schema
     * @param definitions schema 的 `definitions` 节点（供 `$ref` 递归展开）
     * @param hints       字段提示表（键是字段名），可为空
     * @return 紧凑写法
     */
    static String renderProp(
            String name, JsonNode node, JsonNode definitions,
            Map<String, String> hints) {
        String base = renderPropBase(name, node, definitions, hints);
        String hint = hints == null ? null : hints.get(name);
        return hint == null ? base : base + "(" + hint + ")";
    }

    /**
     * 字段写法的主体部分（不含提示表后缀）。
     *
     * <p>未知的 `$ref` 递归展开一层，不认识就退回字段名，绝不抛异常 ——
     * 提示词拼装失败等于整条生成链路跪掉，这里必须稳。
     *
     * @param name        字段名
     * @param node        字段 schema
     * @param definitions schema 的 `definitions` 节点
     * @param hints       字段提示表（递归时透传）
     * @return 紧凑写法（无提示后缀）
     */
    static String renderPropBase(
            String name, JsonNode node, JsonNode definitions,
            Map<String, String> hints) {
        JsonNode ref = node.get("$ref");
        String refName = ref == null
                ? ""
                : ref.asText().substring(ref.asText().lastIndexOf('/') + 1);
        if (!refName.isEmpty()) {
            switch (refName) {
                case "label":
                    return name + "{\"text\"}";
                case "binding":
                    return name + "{\"id\"}";
                case "point":
                    // 与 arrayShape 的 points 形态保持一致：首点必须写出来，
                    // 见那边的注释（模型会把绝对坐标写进 points 导致落点翻倍）。
                    // 目前 schema 里 point 只以 items.$ref 的形式出现、走不到这里，
                    // 但留着一条不一致的写法迟早会咬人。
                    return name + "[[0,0],[dx,dy]...]";
                default:
                    return renderProp(
                            name, definitions.get(refName), definitions, hints);
            }
        }
        if (node.get("enum") != null) {
            List<String> values = new ArrayList<>();
            boolean numeric = true;
            for (JsonNode value : node.get("enum")) {
                values.add(value.asText());
                numeric = numeric && value.isNumber();
            }
            String joined = String.join("|", values);
            // ⚠️ 数字枚举不能与字符串枚举共用 [] 写法：textAlign[left|center|right]
            // 和 fontFamily[1|2|3] 长得一模一样，模型照抄就写成 "1"（字符串），
            // 而校验器要求 val.isInt() → 实测"写"类场景（写诗 / 算术 / emoji /
            // 纯符号）首轮 4/4 全挂在 fontFamily 上。标成"数字"让形态自解释。
            return numeric
                    ? name + " " + joined + "(数字)"
                    : name + "[" + joined + "]";
        }
        if (node.get("const") != null) {
            return name;
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "number":
                return isExclusivePositive(node) ? name + "(>0)" : name;
            case "integer":
                return node.get("maximum") != null
                        ? name + " 0-" + node.get("maximum").asInt()
                        : name;
            case "array":
                return name + arrayShape(node);
            case "string":
                return name;
            default:
                // 没有 type 的（如 group 的 oneOf）就只报名字，别报成对象
                return name;
        }
    }

    /**
     * 数组的简写：points → `[[0,0],[dx,dy]...]`，其余 → `[...]`.
     *
     * <p>⚠️ ：原来写的是 `[[dx,dy]...]`，看不出**首点必须是 [0,0]**
     * —— 因为 points 是相对 x/y 的偏移（Excalidraw 约定，前端
     * `withArrowGeometry` 也是按 `[[0,0],[终点-起点]]` 生成的）。
     * 模型据此把**绝对坐标**写进 points，同时把 x/y 也设成第一个点，
     * 于是落点 = (x+dx, y+dy) ≈ 坐标翻倍：实测画猫那组 9 条线全被画到视野外，
     * 而校验器只检查每个点是不是两个数字、**不检查首点**，静默通过。
     * 渲染成 `[[0,0],[dx,dy]...]` 让这个约定自解释。
     *
     * @param node 数组字段的 schema
     * @return 数组简写
     */
    static String arrayShape(JsonNode node) {
        JsonNode items = node.get("items");
        if (items != null && items.get("$ref") != null
                && items.get("$ref").asText().endsWith("/point")) {
            return "[[0,0],[dx,dy]...]";
        }
        return "[...]";
    }

    /**
     * {@code {exclusiveMinimum:0}} 表示「必须大于 0」，提示词里写成 `(>0)`。
     *
     * @param node 字段 schema
     * @return 是否为卡死大于 0 的数值
     */
    static boolean isExclusivePositive(JsonNode node) {
        JsonNode min = node.get("exclusiveMinimum");
        return min != null && min.asInt() == 0;
    }
}
