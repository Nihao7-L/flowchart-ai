package io.github.nihaoljx.flowchart.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 场景宽容归一化（2026-10-07）
 *
 * <p><b>它解决的问题：</b>模型会输出"格式差一点、但意图明确"的字段，
 * 而 {@link GraphValidator} 是按**契约字面**判的 —— 差一点就整轮打回，
 * 于是白白多跑一次 LLM（一次大图调用 50 秒起）。实测中等用例第 1 轮
 * 5 个箭头全带空 label、简单用例也偶发，`label 缺少非空 text` 一度
 * 是首轮校验失败的第一大类。
 *
 * <p><b>判据：只做「零信息损失」的修复，其余一律不动。</b>
 * 这一条边界比"能修多少"重要 —— 这里每多修一种，就多一个
 * "用户看到的图和模型想画的图不一样"的隐蔽风险，而提示词口径修好
 * 之后模型本来就会写对。目前收七条，都有实测来源：
 * <ol>
 *   <li><b>JSON 里的注释</b> → 剥掉。模型会写 {@code // 解释}，实测已踩两次
 *       （大图 { "画一万个矩形" } 与"改图但无上下文"）。注释不是数据，
 *       剥掉是最彻底的无损；而且必须在**这里**剥 —— 只在校验器里放宽解析
 *       的话，注释会跟着下发 JSON 流到前端，被严格 {@code JSON.parse} 拒掉。
 *       实现是字符串字面量状态机，不能用正则（{@code "url":"https://x"}
 *       会被 {@code //} 截断成 {@code "https:}，反而静默改坏数据）。</li>
 *   <li><b>类型名用了库内别名</b> {@code circle} → {@code ellipse}。模型从
 *       Excalidraw 原生词汇里漏出来的（画猫时眼睛/头"就是圆"），实测首轮报
 *       {@code 非法 type: circle x2}。别名表刻意不预撒网，见 {@link #TYPE_ALIASES}。</li>
 *   <li><b>数字写成字符串</b> {@code "fontFamily":"1"} → {@code 1}。
 *       契约原来把数字枚举渲染成 {@code fontFamily[1|2|3]}，与
 *       {@code textAlign[left|center|right]} 同形，模型照抄就写成字符串，
 *       而校验器要求 {@code isInt()} —— 实测"写"类场景（写诗 / 算术 /
 *       emoji / 纯符号）首轮 <b>4/4</b> 全挂在"非法 fontFamily"。</li>
 *   <li><b>已绑定箭头上的 points</b> → 只留真折线（≥ 3 个点），其余摘掉。
 *       契约规定 {@code start/end} 与 {@code points} 二选一；模型会留一个
 *       空/短的 points 数组，实测首轮报 {@code points 数组长度 < 2}。
 *       v10.0.0 放开折线后发现更要命的一种：首尾两点的"直线端点"，
 *       模型把它算成目标图形<b>中心</b>而不是边缘（详见下方那一段）。</li>
 *   <li><b>label 直写字符串</b> {@code label: "文字"} →
 *       {@code label: {"text":"文字"}}。信息完全等价；契约要求对象形态
 *       是因为直写字符串时 Excalidraw 会**静默丢掉这段文字**
 *       （2026-09-16 实测），所以必须转成对象，而不是原样放过。</li>
 *   <li><b>label 空壳</b> {@code {"text":""}} / {@code {"text":"  "}} /
 *       {@code {}} / 数字 / 布尔 / 数组 → <b>整个键删掉</b>。空 label 的语义
 *       就是"这个元素没有标签"，删掉等于把话说清楚；留着才会被校验器打回
 *       （实测中等用例首轮 5 个箭头全带空 label）。</li>
 *   <li><b>points 按绝对坐标写</b> → 减回相对坐标（见
 *       {@link #rebaseAbsolutePoints}）。这是七条里<b>唯一一条不是纯信息
 *       等价</b>的 —— 它是对"模型想画在哪"的一次推断，所以触发条件卡得
 *       极死（首点与 x/y <b>完全相等</b>才算），宁可漏修，绝不误改。</li>
 * </ol>
 *
 * <p><b>"已绑定箭头上的 points"那条的边界要说清楚，因为它不像其余几条那样一眼等价：</b>
 * 契约的语义模型里，绑定箭头的路径是<b>从绑定推导</b>的 ——
 * "绑两端就只写 start/end"这句话的等价表述就是
 * "绑定箭头没有自己的路径"。所以 LLM 输出里绑定箭头上的 {@code points}
 * 不是一种意图，而是冗余残留，摘掉在<b>契约层面</b>无损。
 *
 * <p>v10.0.0 把它放宽成"≥ 2 个点就留下"，随后被实测打回来：模型写两点时
 * 想表达的是"从 A 连到 B"，但它把端点算成了<b>框中心</b>。20 个用例里
 * 17 个给绑定箭头写了 points，其中 18 处端点插在框内部（离边 20~50px），
 * 渲染层原样透传 ⇒ 箭头穿刺、压住框内文字。同一批里 {@code h_large} 的
 * 15 条却都算对了 —— 同一个模型有时对有时错，因为它看不见画布。
 * 所以口径收回成"<b>有没有中间折点</b>"：首尾两点不算折线（那是它在脑算
 * 端点，还会算错），≥ 3 个点才是它真的要转弯。接哪条边交给渲染器算
 * （前端 {@code edgePointTowards} 取包围盒朝向对方的边缘交点，永远准），
 * 模型只负责"连谁"和"怎么绕"。
 *
 * <p>反过来说，用户画布上<b>已有</b>的元素不受这里影响：{@code sanitize}
 * 只在 LLM 原始输出那一条链路上（{@code bind → dedup → sanitize}），
 * Excalidraw 回传的场景不经过它。
 *
 * <p><b>位置：</b>必须排在 {@code bind / dedup} 之后、{@link GraphValidator}
 * 之前 —— 校验器判的是它拿到的 JSON，修复必须发生在判定之前，
 * 而且它改的必须是**最终要下发的**那份 JSON（否则校验过了、
 * 交付的还是坏的那份，等于白修）。
 *
 * <p>任何异常都**原样返回**：归一化是锦上添花，不能变成新的故障点
 * （与 {@link SceneDeduper} 同一条纪律）。
 */
@Component
public class SceneSanitizer {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    SceneSanitizer.class);

    /**
     * 契约里是数字的字段（镜像 {@code scene.schema.json} 各分支的
     * {@code type: number / integer}，外加 {@code fontFamily} 这个数字枚举）。
     *
     * <p>为什么要这张表：模型会把数字写成字符串 —— 最典型的是
     * {@code "fontFamily":"1"}，因为契约原来把数字枚举渲染成
     * {@code fontFamily[1|2|3]}，与 {@code textAlign[left|center|right]}
     * 完全同形。实测"写"类场景（写诗 / 算术 / emoji / 纯符号）首轮
     * <b>4/4</b> 全被 {@code 非法 fontFamily} 打回。
     *
     * <p>数值字符串 → 数字是**零信息损失**的，所以在这里还原；
     * 而"数字字段给了非数字文本"（如 {@code "width":"宽一点"}）一律不动，
     * 那种情况校验器该打回就打回。
     *
     * <p>漂移守卫在 {@code SceneSanitizerTest}：它读 schema 断言这张表
     * 覆盖了所有数值属性 —— 契约加数值字段时会红。
     */
    static final Set<String> NUMERIC_FIELDS = Set.of(
            "x", "y", "width", "height", "angle",
            "strokeWidth", "roughness", "opacity",
            "fontSize", "fontFamily");

    /**
     * 类型名的**库内别名** → 契约名。
     *
     * <p>只有实测出现过的才收：{@code circle} 是模型从 Excalidraw 原生词汇里
     * 漏出来的（画猫那组首轮报 {@code 非法 type: circle x2} —— 猫的眼睛和头
     * 在它看来"就是圆"）。映射到 {@code ellipse} 是**语义等价**的：
     * 圆本来就靠宽高相等来表达，宽高字段原样保留，画出来一模一样。
     *
     * <p>刻意不预撒网（{@code rect} / {@code square} / {@code tri} 都没收）：
     * 没收错的风险比少收大 —— 猜错了就是把用户要的图形换成了别的。
     * 契约本身已经写明"type 值 = 行首那个类型名"，这里只是兜住已知的滑落。
     */
    static final java.util.Map<String, String> TYPE_ALIASES =
            java.util.Map.of("circle", "ellipse");

    /** 严格的十进制数字（不接受 NaN / Infinity / 1e5 这类形态） */
    private static final java.util.regex.Pattern NUMERIC_TEXT =
            java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?");

    private final ObjectMapper objectMapper =
            new ObjectMapper();

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
            for (JsonNode element : elements) {
                if (!element.isObject()) {
                    kept.add(element);
                    continue;
                }
                ObjectNode obj = (ObjectNode) element;

                // ① 类型名用了库内别名 → 回归契约名
                JsonNode type = obj.get("type");
                if (type != null && type.isTextual()
                        && TYPE_ALIASES.containsKey(type.asText())) {
                    obj.put("type",
                            TYPE_ALIASES.get(type.asText()));
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
                //    判据是**有没有中间折点**：points 只有首尾两点时它不是折线，
                //    而是模型自己脑算的一条直线端点，而它**算不准** ——
                //    2026-10-09 实测 20 个用例里 17 个都给绑定箭头写了 points，
                //    其中 18 处的端点落在"目标图形的正中心"而不是边缘
                //    （h_sequence 的 user 框高 50，端点 y 写的就是框中心 75），
                //    渲染层 hasXY && hasPoints 时原样透传 ⇒ 箭头插进框里、
                //    正好压住框内 label。同一批里 h_large 反而算对了 ——
                //    同一个模型有时对有时错，因为它看不见画布。
                //    所以这里把"端点接在哪"交回渲染器（它取边缘交点，永远准），
                //    只把"怎么绕"留给模型：points ≥ 3 个点才说明它真的要转弯。
                JsonNode pts = obj.get("points");
                if (bindsToRealElements(obj, knownIds)
                        && pts != null
                        && (!pts.isArray() || pts.size() < 3)) {
                    obj.remove("points");
                    unbound++;
                }

                // ④ points 写成了绝对坐标 → 减回相对坐标
                //    （排在 ③ 之后：绑定箭头刚摘掉 points，这里自然跳过）
                if (rebaseAbsolutePoints(obj)) {
                    rebased++;
                }

                // ⑤ label 归一化
                JsonNode label = obj.get("label");
                if (label == null || label.isNull()) {
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
                kept.add(obj);
            }

            if (repaired == 0 && dropped == 0
                    && coerced == 0 && unbound == 0
                    && aliased == 0 && rebased == 0) {
                return commentStripped ? source : json;
            }
            ((ObjectNode) root).set(
                    "elements", toArrayNode(kept));
            LOG.info("宽容归一化：label 补齐 {} 处、空壳/非法丢弃 {} 处、"
                            + "数字字符串还原 {} 处、绑定箭头摘非折线 points {} 处、"
                            + "类型别名归一 {} 处、绝对坐标 points 回落 {} 处、"
                            + "剥注释 {} 处",
                    repaired, dropped, coerced, unbound,
                    aliased, rebased, commentStripped ? 1 : 0);
            return objectMapper
                    .writeValueAsString(root);
        } catch (Exception e) {
            LOG.warn("宽容归一化跳过（解析失败）: {}",
                    e.getMessage());
            return json;
        }
    }

    /**
     * 剥掉 JSON 里的 {@code //} 行注释与 {@code /* *}{@code /} 块注释。
     *
     * <p><b>必须按字符串字面量状态机扫，不能用正则。</b>最典型的反例是
     * {@code "url":"https://example.com"} —— 正则见到 {@code //} 就会把
     * 后半行当注释砍掉，把好好的 URL 截成 {@code "https:}，从"注释导致解析
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
     * <p>只有这种箭头上的 {@code points} 才敢摘：契约规定
     * {@code start/end} 与 {@code points} <b>二选一</b>（校验器会给并存判错、
     * 也会给长度 &lt; 2 的 points 判错），而前端的绑定箭头几何是从绑定推导的
     * （{@code sceneAdapter.withArrowGeometry} 只补缺、不做二次加工），
     * 所以摘掉不改变最终观感。
     *
     * <p>反过来，绑定指向不存在的元素时**必须留着 points 不动** ——
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
     * <p><b>为什么需要它：</b>{@code points} 的语义是<b>相对本元素
     * (x,y) 原点的偏移</b>（Excalidraw 约定，schema 的 {@code point}
     * description 也这么写着），但模型会把<b>绝对坐标</b>写进去，
     * 同时把 {@code x / y} 也设成第一个点 —— 于是最终落点
     * = {@code (x+dx, y+dy)}，等于坐标翻倍。实测画猫那组
     * （{@code illustration}）9 条线全被甩到视野外、整张图只剩一个
     * 孤零零的文字，而校验器 {@code checkPointsShape} 只检查每个点
     * 是不是两个数字、<b>不检查首点</b>，所以它一路静默通过，
     * 连打回都没有。
     *
     * <p><b>触发条件故意卡得极死：{@code points[0]} 与 {@code (x,y)}
     * 完全相等。</b>这是"用绝对坐标写"这个写法的指纹 —— 模型把元素
     * 原点当成路径起点，两者才会一模一样。这个条件把误判面压到最小：
     * <ul>
     *   <li>正常写相对坐标（首点 {@code [0,0]}）而 x/y 非 0 → 不等，
     *       不动；</li>
     *   <li>x/y 恰为 0 且首点 {@code [0,0]} → 相等，但减法结果就是
     *       {@code [0,0]}，改了等于没改（下面直接返回 false）；</li>
     *   <li>首点既不是 {@code [0,0]} 也不等于 {@code (x,y)} → 不动，
     *       那是别的问题，留给校验器照常报出来。</li>
     * </ul>
     *
     * <p><b>它是七条里唯一一条带推断的</b>：其余六条都是"同一件事的
     * 两种写法"，这条是"猜模型想画在哪"。之所以还是收下，是因为另一侧
     * （放任翻倍）的代价是<b>整张图看不见</b> —— 实测发生过，而且
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

        ArrayNode rebuilt = new ObjectMapper().createArrayNode();
        for (JsonNode point : points) {
            ArrayNode pair = rebuilt.addArray();
            appendNumber(pair, point.get(0).asDouble() - ox);
            appendNumber(pair, point.get(1).asDouble() - oy);
        }
        obj.set("points", rebuilt);
        return true;
    }

    /** 是 {@code [有限数字, 有限数字]} 才算合法坐标 */
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
     * 整数写整数：不然 {@code [0,0]} 会序列化成 {@code [0.0,0.0]}，
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
                new ObjectMapper().createArrayNode();
        for (JsonNode element : elements) {
            array.add(element);
        }
        return array;
    }
}
