package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nihaoljx.flowchart.graph.ValidationIssue;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 内容服务（v2-8 / 路线2 重写；v2-33 补"编辑模式"；v2-41 拆静态/动态两块；
 * 2026-09-24 静态层正文改为**直接读 md 文件**；同日补 v2-42 编辑模式增量）
 *
 * <p><b>静态层唯一正文来源 = {@code prompts/whiteboard/system-prompt.md}</b>
 * （用户 2026-09-24 拍板「以 system-prompt.md 为准」；2026-09-27 合并掉
 * {@code system-prompt-large.md}，静态层从此只有这一份 md）。运行时**不再维护第二份
 * Java 硬编码文案** —— 改提示词只改那一份 md，从此不存在"两份文本互相漂移"。
 *
 * <p>本类职责：
 * <ul>
 *   <li>{@link #buildSystemBlock()} —— md 正文 + {@link #buildContractBlock()}
 *       生成的契约段（契约源仍是 {@code schemas/scene.schema.json}，md 里声明
 *       "契约每轮由运行时注入"）。每轮完全不变，进 system 消息、且是
 *       KV-cache 的稳定前缀；</li>
 *   <li>{@link #buildUserBlock(String, String)} —— 当前场景 + 修改规则 + 用户需求（每轮变化，进 user 消息）。
 *       大图（> {@value #EDIT_INLINE_MAX_ELEMENTS} 元素）走 v2-42 增量投喂：只给结构摘要，
 *       不再整份塞场景；</li>
 *   <li>{@link #buildFixBlock(List)} —— 修正轮追加的校验 issues。</li>
 * </ul>
 * 装配由 {@code context.ContextAssembler} 负责（排序 / 裁剪 / 落盘）；本类只产出文案。
 *
 * <p>版本号等元数据**不进正文**（否则每轮前缀变化、KV-cache 失效）。
 */
@Service
public class PromptService {

    /** 静态层提示词正文（唯一真相源，常驻核心） */
    private static final String SYSTEM_PROMPT_PATH =
            "prompts/whiteboard/system-prompt.md";

    /** 契约文件（白板内容契约唯一真相源） */
    private static final String SCHEMA_PATH = "schemas/scene.schema.json";

    /**
     * v2-42：编辑模式下元素数**超过**此值，user 块改走「结构摘要 + 被改元素全文 + 引用骨架」。
     *
     * <p>动机：大图整份塞进 prompt 有两个代价 —— token 开销线性增长、
     * 且"当前场景"段过长会稀释模型对「用户需求」的注意力（Lost in the Middle）。
     * 30 这个数字的判据是"整份 JSON 大约 3~5 KB"，再往上就开始明显拖慢并干扰。
     */
    public static final int EDIT_INLINE_MAX_ELEMENTS = 30;

    /**
     * md 里的 HTML 注释（只给人和 CI 看的内容）。{@code DOTALL} + 非贪婪：
     * 注释可能跨多行，但绝不能一口吞到下一个注释的结尾。
     */
    private static final java.util.regex.Pattern COMMENT_PATTERN =
            java.util.regex.Pattern.compile("(?s)<!--.*?-->");

    /**
     * 契约段要列举的元素分支（{@code scene.schema.json} 里 {@code definitions} 的键）。
     * 按"模型实际会用到"排序；{@code image} / {@code magicframe} 属前向兼容、当前不产出，
     * 列出来只会稀释注意力，故不纳入（md 的「能力与边界」里已交代它们）。
     */
    static final String[] CONTRACT_BRANCHES = {
        "shape", "arrow", "line", "text", "frame", "freedraw", "embeddable"
    };

    /**
     * 字段表里**不列**的键：它们由后端按语义填，写出来反而会被打回。
     * 名字仍然会出现在段尾那句「渲染层字段不要输出」里 —— 契约与提示词口径不冲突，
     * 也不会漏信息（漂移守卫正是靠"名字出现过"来兜底的）。
     */
    private static final java.util.Set<String> SKIP_IN_TABLE =
            java.util.Set.of("children", "roundness", "autoResize",
                    "startArrowhead", "endArrowhead", "verticalAlign");

    /**
     * 单条补充口径（渲染不出来自 schema，是规则，不是形状）。
     *
     * <p>⚠️ 2026-10-07 补 arrow 一条：契约的字段表把 {@code points / start / end}
     * 并排列出，读起来像"三个都能给"，而校验器卡的是<b>二选一</b>。实测中等用例
     * 第 1 轮 5 个箭头全犯（{@code 共 10 个问题}，一半是这条）—— md 的「自检」
     * 里虽写了二选一，但模型填字段时看的是这里。字段级规则就跟在字段后面，
     * 与 {@code line} 那条同理。
     */
    private static final java.util.Map<String, String> BRANCH_NOTES =
            java.util.Map.of("line", "（禁止 start/end 绑定 —— 连图形用 arrow；"
                            + "伪 3D 的面就靠它，closed + backgroundColor + fillStyle 才填得上色）",
                    "arrow", "（绑了两端就只写 start/end，不要再写 points"
                            + "—— 接哪条边由渲染器自己算，比手算准；"
                            + "要绕开别的框才给 points 写 ≥3 个点：首点 [0,0]，"
                            + "其余是相对首点的位移，这时必须同时带 start/end；"
                            + "start/end 只能绑 rectangle / ellipse / diamond / text，"
                            + "绝不要绑 frame / magicframe —— 绑了整张图会渲染成空白）",
                    "text", "（只作独立标注 / 标题：流程 / 架构 / 关系图的节点"
                            + "要用图形 + label 装名字。文字没有宽高，箭头绑上去会"
                            + "从字中间穿过去，节点会糊成一团）",
                    "embeddable", "（箭头不要绑它，绑了画不出线）",
                    "frame", "（分区底板 / 容器框（如把一整列框起来的浅色背景）。"
                            + "框的成员由后端按「图形的 group 与框的 name 同名」算出 —— 你只需给框写 name，"
                            + "containerId 只把文字挂进图形、不表归属；框左上角必定显示灰色标题且无法隐藏，"
                            + "别在框上再叠一个重复的文字标题。箭头不要绑它，"
                            + "绑了整张图会渲染成空白）");

    /**
     * 字段级提示：跟在字段名后面渲染成 {@code angle(弧度不是角度)}。
     *
     * <p><b>为什么要有这张表：</b>契约段是按 schema 现算的，只渲染「名字 + 类型
     * + 枚举」，schema 里的 {@code description} 一律不进提示词（省 token）。
     * 于是那些**光看字段名不够、又不像枚举那样能自解释**的字段就成了坑：
     * <ul>
     *   <li>{@code angle} —— schema 写着「弧度，默认 0」，但模型看不到这句，
     *       按直觉写 {@code 45}。而前端 / Excalidraw 都按弧度解释，
     *       45 rad ≈ 58.4°，图形被转到莫名其妙的角度（2026-10-07 iso-3d
     *       实测：6 个矩形全被转歪，等轴测房子糊成一团）。</li>
     *   <li>{@code backgroundColor} —— 契约里 {@code fillStyle} 是个枚举，
     *       模型以为「给了纹理就等于填了」，于是只给 {@code fillStyle} 不给
     *       {@code backgroundColor} → 前端等于没填充 → 整张图纯黑线，
     *       用户明确要的「不同面同色系深浅」完全没实现。</li>
     * </ul>
     *
     * <p>写法守住两条：① 只写**看名字绝对猜不到**的那一点，不去重复 md 已有的
     * 内容；② 短到不拖累字段表。漂移守卫见 {@code PromptServiceContractTest}：
     * 这张表的每个键都必须是 schema 里真实存在的字段。
     */
    static final java.util.Map<String, String> FIELD_HINTS =
            java.util.Map.of(
                    "angle", "弧度不是角度",
                    "backgroundColor", "要填充就得给，不写等于没填");

    /**
     * classpath 资源缓存：只在首次加载时读一次文件。
     * 用 ConcurrentHashMap —— Spring MVC 请求处理多线程，懒加载的 put 必须线程安全
     * （HashMap 并发 put 可能链表成环导致后续 get 死循环，且仅在并发下暴露）。
     */
    private final ConcurrentHashMap<String, String> resourceCache =
            new ConcurrentHashMap<>();

    /**
     * 从 md 首行治理头里抠出版本号，形如 {@code 版本 v10.2.0}。
     *
     * <p>治理头本身会被 {@link #stripComments} 剥掉（不发模型），
     * 但这里是**读原文**取号，所以剥离动作不影响取值。
     */
    private static final java.util.regex.Pattern VERSION_PATTERN =
            java.util.regex.Pattern.compile("版本\\s*(v\\d+(?:\\.\\d+)*)");

    /**
     * 会话模型的解析器：把「场景 elements」与「用户手绘旁路」
     * 拆成两段分别渲染（v2-34）；v2-42 起还用来算元素数与抽骨架。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Logger LOG =
            LoggerFactory.getLogger(PromptService.class);

    /**
     * 静态层内容块：单一提示词正文 + 契约段（v6.0.0 拆层，v7.0.0 合并回一层）。
     *
     * <p><b>v7.0.0 变更（用户拍板「不分两个系统提示词」）</b>：原来静态层拆成
     * 「核心 + 大图扩展」两份、按 {@code isLargeTask} 条件注入。合并为一份后：
     * <ul>
     *   <li>删掉 {@code system-prompt-large.md} 与规模判定逻辑 —— 少一条分支、少一份
     *       会漂移的副本；</li>
     *   <li>体积反而更小：扩展层原本就与核心层大面积重复（分区、要量、交白卷），
     *       去重后 8325 字节 vs 旧两层合计 9414 字节；</li>
     *   <li>token 口径统一：所有请求付同一份 system（约 2996），不再有"切层级"
     *       导致的 KV-cache 前缀抖动（旧：切层级会打穿稳定前缀）。</li>
     * </ul>
     *
     * @return system 消息正文（= 提示词正文 + "\n\n" + 契约段）
     * @throws IOException 读资源文件失败
     */
    public String buildSystemBlock() throws IOException {
        return stripComments(loadResource(SYSTEM_PROMPT_PATH))
                + "\n\n" + buildContractBlock();
    }

    /**
     * 提示词版本号（v2-43 / Q3-A）—— <b>从 md 治理头现读</b>。
     *
     * <p><b>为什么必须现读、不能写死常量</b>：v2-43 最初把版本号做成
     * {@code GenerationService.PROMPT_VERSION} 里的硬编码字符串，结果是
     * 10-06 到 10-10 期间 md 连改十余版（v9.0.0 → v10.2.0），那个常量一直
     * 停在 {@code v8.0.1}。它不报错、不告警，只是<b>安静地记错</b> ——
     * 而它唯一的用途就是给 M14（评估回归）按版本对照新旧出图质量，
     * 记错等于给回归门禁喂假基线，比"没记"更糟。
     *
     * <p>改成从治理头派生后，"人改了 md 忘了改 Java"这条断链**结构上消失**：
     * 版本号只有一个来源，就是那份文件自己的首行。
     *
     * <p>取值失败（治理头被删 / 格式变了）不抛异常，而是回退到
     * {@code "unknown"} 并打 WARN —— 版本号是溯源元数据，不该让整个出图链路挂掉。
     *
     * @return 形如 {@code v10.2.0}；取不到时为 {@code unknown}
     */
    public String promptVersion() {
        try {
            String md = loadResource(SYSTEM_PROMPT_PATH);
            java.util.regex.Matcher matcher = VERSION_PATTERN.matcher(md);
            if (matcher.find()) {
                return matcher.group(1);
            }
            LOG.warn("system-prompt.md 治理头里没找到「版本 vX.Y.Z」，promptVersion 记为 unknown");
        } catch (IOException e) {
            LOG.warn("读 system-prompt.md 失败，promptVersion 记为 unknown: {}",
                    e.getMessage());
        }
        return "unknown";
    }

    /**
     * 去掉 md 里的 HTML 注释（{@code <!-- ... -->}）。
     *
     * <p><b>2026-10-07 用户拍板 strip：</b>md 首行那条 {@code L0 治理头} 是一份
     * 给人 / CI 看的变更史，明写着「不属模型指令」，但它<b>一直原样发给了模型</b>
     * —— 实测占 839B ≈ md 的 14%，按 3.4 字节/token 折算约 245 token / 轮，
     * 每问一次付一次。而版本号、改动原因这些东西模型读了完全没用。
     *
     * <p>更糟的是它<b>会随每次改动膨胀</b>（提示词越维护越长），而它对 KV-cache
     * 前缀还起反作用：本类注释里「版本号等元数据不进正文，否则每轮前缀变化、
     * KV-cache 失效」那条纪律，正是被这行亲手违反的。strip 之后 md 顶部
     * 直接是 {@code # System Prompt}，前缀稳定且只剩真正的指令。
     *
     * <p>注释仍留在源文件里：写变更史是必要的，只是不该付费发给模型。
     * CI / 人读文件照样看得到。
     */
    private static String stripComments(String md) {
        return COMMENT_PATTERN.matcher(md).replaceAll("").strip();
    }

    /**
     * 契约段（v2-43）：由 {@code scene.schema.json} **派生**出的紧凑字段表。
     *
     * <p>为什么不再是「原样贴 JSON」（旧写法）：实测整份 schema 注入进来要吃掉
     * 3000+ token，占 system 块六成以上，其中大半是 JSON 语法括号与重复的
     * {@code "type":"number"} / id 正则。模型真正需要的只是「每个类型能写哪些键、
     * 取值是什么」。改为按 schema 现算一张字段表 ——
     * <ul>
     *   <li>省 token：同一份信息从 3000+ 降到 500 上下（详见 {@code buildSystemBlock} 注释）；</li>
     *   <li>不漂移：字段表是**算出来的**，契约里加一个键，提示词里自动多一项，
     *       不存在「改了 schema 忘了改提示词」这条经典断链；</li>
     *   <li>更好读：模型产的是 JSON，按类型分组的字段清单比一大坨
     *       {@code draft-07} 嵌套更容易对照。</li>
     * </ul>
     *
     * <p>本方法仍是**纯静态**的（同样受 {@code resourceCache} 缓存、同样进 system、
     * 同样是 KV-cache 稳定前缀），所以 KV-cache 收益一点没丢。
     *
     * @return 契约段正文
     * @throws IOException 读资源文件失败
     */
    public String buildContractBlock() throws IOException {
        JsonNode definitions =
                objectMapper.readTree(loadResource(SCHEMA_PATH)).get("definitions");
        StringBuilder sb = new StringBuilder();
        sb.append("## 契约（scene.schema.json，字段与类型以此为准）\n");
        sb.append("场景 = {\"elements\":[...]}，数组顺序即层级；坐标单位 px、"
                + "原点是左上角，x 向右、y 向下增大（屏幕坐标，不是数学坐标），"
                + "就是最终落点。\n");
        // ⚠️ 这句话必须点名 type（2026-10-07 血的教训）：
        // 下面那份键清单为了省 token **不列 type**（它是行首类型名，重复写
        // 纯浪费）。但只说"只写下面这些键"、却不提 type 时，模型会严格
        // 照做 —— 于是每个元素都漏写 type。实测 15 模块大图第一轮
        // 38 个 issue **全是同一个原因**"缺少必填字段 type"（一个元素一条），
        // 白白烧掉一轮强模型；DeepSeek 的思维链里甚至明写着它在纠结
        // "契约没列 type，到底要不要给"。
        sb.append("每个元素必须带 type（值 = 行首那个类型名），"
                + "除它以外只写下面这些键，多一个键整份作废：\n");
        for (String branch : CONTRACT_BRANCHES) {
            JsonNode def = definitions.get(branch);
            JsonNode props = def.get("properties");
            sb.append("- ").append(branchNames(def)).append("：");
            Iterator<String> names = props.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                // type 就是行首那个类型名，重复写纯占 token
                if ("type".equals(name) || SKIP_IN_TABLE.contains(name)) {
                    continue;
                }
                sb.append(renderProp(name, props.get(name), definitions));
                if (names.hasNext()) {
                    sb.append(", ");
                }
            }
            sb.append(requiredSuffix(def)).append(BRANCH_NOTES.getOrDefault(branch, "")).append('\n');
        }
        // ⚠️ 2026-10-07：原来只有"不要写裸字符串"这半条，漏了空壳。实测中等用例
        // 第 1 轮 5 个箭头全带空 label、简单用例也偶发（第 2 轮才过）—— 而
        // GraphValidator.checkLabel 对"label 存在但 text 空"是**打回**，不是忽略。
        // 契约里没写、模型就认为空壳无害。字段级规则跟在字段旁边最有效。
        sb.append("id 用英文字母数字下划线连字符（不许空格 / 中文 / 点 / 斜杠，"
                + "校验器按 ^[A-Za-z0-9_-]+$ 卡）；文字用 label 或 containerId，不要写裸字符串；"
                + "label 里必须有非空 text，没内容就整个键别写（空壳会被打回）。\n");
        sb.append("渲染层字段不要输出（后端按语义填）：children、roundness、startArrowhead、"
                + "endArrowhead、verticalAlign、autoResize。\n");
        return sb.toString();
    }

    /**
     * 分支的展示名：{@code shape} 展开成「rectangle / ellipse / diamond」（取自它
     * {@code type} 的 enum），其余分支用自己 {@code type} 的 const。
     */
    private static String branchNames(JsonNode def) {
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
     * 必填键后缀：把契约里的 {@code required} 附在行尾
     * （模型漏写必填键会被直接打回）。
     *
     * <p>⚠️ 2026-10-07 起**不再剔除 type**：原先认为它"自明"（行首就写着
     * rectangle / arrow…）而省掉，但这恰恰让模型以为 type 不属于该写的键
     * —— 实战里 15 模块大图第一轮 38 个 issue 全是"缺少必填字段 type"。
     * 真值是 schema 的 required，就照抄，别再自作聪明。
     */
    private static String requiredSuffix(JsonNode def) {
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
     * 单个字段的紧凑写法：{@code fillStyle} → {@code fillStyle[hachure|...]}，
     * {@code opacity} → {@code opacity 0-100}，{@code label} → {@code label{"text"}}。
     * 未知的 {@code $ref} 递归展开一层，不认识就退回字段名，绝不抛异常 ——
     * 提示词拼装失败等于整条生成链路跪掉，这里必须稳。
     */
    private static String renderProp(String name, JsonNode node, JsonNode definitions) {
        String base = renderPropBase(name, node, definitions);
        String hint = FIELD_HINTS.get(name);
        return hint == null ? base : base + "(" + hint + ")";
    }

    private static String renderPropBase(String name, JsonNode node, JsonNode definitions) {
        JsonNode ref = node.get("$ref");
        String refName = ref == null ? "" : ref.asText().substring(ref.asText().lastIndexOf('/') + 1);
        if (!refName.isEmpty()) {
            switch (refName) {
                case "label":
                    return name + "{\"text\"}";
                case "binding":
                    return name + "{\"id\"}";
                case "point":
                    // 与 arrayShape() 的 points 形态保持一致：首点必须写出来，
                    // 见那边的注释（模型会把绝对坐标写进 points 导致落点翻倍）。
                    // 目前 schema 里 point 只以 items.$ref 的形式出现、走不到这里，
                    // 但留着一条不一致的写法迟早会咬人。
                    return name + "[[0,0],[dx,dy]...]";
                default:
                    return renderProp(name, definitions.get(refName), definitions);
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
     * 数组的简写：points → {@code [[0,0],[dx,dy]...]}，其余 → {@code [...]}.
     *
     * <p>⚠️ 2026-10-07：原来写的是 {@code [[dx,dy]...]}，看不出**首点必须是 [0,0]**
     * —— 因为 points 是相对 x/y 的偏移（Excalidraw 约定，前端
     * {@code withArrowGeometry} 也是按 {@code [[0,0],[终点-起点]]} 生成的）。
     * 模型据此把**绝对坐标**写进 points，同时把 x/y 也设成第一个点，
     * 于是落点 = (x+dx, y+dy) ≈ 坐标翻倍：实测画猫那组 9 条线全被画到视野外，
     * 而校验器只检查每个点是不是两个数字、**不检查首点**，静默通过。
     * 渲染成 {@code [[0,0],[dx,dy]...]} 让这个约定自解释。
     */
    private static String arrayShape(JsonNode node) {
        JsonNode items = node.get("items");
        if (items != null && items.get("$ref") != null
                && items.get("$ref").asText().endsWith("/point")) {
            return "[[0,0],[dx,dy]...]";
        }
        return "[...]";
    }

    /** {code exclusiveMinimum:0} 表示「必须大于 0」，提示词里写成 {@code (>0)}。 */
    private static boolean isExclusivePositive(JsonNode node) {
        JsonNode min = node.get("exclusiveMinimum");
        return min != null && min.asInt() == 0;
    }


    /**
     * 动态层内容块（v2-41）：当前场景 + 修改规则 + 用户需求
     *
     * <p>这一块**每轮变化**（用户换了需求、图被改了），所以进 user 消息。
     *
     * <p><b>v2-42 增量投喂</b>：编辑模式下元素数超过
     * {@value #EDIT_INLINE_MAX_ELEMENTS} 时，当前场景不再整份贴出，
     * 改为 {@link #compactSceneBlock}（结构摘要 + 被改元素全文 + 引用骨架）。
     * 小图（≤ 阈值）仍走整份贴出 —— 那条路径的文案与 v2-41 逐字节一致，
     * 不做任何改动，保证小图行为零漂移。
     *
     * @param userText         用户在输入框里的原始需求
     * @param currentSceneJson 会话当前模型的 JSON（新建图时传 null）
     * @return user 消息正文
     */
    public String buildUserBlock(String userText, String currentSceneJson) {
        StringBuilder sb = new StringBuilder();
        boolean editMode = currentSceneJson != null && !currentSceneJson.isBlank();
        if (editMode) {
            String scene = sceneOnly(currentSceneJson);
            JsonNode sceneNode = tryParse(scene);
            int count = elementCount(sceneNode);
            if (count > EDIT_INLINE_MAX_ELEMENTS) {
                sb.append(compactSceneBlock(sceneNode, count, userText));
            } else {
                sb.append("## 当前场景（用户眼下看到的图，请在它的基础上修改）\n");
                sb.append("```json\n").append(scene).append("\n```\n\n");
            }
            sb.append(userElementsBlock(currentSceneJson));
            sb.append(modifyRulesBlock());
        }

        sb.append("## 用户需求\n");
        sb.append(userText).append("\n");
        return sb.toString();
    }

    /**
     * 修改规则段（v2-33 引入；v2-42 抽成方法，两条路径共用同一份文案）。
     *
     * @return 「## 修改规则（必须遵守）」整段
     */
    private String modifyRulesBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 修改规则（必须遵守）\n");
        sb.append("- **先判断用户这一句的意图，两种走法完全不同**：\n");
        sb.append("  - **改这张图**：用户在谈论当前场景里的东西（提到某个节点 / 某段文字 / 某种颜色，"
                + "或用「删掉」「改成」「加上」「连起来」这类说法）——"
                + "必须严格按下面各条保真规则执行。\n");
        sb.append("  - **另画一张**：用户要的是一个**全新主题**的图，与当前场景明显无关"
                + "（例：当前是登录流程，这一句要「订单处理流程」）——"
                + "丢弃旧元素，只输出新图。\n");
        sb.append("- 你输出的是**修改后的完整场景**（全量 elements），不是差异片段、不是新增部分。\n");
        sb.append("- **没有要求改动的元素必须原样保留**：`id` / `type` / 几何（x / y / width / height）"
                + "/ `label` / 样式全部照抄，不要重新编号、不要重排坐标、不要精简掉你认为多余的元素。\n");
        sb.append("- 「删除某元素」= 结果里不包含它（同时删掉连到它的箭头）。\n");
        sb.append("- 「新增元素」= 起一个不与现有 id 重复的新 id（沿用现有命名风格）。\n");
        sb.append("- 「改文字 / 改颜色」= 只动那一个字段，其余照抄。\n");
        sb.append("- 只有「另画一张」的情况，或用户明确要求「重新画 / 换一张 / 全部重做」，"
                + "才允许丢弃原有元素。\n\n");
        return sb.toString();
    }

    /**
     * v2-42：大图编辑模式的紧凑场景块。
     *
     * <p>由三部分组成（对齐计划里写的「结构摘要 + 被改元素全文 + 引用」）：
     * <ol>
     *   <li><b>结构摘要</b> —— 每个元素一行骨架（id / type / 几何 / label 文本 /
     *       绑定端点 / group），**全量**列出。之所以骨架里必须带几何与 label：
     *       输出侧仍要求"未点名元素原样保留"（md 约束 5），若摘要丢了这些信息，
     *       模型就只能瞎猜几何 —— 图会散。带上以后，未点名元素可被逐字复现，
     *       **无需后端做合并**；</li>
     *   <li><b>被改元素全文</b> —— 用户这句话里点名到的元素（按 id 或 label 文本
     *       命中），给出完整 JSON，省得模型只凭骨架猜样式；</li>
     *   <li><b>引用骨架</b> —— 箭头 / 线的端点、containerId 归属，供模型在
     *       删元素时顺手清掉悬空箭头（md 约束 5）。</li>
     * </ol>
     *
     * @param scene    已剥离 {@code _userElements} 的场景根节点
     * @param count    元素总数
     * @param userText 用户需求（用于识别"被点名"的元素）
     * @return 紧凑场景块
     */
    private String compactSceneBlock(JsonNode scene, int count, String userText) {
        JsonNode elements = scene.get("elements");
        StringBuilder summary = new StringBuilder();
        StringBuilder changed = new StringBuilder();
        StringBuilder refs = new StringBuilder();
        int mentioned = 0;
        for (JsonNode el : elements) {
            String line = skeleton(el);
            if (!line.isEmpty()) {
                summary.append(line).append('\n');
            }
            if (mentionedIn(el, userText)) {
                changed.append("```json\n").append(compactJson(el)).append("\n```\n");
                mentioned++;
            }
            String ref = reference(el);
            if (!ref.isEmpty()) {
                refs.append(ref).append('\n');
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前场景（结构摘要：共 ").append(count)
                .append(" 个元素，为省 token 只列骨架）\n");
        sb.append("**没要求改动的元素，必须按下面骨架里的 id / type / 几何原样保留**"
                + "（`label` 骨架里给了文本，照抄即可）——"
                + "这是输出侧「全量 elements + 未点名元素原样保留」的依据。\n");
        sb.append("```text\n").append(summary).append("```\n\n");
        if (mentioned > 0) {
            sb.append("## 你可能要改的元素（完整定义，其余字段以骨架为准）\n");
            sb.append(changed).append("\n");
        }
        if (refs.length() > 0) {
            sb.append("## 引用骨架（谁连着谁 / 谁装在谁里，删元素时顺手清掉悬空引用）\n");
            sb.append("```text\n").append(refs).append("```\n\n");
        }
        return sb.toString();
    }

    /**
     * 单个元素的一行骨架（v2-42）。
     *
     * <p>格式：{@code id type (x,y,w x h) start->end "label" group=... }，
     * 缺哪段省哪段。意图是"信息够模型复现，又不重复渲染层字段"。
     *
     * @param el 元素节点
     * @return 骨架行；连 id 或 type 都没有时返回空串（该元素跳过）
     */
    private String skeleton(JsonNode el) {
        String id = text(el, "id");
        String type = text(el, "type");
        if (id.isEmpty() || type.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append(id).append(' ').append(type);
        if (el.hasNonNull("x") && el.hasNonNull("y")) {
            b.append(" (").append(num(el, "x")).append(',').append(num(el, "y"));
            if (el.hasNonNull("width") && el.hasNonNull("height")) {
                b.append(',').append(num(el, "width"))
                        .append('x').append(num(el, "height"));
            }
            b.append(')');
        }
        String start = endpoint(el.get("start"));
        String end = endpoint(el.get("end"));
        if (!start.isEmpty() || !end.isEmpty()) {
            b.append(' ').append(start).append("->").append(end);
        } else if (el.has("points") && el.get("points").isArray()) {
            b.append(" points=").append(el.get("points").size());
        }
        String label = labelText(el);
        if (!label.isEmpty()) {
            b.append(" \"").append(label).append('"');
        }
        String group = text(el, "group");
        if (!group.isEmpty()) {
            b.append(" group=").append(group);
        }
        String name = text(el, "name");
        if (!name.isEmpty()) {
            b.append(" name=").append(name);
        }
        return b.toString();
    }

    /**
     * 引用骨架行（v2-42）：端点绑定或 containerId 归属。
     *
     * @param el 元素节点
     * @return {@code e1: n1 -> n2} 或 {@code t1: in n1}；无引用时返回空串
     */
    private String reference(JsonNode el) {
        String id = text(el, "id");
        if (id.isEmpty()) {
            return "";
        }
        String start = endpoint(el.get("start"));
        String end = endpoint(el.get("end"));
        if (!start.isEmpty() || !end.isEmpty()) {
            return id + ": " + start + " -> " + end;
        }
        String container = text(el, "containerId");
        if (!container.isEmpty()) {
            return id + ": in " + container;
        }
        return "";
    }

    /**
     * 判断元素是否被用户这句话"点名"（v2-42）。
     *
     * <p>朴素判据：用户文本里出现了该元素的 id 或 label 文本。
     * 命中就把它整份 JSON 附上 —— 宁可多给一个，也不少给；判据保守
     * （长度 &lt; 2 不参战）以免短标签（如「是」）到处误命中。
     *
     * @param el       元素节点
     * @param userText 用户需求
     * @return 是否命中
     */
    private boolean mentionedIn(JsonNode el, String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        String id = text(el, "id");
        if (id.length() >= 2 && userText.contains(id)) {
            return true;
        }
        String label = labelText(el);
        return label.length() >= 2 && userText.contains(label);
    }

    /**
     * 取 {@code start} / {@code end} 这类端点对象里的 id。
     *
     * @param node 端点节点（可能是 {@code {"id":"n1"}} 或缺失）
     * @return 端点 id；取不到返回空串
     */
    private String endpoint(JsonNode node) {
        if (node != null && node.isObject() && node.hasNonNull("id")) {
            return node.get("id").asText();
        }
        return "";
    }

    /**
     * 取元素的可见文字：优先 {@code label.text}（图形），其次 {@code text}（文字元素）。
     *
     * @param el 元素节点
     * @return 文本；没有返回空串
     */
    private String labelText(JsonNode el) {
        JsonNode label = el.get("label");
        if (label != null && label.isObject() && label.hasNonNull("text")) {
            return label.get("text").asText();
        }
        if (el.hasNonNull("text")) {
            return el.get("text").asText();
        }
        return "";
    }

    /**
     * 字符串字段读取（缺失 / null 一律回空串，省掉到处判空）。
     *
     * @param node  节点
     * @param field 字段名
     * @return 值；取不到返回空串
     */
    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }

    /**
     * 数字字段读取（骨架里用；整数去小数点，避免 {@code 0.0} 这类噪音）。
     *
     * @param node  节点
     * @param field 字段名
     * @return 简洁数字串；非数字返回 {@code "?"}
     */
    private String num(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isNumber()) {
            return "?";
        }
        double d = v.asDouble();
        if (d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.format(Locale.ROOT, "%.1f", d);
    }

    /**
     * 单行 JSON（v2-42：把元素整份塞进「被改元素」段）。
     *
     * @param node 节点
     * @return 紧凑 JSON
     */
    private String compactJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    /**
     * 容错解析（v2-42）：场景 JSON 不可解析时返回 null，调用方退回整份贴出。
     *
     * @param json JSON 文本
     * @return 根节点；解析失败返回 null
     */
    private JsonNode tryParse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            LOG.warn("场景 JSON 解析失败，退回整份贴出: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 元素计数（v2-42）。
     *
     * @param scene 场景根节点（可为 null）
     * @return 元素个数；无法判断返回 -1（调用方按"小图"处理，走整份贴出）
     */
    private int elementCount(JsonNode scene) {
        if (scene == null) {
            return -1;
        }
        JsonNode elements = scene.get("elements");
        return (elements != null && elements.isArray()) ? elements.size() : -1;
    }

    /**
     * 修正块（v2-41）：上一轮校验未通过
     *
     * <p>不再声明 IOException —— 它本身没有任何 I/O。
     *
     * @param issues 上一轮校验失败的 issue 列表
     * @return 追加到 user 消息末尾的修正块
     */
    public String buildFixBlock(List<ValidationIssue> issues) {
        StringBuilder fix = new StringBuilder();
        fix.append("\n\n## ⚠️ 上一轮校验未通过\n");
        fix.append("请只修正以下问题，"
                + "不要重画整张图：\n");
        for (ValidationIssue issue : issues) {
            fix.append("- 字段 `")
                    .append(issue.field())
                    .append("`：")
                    .append(issue.reason())
                    .append(" → ")
                    .append(issue.hint())
                    .append("\n");
        }
        return fix.toString();
    }

    /**
     * 单串等价入口（v2-41 起仅供"整串 prompt"形态的调用方与测试使用）
     *
     * <p><b>生产路径已改走 {@code context.ContextAssembler}（system / user 两条消息）。</b>
     * 本方法内部只是把 {@link #buildSystemBlock()} 与
     * {@link #buildUserBlock(String, String)} 首尾相接 —— 因此
     * **不存在第二份文案、不会漂移**，旧调用点与既有断言可原样保留。
     *
     * @param userText         用户在输入框里的原始需求
     * @param currentSceneJson 会话当前模型的 JSON（新建图时传 null）
     * @return 完整 prompt（= system 块 + user 块）
     * @throws IOException 读资源文件失败
     */
    public String buildPrompt(String userText, String currentSceneJson) throws IOException {
        return buildSystemBlock() + buildUserBlock(userText, currentSceneJson);
    }

    /**
     * 单串等价入口：修正 prompt（v2-10）
     *
     * <p>= {@link #buildPrompt(String, String)} + {@link #buildFixBlock(List)}。
     *
     * @param userText         原始用户需求
     * @param issues           上一轮校验失败的 issue 列表
     * @param currentSceneJson 会话当前模型的 JSON（新建图时传 null）
     * @return 修正 prompt
     * @throws IOException 读资源文件失败
     */
    public String buildFixPrompt(
            String userText,
            List<ValidationIssue> issues,
            String currentSceneJson)
            throws IOException {
        return buildPrompt(userText, currentSceneJson)
                + buildFixBlock(issues);
    }

    /**
     * 场景段：把会话模型里的"用户手绘"旁路摘掉之后的 JSON。
     *
     * <p>为什么要摘：{@code _userElements} 是后端内部字段，
     * 混在"当前场景"里会让 LLM 以为它是场景的一部分，
     * 进而原样抄进输出 —— 那反而成了新的重复源（v2-34）。
     *
     * @param modelJson 会话模型 JSON
     * @return 只含 elements 的 JSON；解析失败时原样返回
     */
    private String sceneOnly(String modelJson) {
        try {
            JsonNode root = objectMapper.readTree(modelJson);
            if (root.has(SessionStore.USER_ELEMENTS_KEY)) {
                ((ObjectNode) root).remove(
                        SessionStore.USER_ELEMENTS_KEY);
                return objectMapper.writeValueAsString(root);
            }
            return modelJson;
        } catch (Exception e) {
            LOG.warn("当前场景解析失败，按原样嵌入: {}",
                    e.getMessage());
            return modelJson;
        }
    }

    /**
     * 用户手绘元素段（v2-34）
     *
     * <p>用户手绘的线**不在 elements 里**（后端不持有它们的几何），
     * 于是 LLM 完全不知道用户已经在那里连了一条线，会再画一条 ——
     * 用户看到的就是"同一处两条线"（2026-09-16 实测：
     * 「多添加线的地方是我删除一次然后又添加上去的」）。
     * 这里把用户画的东西单列一段告诉它，并明确禁止抄进输出。
     *
     * @param modelJson 会话模型 JSON
     * @return 提示段落；没有用户手绘时返回空串
     */
    private String userElementsBlock(String modelJson) {
        try {
            JsonNode userElements = objectMapper
                    .readTree(modelJson)
                    .get(SessionStore.USER_ELEMENTS_KEY);
            if (userElements == null
                    || !userElements.isArray()
                    || userElements.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("## 用户手工添加的内容（不是你画的）\n");
            sb.append("用户在画布上**手工**画了下面这些元素，"
                    + "它们不在上面的 elements 里：\n");
            sb.append("```json\n")
                    .append(objectMapper
                            .writeValueAsString(userElements))
                    .append("\n```\n");
            sb.append("- 它们是用户自己画的、**会一直被保留**，"
                    + "你既不需要、也不要把它们复制进 elements。\n");
            sb.append("- 如果它们已经表达了某条连线或标注，"
                    + "**不要重复画一条**。\n\n");
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("用户手绘段解析失败，跳过: {}",
                    e.getMessage());
            return "";
        }
    }

    /**
     * 读 classpath 资源并缓存（按路径缓存，首次读一次）。
     *
     * @param path classpath 路径
     * @return 文件全文（UTF-8）
     * @throws IOException 读失败
     */
    private String loadResource(String path) throws IOException {
        try {
            return resourceCache.computeIfAbsent(path, p -> {
                try {
                    ClassPathResource resource = new ClassPathResource(p);
                    try (var in = resource.getInputStream()) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw e;
        }
    }
}
