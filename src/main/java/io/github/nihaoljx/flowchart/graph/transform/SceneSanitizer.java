package io.github.nihaoljx.flowchart.graph.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 场景宽容归一化
 *
 * 它解决的问题：模型会输出"格式差一点、但意图明确"的字段，
 * 而 GraphValidator 是按**契约字面**判的 —— 差一点就整轮打回，
 * 于是白白多跑一次 LLM（一次大图调用 50 秒起）。实测中等用例第 1 轮
 * 5 个箭头全带空 label、简单用例也偶发，`label 缺少非空 text` 一度
 * 是首轮校验失败的第一大类。
 *
 * 判据：只做「零信息损失」的修复，其余一律不动。
 * 这一条边界比"能修多少"重要 —— 这里每多修一种，就多一个
 * "用户看到的图和模型想画的图不一样"的隐蔽风险，而提示词口径修好
 * 之后模型本来就会写对。目前收七条，都有实测来源：
 *
 * - JSON 里的注释 → 剥掉。模型会写 `// 解释`，实测已踩两次
 *       （大图 { "画一万个矩形" } 与"改图但无上下文"）。注释不是数据，
 *       剥掉是最彻底的无损；而且必须在**这里**剥 —— 只在校验器里放宽解析
 *       的话，注释会跟着下发 JSON 流到前端，被严格 `JSON.parse` 拒掉。
 *       实现是字符串字面量状态机，不能用正则（`"url":"https://x"`
 *       会被 `//` 截断成 `"https:`，反而静默改坏数据）。
 *
 * - 类型名用了库内别名 `circle` → `ellipse`。模型从
 *       Excalidraw 原生词汇里漏出来的（画猫时眼睛/头"就是圆"），实测首轮报
 *       `非法 type: circle x2`。别名表刻意不预撒网，见 TYPE_ALIASES。
 *
 * - 数字写成字符串 `"fontFamily":"1"` → `1`。
 *       契约原来把数字枚举渲染成 `fontFamily[1|2|3]`，与
 *       `textAlign[left|center|right]` 同形，模型照抄就写成字符串，
 *       而校验器要求 `isInt()` —— 实测"写"类场景（写诗 / 算术 /
 *       emoji / 纯符号）首轮 4/4 全挂在"非法 fontFamily"。
 *
 * - 已绑定箭头上的 points → 只留真折线（≥ 3 个点），其余摘掉。
 *       契约规定 `start/end` 与 `points` 二选一；模型会留一个
 *       空/短的 points 数组，实测首轮报 `points 数组长度 < 2`。
 *        放开折线后发现更要命的一种：首尾两点的"直线端点"，
 *       模型把它算成目标图形中心而不是边缘（详见下方那一段）。
 *
 * - label 直写字符串 `label: "文字"` →
 *       `label: {"text":"文字"`}。信息完全等价；契约要求对象形态
 *       是因为直写字符串时 Excalidraw 会**静默丢掉这段文字**
 *       ，所以必须转成对象，而不是原样放过。
 *
 * - label 空壳 `{"text":""`} / `{"text":"  "`} /
 *       `{`} / 数字 / 布尔 / 数组 → 整个键删掉。空 label 的语义
 *       就是"这个元素没有标签"，删掉等于把话说清楚；留着才会被校验器打回
 *       （实测中等用例首轮 5 个箭头全带空 label）。
 *
 * - points 按绝对坐标写 → 减回相对坐标（见
 *       rebaseAbsolutePoints）。这是七条里唯一一条不是纯信息
 *       等价的 —— 它是对"模型想画在哪"的一次推断，所以触发条件卡得
 *       极死（首点与 x/y 完全相等才算），宁可漏修，绝不误改。
 *
 * "已绑定箭头上的 points"那条的边界要说清楚，因为它不像其余几条那样一眼等价：
 * 契约的语义模型里，绑定箭头的路径是从绑定推导的 ——
 * "绑两端就只写 start/end"这句话的等价表述就是
 * "绑定箭头没有自己的路径"。所以 LLM 输出里绑定箭头上的 `points`
 * 不是一种意图，而是冗余残留，摘掉在契约层面无损。
 *
 *  把它放宽成"≥ 2 个点就留下"，随后被实测打回来：模型写两点时
 * 想表达的是"从 A 连到 B"，但它把端点算成了框中心。20 个用例里
 * 17 个给绑定箭头写了 points，其中 18 处端点插在框内部（离边 20~50px），
 * 渲染层原样透传 ⇒ 箭头穿刺、压住框内文字。同一批里 `h_large` 的
 * 15 条却都算对了 —— 同一个模型有时对有时错，因为它看不见画布。
 * 所以口径收回成"有没有中间折点"：首尾两点不算折线（那是它在脑算
 * 端点，还会算错），≥ 3 个点才是它真的要转弯。接哪条边交给渲染器算
 * （前端 `edgePointTowards` 取包围盒朝向对方的边缘交点，永远准），
 * 模型只负责"连谁"和"怎么绕"。
 *
 * 反过来说，用户画布上已有的元素不受这里影响：`sanitize`
 * 只在 LLM 原始输出那一条链路上（`bind → dedup → sanitize`），
 * Excalidraw 回传的场景不经过它。
 *
 * 位置：必须排在 `bind / dedup` 之后、GraphValidator
 * 之前 —— 校验器判的是它拿到的 JSON，修复必须发生在判定之前，
 * 而且它改的必须是**最终要下发的**那份 JSON（否则校验过了、
 * 交付的还是坏的那份，等于白修）。
 *
 * 任何异常都**原样返回**：归一化是锦上添花，不能变成新的故障点
 * （与 SceneDeduper 同一条纪律）。
 */
@Component
public class SceneSanitizer {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    SceneSanitizer.class);

    /**
     * 契约里是数字的字段（镜像 `scene.schema.json` 各分支的
     * `type: number / integer`，外加 `fontFamily` 这个数字枚举）。
     *
 * 为什么要这张表：模型会把数字写成字符串 —— 最典型的是
     * `"fontFamily":"1"`，因为契约原来把数字枚举渲染成
     * `fontFamily[1|2|3]`，与 `textAlign[left|center|right]`
     * 完全同形。实测"写"类场景（写诗 / 算术 / emoji / 纯符号）首轮
     * 4/4 全被 `非法 fontFamily` 打回。
     *
 * 数值字符串 → 数字是**零信息损失**的，所以在这里还原；
     * 而"数字字段给了非数字文本"（如 `"width":"宽一点"`）一律不动，
     * 那种情况校验器该打回就打回。
     *
 * 漂移守卫在 `SceneSanitizerTest`：它读 schema 断言这张表
     * 覆盖了所有数值属性 —— 契约加数值字段时会红。
     */
    static final Set<String> NUMERIC_FIELDS = Set.of(
            "x", "y", "width", "height", "angle",
            "strokeWidth", "roughness", "opacity",
            "fontSize", "fontFamily");

    /**
     * 类型名的**库内别名** → 契约名。
     *
 * 只有实测出现过的才收：`circle` 是模型从 Excalidraw 原生词汇里
     * 漏出来的（画猫那组首轮报 `非法 type: circle x2` —— 猫的眼睛和头
     * 在它看来"就是圆"）。映射到 `ellipse` 是**语义等价**的：
     * 圆本来就靠宽高相等来表达，宽高字段原样保留，画出来一模一样。
     *
 * 刻意不预撒网（`rect` / `square` / `tri` 都没收）：
     * 没收错的风险比少收大 —— 猜错了就是把用户要的图形换成了别的。
     * 契约本身已经写明"type 值 = 行首那个类型名"，这里只是兜住已知的滑落。
     */
    static final java.util.Map<String, String> TYPE_ALIASES =
            java.util.Map.of("circle", "ellipse");

    /**
     * 决策/分支语义词：矩形节点的 label 命中其中任一 ⇒ 改成菱形。
     * 模型常把判断点画成矩形（实测 flow 图「支付处理三选一分支」
     * 没有菱形判断点、state-machine 的状态分支也是矩形）。
     * 菱形是结构图里判断点的标准形态，改之零歧义。
     */
    static final Set<String> DECISION_HINTS = Set.of(
            "判断", "决策", "分支", "是否", "选择", "条件",
            "判定", "校验", "检查", "if", "?");

    /**
     * 分支结局词：节点 label 命中 ⇒ 也升级成菱形。
     * 这是用户对照图后明确点名的那类（「库存不足 / 支付失败 / 物流异常
     * 应为红菱形」）—— 它们本身是某条分支的结局 / 再路由点，
     * 画成矩形会让「这是一条岔路」看不出来。
     */
    static final Set<String> BRANCH_OUTCOME_HINTS = Set.of(
            "失败", "异常", "不足", "错误", "超时", "取消", "退回",
            "拒绝", "告警", "报警", "库存不足", "缺货");

    /** 语义配色兜底（仅在模型没写 color 时生效，零覆盖风险） */
    static final java.util.Map<String, String> COLOR_ERROR =
            java.util.Map.ofEntries(
                    java.util.Map.entry("异常", "red"),
                    java.util.Map.entry("失败", "red"),
                    java.util.Map.entry("不足", "red"),
                    java.util.Map.entry("取消", "red"),
                    java.util.Map.entry("超时", "red"),
                    java.util.Map.entry("退回", "red"),
                    java.util.Map.entry("错误", "red"),
                    java.util.Map.entry("拒绝", "red"),
                    java.util.Map.entry("告警", "red"),
                    java.util.Map.entry("报警", "red"),
                    java.util.Map.entry("fail", "red"),
                    java.util.Map.entry("error", "red"),
                    java.util.Map.entry("timeout", "red"),
                    java.util.Map.entry("cancel", "red"));
    static final java.util.Map<String, String> COLOR_DONE = java.util.Map.of(
            "完成", "blue", "结束", "blue", "终态", "blue",
            "done", "blue", "complete", "blue");
    static final java.util.Map<String, String> COLOR_WAIT = java.util.Map.of(
            "等待", "yellow", "进行中", "yellow", "处理中", "yellow",
            "审核中", "yellow", "pending", "yellow", "waiting", "yellow",
            "processing", "yellow");
    static final java.util.Map<String, String> COLOR_PARALLEL = java.util.Map.of(
            "并行", "purple");
    static final java.util.Map<String, String> COLOR_OK = java.util.Map.of(
            "正常", "green", "可用", "green", "成功", "green",
            "启用", "green", "ok", "green", "success", "green");
    static final java.util.Map<String, String> COLOR_HISTORY = java.util.Map.of(
            "历史", "gray", "归档", "gray", "旧", "gray",
            "archive", "gray");

    /** 严格的十进制数字（不接受 NaN / Infinity / 1e5 这类形态） */
    private static final java.util.regex.Pattern NUMERIC_TEXT =
            java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?");

    private final ObjectMapper objectMapper;

    /** 手工装配/单测沿用：自建一个（不共享 Spring 的 @Bean） */
    public SceneSanitizer() {
        this.objectMapper = new ObjectMapper();
    }

    /** Spring 装配：注入全局共享的 {@code @Bean ObjectMapper}（report 1.4 收敛） */
    @Autowired
    public SceneSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 零损失修复场景里"差一点"的字段。
     *
     * @param json 场景 JSON（应已做过 bind / dedup）
     * @return 修复后的 JSON；**没有任何改动时返回原字符串**
     *         （保证"没坏就不动"，也便于上游按引用相等判断）
     */
    public String sanitize(String json) {
        try {
            // ⓪ 先剥注释：JSON 里出现 `//` 会让**所有**下游（校验器、前端
            // JSON.parse）当场解析失败。注释不是数据，剥掉是彻头彻尾的无损，
            // 而且必须在这里做 —— 只在某一处放宽解析的话，注释会跟着
            // 下发 JSON 流到前端，照样炸。
            String source = stripJsonComments(json);
            boolean commentStripped = !source.equals(json);

            JsonNode root =
                    objectMapper.readTree(source);
            JsonNode elements = root.get("elements");
            if (elements == null
                    || !elements.isArray()) {
                return commentStripped ? source : json;
            }

            int repaired = 0;
            int dropped = 0;
            int coerced = 0;
            int unbound = 0;
            int aliased = 0;
            int rebased = 0;
            int inferredColor = 0;

            // 先收集全图 id：只有"两端都指向真实元素"的箭头才敢丢 points
            // （绑到不存在元素的箭头要让校验器照常报错，不能被这里掩盖）
            Set<String> knownIds = new java.util.HashSet<>();
            for (JsonNode element : elements) {
                JsonNode id = element.get("id");
                if (id != null && id.isTextual()) {
                    knownIds.add(id.asText());
                }
            }

            List<JsonNode> kept = new ArrayList<>();
            // ⓪b 重复框去重：children 完全相同的两个 frame 是同一个框画了
            // 两遍 —— 模型给同一批成员同时写了「正确的名字」和「错的名字」，
            // 例 `frame_1:notification{awaiting_notification,notified}` 与
            // `frame_4:processing{...同一批...}`。两个框被画在同一位置，
            // 标题字逐字压印，渲染出来是乱码（2026-10-18 state-machine
            // 实测：三个框标题显示成 `prdeessing`/`poinfessing`/
            // `pardeessing`，就是 `processing` 三个字形互相叠加）。
            // 保留**先出现**的那个：模型给的顺序即其意图顺序，且它通常
            // 就是名字对得上内容的那个。
            Set<String> keptFrameChildren = new java.util.HashSet<>();
            for (JsonNode element : elements) {
                if (!element.isObject()) {
                    kept.add(element);
                    continue;
                }
                ObjectNode obj = (ObjectNode) element;

                // ⓪b 续：真正丢掉被判定为重复的那个框（判定已在上面的
                // 预扫描里算完，这里只做去重执行，保证「谁先出现谁留下」）
                if ("frame".equals(obj.path("type").asText())) {
                    JsonNode childs = obj.get("children");
                    if (childs != null && childs.isArray()
                            && !childs.isEmpty()) {
                        List<String> names = new ArrayList<>();
                        for (JsonNode c : childs) {
                            names.add(c.asText());
                        }
                        java.util.Collections.sort(names);
                        if (!keptFrameChildren.add(
                                String.join("\u0000", names))) {
                            dropped++;
                            continue;
                        }
                    }
                }

                // ① 类型名用了库内别名 → 回归契约名
                JsonNode type = obj.get("type");
                if (type != null && type.isTextual()
                        && TYPE_ALIASES.containsKey(type.asText())) {
                    obj.put("type",
                            TYPE_ALIASES.get(type.asText()));
                    aliased++;
                }

                // ①b 决策 / 分支点画成菱形（矩形 + 命中语义词 ⇒ diamond）。
                // 背景：模型把判断点画成矩形（实测 flow 图「支付处理三选一
                // 分支」没有菱形判断点），菱形是结构图里判断点的标准形态。
                if (type != null && "rectangle".equals(type.asText())
                        && isDecisionLabel(obj)) {
                    obj.put("type", "diamond");
                    aliased++;
                }

                // ② 数字字段被写成字符串 → 还原成数字（零信息损失）
                for (String field : NUMERIC_FIELDS) {
                    JsonNode value = obj.get(field);
                    if (value == null
                            || !value.isTextual()
                            || !NUMERIC_TEXT.matcher(
                                    value.asText().trim()).matches()) {
                        continue;
                    }
                    String text = value.asText().trim();
                    if (text.contains(".")) {
                        obj.put(field, Double.parseDouble(text));
                    } else {
                        obj.put(field, Long.parseLong(text));
                    }
                    coerced++;
                }

                // ③ 已绑定箭头上的 points → 只留"真折线"，其余摘掉。
                // 判据是**有没有中间折点**：points 只有首尾两点时它不是折线，
                // 而是模型自己脑算的一条直线端点，而它**算不准** ——
                // 实测 20 个用例里 17 个都给绑定箭头写了 points，
                // 其中 18 处的端点落在"目标图形的正中心"而不是边缘
                // （h_sequence 的 user 框高 50，端点 y 写的就是框中心 75），
                // 渲染层 hasXY && hasPoints 时原样透传 ⇒ 箭头插进框里、
                // 正好压住框内 label。同一批里 h_large 反而算对了 ——
                // 同一个模型有时对有时错，因为它看不见画布。
                // 所以这里把"端点接在哪"交回渲染器（它取边缘交点，永远准），
                // 只把"怎么绕"留给模型：points ≥ 3 个点才说明它真的要转弯。
                JsonNode pts = obj.get("points");
                if (bindsToRealElements(obj, knownIds)
                        && pts != null
                        && (!pts.isArray() || pts.size() < 3)) {
                    obj.remove("points");
                    unbound++;
                }

                // ④ points 写成了绝对坐标 → 减回相对坐标
                // （排在 ③ 之后：绑定箭头刚摘掉 points，这里自然跳过）
                if (rebaseAbsolutePoints(obj)) {
                    rebased++;
                }

                // ⑤ label 归一化
                JsonNode label = obj.get("label");
                if (label == null || label.isNull()) {
                    // ⑤b 没 label 时仍补语义配色（节点 id/type 也能透露结局）
                    if (inferColor(obj, nodeName(obj))) {
                        inferredColor++;
                    }
                    kept.add(obj);
                    continue;
                }
                if (label.isTextual()
                        && !label.asText().isBlank()) {
                    // 直写字符串 → 包成对象（契约形态），信息一字不差
                    obj.putObject("label")
                            .put("text", label.asText());
                    repaired++;
                } else if (!isUsableLabel(label)) {
                    obj.remove("label");
                    dropped++;
                }
                // ⑤b 语义配色兜底：模型漏写 color 时按 label / id 推断，
                // 不覆盖已显式写对的 color（零覆盖风险）。
                if (inferColor(obj, nodeName(obj))) {
                    inferredColor++;
                }
                kept.add(obj);
            }

            if (repaired == 0 && dropped == 0
                    && coerced == 0 && unbound == 0
                    && aliased == 0 && rebased == 0
                    && inferredColor == 0) {
                return commentStripped ? source : json;
            }
            ((ObjectNode) root).set(
                    "elements", toArrayNode(kept));
            LOG.info("宽容归一化：label 补齐 {} 处、空壳/非法丢弃 {} 处、"
                            + "数字字符串还原 {} 处、绑定箭头摘非折线 points {} 处、"
                            + "类型别名归一 {} 处、绝对坐标 points 回落 {} 处、"
                            + "语义配色 {} 处、剥注释 {} 处",
                    repaired, dropped, coerced, unbound,
                    aliased, rebased, inferredColor, commentStripped ? 1 : 0);
            return objectMapper
                    .writeValueAsString(root);
        } catch (Exception e) {
            LOG.warn("宽容归一化跳过（解析失败）: {}",
                    e.getMessage());
            return json;
        }
    }

    /**
     * 剥掉 JSON 里的 `//` 行注释与 `/* *``/` 块注释。
     *
 * 必须按字符串字面量状态机扫，不能用正则。最典型的反例是
     * `"url":"https://example.com"` —— 正则见到 `//` 就会把
     * 后半行当注释砍掉，把好好的 URL 截成 `"https:`，从"注释导致解析
     * 失败"变成"静默改坏数据"，比原问题更糟。
     */
    static String stripJsonComments(String text) {
        if (text.indexOf("//") < 0 && text.indexOf("/*") < 0) {
            return text;
        }
        StringBuilder out =
                new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    while (i < text.length()
                            && text.charAt(i) != '\n') {
                        i++;
                    }
                    // 留一个换行，别把被注释隔开的两行粘成一行
                    out.append('\n');
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < text.length()
                            && !(text.charAt(i) == '*'
                            && text.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 这个元素是不是"两端都绑定到本场景真实元素"的箭头。
     *
 * 只有这种箭头上的 `points` 才敢摘：契约规定
     * `start/end` 与 `points` 二选一（校验器会给并存判错、
     * 也会给长度 < 2 的 points 判错），而前端的绑定箭头几何是从绑定推导的
     * （`sceneAdapter.withArrowGeometry` 只补缺、不做二次加工），
     * 所以摘掉不改变最终观感。
     *
 * 反过来，绑定指向不存在的元素时**必须留着 points 不动** ——
     * 那是真错误，得让校验器照常报出来进入下一轮，不能被这里顺手掩盖。
     */
    private static boolean bindsToRealElements(
            JsonNode elem, Set<String> knownIds) {
        if (!"arrow".equals(elem.path("type").asText())) {
            return false;
        }
        return resolves(elem.get("start"), knownIds)
                && resolves(elem.get("end"), knownIds);
    }

    private static boolean resolves(
            JsonNode binding, Set<String> knownIds) {
        if (binding == null || !binding.isObject()) {
            return false;
        }
        JsonNode id = binding.get("id");
        return id != null && id.isTextual()
                && knownIds.contains(id.asText());
    }

    /**
     * 把「用绝对坐标写的 points」减回相对坐标。
     *
 * 为什么需要它：`points` 的语义是相对本元素
     * (x,y) 原点的偏移（Excalidraw 约定，schema 的 `point`
     * description 也这么写着），但模型会把绝对坐标写进去，
     * 同时把 `x / y` 也设成第一个点 —— 于是最终落点
     * = `(x+dx, y+dy)`，等于坐标翻倍。实测画猫那组
     * （`illustration`）9 条线全被甩到视野外、整张图只剩一个
     * 孤零零的文字，而校验器 `checkPointsShape` 只检查每个点
     * 是不是两个数字、不检查首点，所以它一路静默通过，
     * 连打回都没有。
     *
 * 触发条件故意卡得极死：`points[0]` 与 `(x,y)`
     * 完全相等。这是"用绝对坐标写"这个写法的指纹 —— 模型把元素
     * 原点当成路径起点，两者才会一模一样。这个条件把误判面压到最小：
     *
 * - 正常写相对坐标（首点 `[0,0]`）而 x/y 非 0 → 不等，
     *       不动；
     *
 * - x/y 恰为 0 且首点 `[0,0]` → 相等，但减法结果就是
     *       `[0,0]`，改了等于没改（下面直接返回 false）；
     *
 * - 首点既不是 `[0,0]` 也不等于 `(x,y)` → 不动，
     *       那是别的问题，留给校验器照常报出来。
     *
 * 它是七条里唯一一条带推断的：其余六条都是"同一件事的
     * 两种写法"，这条是"猜模型想画在哪"。之所以还是收下，是因为另一侧
     * （放任翻倍）的代价是整张图看不见 —— 实测发生过，而且
     * 没有任何下游能发现。也正因为它是唯一有误改风险的规则，才必须
     * 靠上述铁证触发，并在日志里单独计数。
     *
     * @return 真的改动了才返回 true（没改 → false）
     */
    private static boolean rebaseAbsolutePoints(ObjectNode obj) {
        JsonNode points = obj.get("points");
        JsonNode xNode = obj.get("x");
        JsonNode yNode = obj.get("y");
        if (points == null || !points.isArray()
                || points.size() < 2
                || xNode == null || !xNode.isNumber()
                || yNode == null || !yNode.isNumber()) {
            return false;
        }
        double ox = xNode.asDouble();
        double oy = yNode.asDouble();
        if (ox == 0 && oy == 0) {
            return false;
        }

        // 每个点都必须是有限的两个数字，否则认不出来 → 整条不动
        for (JsonNode point : points) {
            if (!isFinitePair(point)) {
                return false;
            }
        }

        // 指纹：首点 == 元素原点。两边都是模型从同一份坐标里抄下来的
        // 字面量，不存在计算误差，所以直接比浮点。
        JsonNode first = points.get(0);
        if (first.get(0).asDouble() != ox
                || first.get(1).asDouble() != oy) {
            return false;
        }

        ArrayNode rebuilt = JsonNodeFactory.instance.arrayNode();
        for (JsonNode point : points) {
            ArrayNode pair = rebuilt.addArray();
            appendNumber(pair, point.get(0).asDouble() - ox);
            appendNumber(pair, point.get(1).asDouble() - oy);
        }
        obj.set("points", rebuilt);
        return true;
    }

    /** 是 `[有限数字, 有限数字]` 才算合法坐标 */
    private static boolean isFinitePair(JsonNode point) {
        if (point == null || !point.isArray() || point.size() < 2) {
            return false;
        }
        return isFiniteNumber(point.get(0))
                && isFiniteNumber(point.get(1));
    }

    private static boolean isFiniteNumber(JsonNode node) {
        return node != null && node.isNumber()
                && Double.isFinite(node.asDouble());
    }

    /**
     * 整数写整数：不然 `[0,0]` 会序列化成 `[0.0,0.0]`，
     * 白白让下发的 JSON 变啰嗦（新坐标全是整数减法，结果九成是整数）。
     */
    private static void appendNumber(ArrayNode target, double value) {
        if (value == Math.floor(value)) {
            target.add((long) value);
        } else {
            target.add(value);
        }
    }

    /** label 是对象且 text 是非空字符串 —— 契约认可的唯一形态 */
    private static boolean isUsableLabel(JsonNode label) {
        if (!label.isObject()) {
            return false;
        }
        JsonNode text = label.get("text");
        return text != null
                && text.isTextual()
                && !text.asText().isBlank();
    }

    private static ArrayNode toArrayNode(
            List<JsonNode> elements) {
        ArrayNode array =
                JsonNodeFactory.instance.arrayNode();
        for (JsonNode element : elements) {
            array.add(element);
        }
        return array;
    }

    /**
     * 节点 label 文本（兼容直接字符串与对象两种写法）。
     */
    private static String nodeName(ObjectNode obj) {
        String text = labelText(obj.get("label"));
        return text == null ? "" : text;
    }

    /**
     * 取一个「显示名」字段里的文本：既认裸字符串，也认
     * {@code {"text": "..."}} 这种对象写法（label 与 frame 的 name 都会
     * 出现这两种形态）。取不到（null / 非文本 / 空对象）返回 {@code null}，
     * 由调用方决定这是不是「没有名字」。
     */
    private static String labelText(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            JsonNode text = node.get("text");
            return text != null && text.isTextual() ? text.asText() : null;
        }
        return null;
    }

    /**
     * 矩形是否实为决策点：label 命中 DECISION_HINTS 或 BRANCH_OUTCOME_HINTS。
     * 命中 ⇒ 调方应升级成菱形。
     */
    private static boolean isDecisionLabel(ObjectNode obj) {
        if (!"rectangle".equals(obj.path("type").asText())) {
            return false;
        }
        String name = nodeName(obj);
        if (name.isEmpty()) {
            return false;
        }
        for (String hint : DECISION_HINTS) {
            if (name.contains(hint)) {
                return true;
            }
        }
        for (String hint : BRANCH_OUTCOME_HINTS) {
            if (name.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 语义配色兜底（只在模型没写 color 时生效）。
     *
     * <p>背景：实测 state-machine / flow 图所有节点 color=null，
     * 模型完全忽略配色语义（红=异常/失败、蓝=终态、黄=进行中、
     * 紫=并行、绿=正常、灰=历史）。这里按 label / id 文本推断，
     * 不覆盖已写对的 color —— 零覆盖风险。
     *
     * <p>优先级：错误类 &gt; 终态类 &gt; 等待类 &gt; 并行类 &gt; 正常类 &gt;
     * 历史类；首个命中即停。
     *
     * @return 真的改了 color 才返回 true
     */
    private boolean inferColor(ObjectNode obj, String name) {
        if (obj.has("color") || name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (java.util.Map.Entry<String, String> e
                : COLOR_ERROR.entrySet()) {
            if (name.contains(e.getKey()) || lower.contains(e.getKey())) {
                obj.put("color", e.getValue());
                return true;
            }
        }
        for (java.util.Map.Entry<String, String> e
                : COLOR_DONE.entrySet()) {
            if (name.contains(e.getKey()) || lower.contains(e.getKey())) {
                obj.put("color", e.getValue());
                return true;
            }
        }
        for (java.util.Map.Entry<String, String> e
                : COLOR_WAIT.entrySet()) {
            if (name.contains(e.getKey()) || lower.contains(e.getKey())) {
                obj.put("color", e.getValue());
                return true;
            }
        }
        for (java.util.Map.Entry<String, String> e
                : COLOR_PARALLEL.entrySet()) {
            if (name.contains(e.getKey()) || lower.contains(e.getKey())) {
                obj.put("color", e.getValue());
                return true;
            }
        }
        for (java.util.Map.Entry<String, String> e
                : COLOR_OK.entrySet()) {
            if (name.contains(e.getKey()) || lower.contains(e.getKey())) {
                obj.put("color", e.getValue());
                return true;
            }
        }
        for (java.util.Map.Entry<String, String> e
                : COLOR_HISTORY.entrySet()) {
            if (name.contains(e.getKey()) || lower.contains(e.getKey())) {
                obj.put("color", e.getValue());
                return true;
            }
        }
        return false;
    }
}
