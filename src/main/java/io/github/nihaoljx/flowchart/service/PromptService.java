package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.nihaoljx.flowchart.graph.layout.GroupPath;
import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;
import io.github.nihaoljx.flowchart.prompt.DiagramPromptRegistry;
import io.github.nihaoljx.flowchart.prompt.DiagramTemplate;
import io.github.nihaoljx.flowchart.prompt.PromptText;
import io.github.nihaoljx.flowchart.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 内容服务（拆静态/动态两块；静态层正文改为**直接读 md 文件**；
 * 含编辑模式增量投喂）
 *
 * 静态层唯一正文来源 = `prompts/whiteboard/system-prompt.md`
 * （用户「以 system-prompt.md 为准」；合并掉
 * `system-prompt-large.md`，静态层从此只有这一份 md）。运行时**不再维护第二份
 * Java 硬编码文案** —— 改提示词只改那一份 md，从此不存在"两份文本互相漂移"。
 *
 * 本类职责：
 *
 * - buildSystemBlock() —— md 正文 + buildContractBlock()
 *       生成的契约段（契约源仍是 `schemas/scene.schema.json`，md 里声明
 *       "契约每轮由运行时注入"）。每轮完全不变，进 system 消息、且是
 *       KV-cache 的稳定前缀；
 *
 * - buildUserBlock(String, String) —— 当前场景 + 修改规则 + 用户需求
 *       （每轮变化，进 user 消息）。大图（>
 *       {@value #EDIT_INLINE_MAX_ELEMENTS} 元素）走增量投喂：只给结构摘要，
 *       不再整份塞场景；
 *
 * - buildFixBlock(List) —— 修正轮追加的校验 issues。
 *
 * 装配由调用方负责：agent 路径直取两块（system 进 AgentMessages 稳定前缀，
 * user 块携带动态层），修正块由 AgentLoop 在提交未过时追加；本类只产出文案。
 *
 * 版本号等元数据**不进正文**（否则每轮前缀变化、KV-cache 失效）。
 */
@Service
public class PromptService {

    /** 静态层提示词正文（唯一真相源，常驻核心） */
    private static final String SYSTEM_PROMPT_PATH =
            "prompts/whiteboard/system-prompt.md";

    /** 契约文件（白板内容契约唯一真相源） */
    private static final String SCHEMA_PATH = "schemas/scene.schema.json";

    /**
 * 编辑模式下元素数**超过**此值，user 块改走「结构摘要 + 被改元素全文 + 引用骨架」。
     *
 * 动机：大图整份塞进 prompt 有两个代价 —— token 开销线性增长、
     * 且"当前场景"段过长会稀释模型对「用户需求」的注意力（Lost in the Middle）。
     * 30 这个数字的判据是"整份 JSON 大约 3~5 KB"，再往上就开始明显拖慢并干扰。
     */
    public static final int EDIT_INLINE_MAX_ELEMENTS = 30;

    /**
     * 契约段要列举的元素分支（`scene.schema.json` 里 `definitions` 的键）。
     * 按"模型实际会用到"排序；`image` / `magicframe` 属前向兼容、当前不产出，
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
 * ⚠️  补 arrow 一条：契约的字段表把 `points / start / end`
     * 并排列出，读起来像"三个都能给"，而校验器卡的是二选一。实测中等用例
     * 第 1 轮 5 个箭头全犯（`共 10 个问题`，一半是这条）—— md 的「自检」
     * 里虽写了二选一，但模型填字段时看的是这里。字段级规则就跟在字段后面，
     * 与 `line` 那条同理。
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
                            + "frame 的成员由后端按 group 路径合成 —— 你只需给节点写 group，"
                            + "通常不需要自己画 frame；containerId 只把文字挂进图形、不表归属；"
                            + "框左上角必定显示灰色标题且无法隐藏，别在框上再叠一个重复的文字标题；"
                            + "如果你**已经**自己画了一个与某层 group 同名的 frame，"
                            + "那一层后端就不再合成，不会出现两层同名单框套在一起；"
                            + "箭头不要绑它，绑了整张图会渲染成空白）");

    /**
     * 字段级提示：跟在字段名后面渲染成 `angle(弧度不是角度)`。
     *
 * 为什么要有这张表：契约段是按 schema 现算的，只渲染「名字 + 类型
     * + 枚举」，schema 里的 `description` 一律不进提示词（省 token）。
     * 于是那些**光看字段名不够、又不像枚举那样能自解释**的字段就成了坑：
     *
 * - `angle` —— schema 写着「弧度，默认 0」，但模型看不到这句，
     *       按直觉写 `45`。而前端 / Excalidraw 都按弧度解释，
     *       45 rad ≈ 58.4°，图形被转到莫名其妙的角度（iso-3d
     *       实测：6 个矩形全被转歪，等轴测房子糊成一团）。
     *
 * - `backgroundColor` —— 契约里 `fillStyle` 是个枚举，
     *       模型以为「给了纹理就等于填了」，于是只给 `fillStyle` 不给
     *       `backgroundColor` → 前端等于没填充 → 整张图纯黑线，
     *       用户明确要的「不同面同色系深浅」完全没实现。
     *
 * 写法守住两条：① 只写**看名字绝对猜不到**的那一点，不去重复 md 已有的
     * 内容；② 短到不拖累字段表。漂移守卫见 `PromptServiceContractTest`：
     * 这张表的每个键都必须是 schema 里真实存在的字段。
     */
    static final java.util.Map<String, String> FIELD_HINTS =
            java.util.Map.of(
                    "angle", "弧度不是角度",
                    "backgroundColor", "要填充就得给，不写等于没填",
                    "group", "嵌套时写成从内到外的数组，如 [\"处理中\",\"订单\"]");

    /**
     * 图型提示词模板注册表：按图型注入专项规则（时序图 / 未来更多图型）。
     *
     * <p>为什么内核 md 里不直接写死各图型的规则：内核已经顶到字节闸门
     * （v10.13.0 时 9987B / 上限 10000B，只剩 13B），每加一种图的规则都要从
     * 通用规则里挤字节。拆出去以后图型模板各自单独计预算，加图型不再动内核。
     */
    private final DiagramPromptRegistry diagramPrompts;

    /** 无参构造（单测 / 非 Spring）：只用 classpath 内置图型模板。 */
    public PromptService() {
        this(new DiagramPromptRegistry());
    }

    @Autowired
    public PromptService(DiagramPromptRegistry diagramPrompts) {
        this.diagramPrompts = diagramPrompts;
    }

    /**
     * classpath 资源缓存：只在首次加载时读一次文件。
     * 用 ConcurrentHashMap —— Spring MVC 请求处理多线程，懒加载的 put 必须线程安全
     * （HashMap 并发 put 可能链表成环导致后续 get 死循环，且仅在并发下暴露）。
     */
    private final ConcurrentHashMap<String, String> resourceCache =
            new ConcurrentHashMap<>();

    /**
     * 从 md 首行治理头里抠出版本号，形如 `版本 `。
     *
 * 治理头本身会被 stripComments 剥掉（不发模型），
     * 但这里是**读原文**取号，所以剥离动作不影响取值。
     */
    private static final java.util.regex.Pattern VERSION_PATTERN =
            java.util.regex.Pattern.compile("版本\\s*(v\\d+(?:\\.\\d+)*)");

    /**
     * 会话模型的解析器：把「场景 elements」与「用户手绘旁路」
     * 拆成两段分别渲染； 起还用来算元素数与抽骨架。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 契约段缓存（report 2 优化 #7）。
     *
     * <p>{@link #buildContractBlock()} 的产物**对所有请求逐字节相同** —— 它只由
     * classpath 里的 {@code scene.schema.json} 派生，而那份文件经
     * {@link #resourceCache} 读一次后再不变。原实现每请求都重新
     * {@code readTree} 解析 14.3KB JSON，再重拼约 500 token 的文本
     * （{@code buildSystemBlock} 每请求都调它），纯属重复劳动。
     *
     * <p>用 {@code volatile} 发布：非 volatile 的懒加载在并发首次访问时
     * 可能让一个线程看到**部分构造**的 String 引用（安全发布问题），
     * volatile 写提供 happens-before。计算是幂等的，最坏情况两个线程
     * 各算一次、后写者覆盖，结果等价。
     */
    private volatile String contractBlockCache;

    /**
     * 已解析的 schema {@code definitions} 节点缓存（同上 #7）。
     *
     * <p>{@link #buildContractBlock()} 与 {@link #contractSection(String)}
     * 各自 {@code readTree(...).get("definitions")} 一次；Jackson 的
     * {@code JsonNode} 是**可变**对象，但本类从不修改它（只读
     * {@code get}/{@code fieldNames}），因此共享是安全的。
     */
    private volatile JsonNode definitionsCache;

    private static final Logger LOG =
            LoggerFactory.getLogger(PromptService.class);

    /**
     * 静态层内容块：单一提示词正文 + 契约段（拆层， 合并回一层）。
     *
 *  变更：原来静态层拆成
     * 「核心 + 大图扩展」两份、按 `isLargeTask` 条件注入。合并为一份后：
     *
 * - 删掉 `system-prompt-large.md` 与规模判定逻辑 —— 少一条分支、少一份
     *       会漂移的副本；
     *
 * - 体积反而更小：扩展层原本就与核心层大面积重复（分区、要量、交白卷），
     *       去重后 8325 字节 vs 旧两层合计 9414 字节；
     *
 * - token 口径统一：所有请求付同一份 system（约 2996），不再有"切层级"
     *       导致的 KV-cache 前缀抖动（旧：切层级会打穿稳定前缀）。
     *
     * @return system 消息正文（= 提示词正文 + "\n\n" + 契约段）
     * @throws IOException 读资源文件失败
     */
    public String buildSystemBlock() throws IOException {
        return buildSystemBlock(null);
    }

    /**
     * 静态层内容块：内核 md + 契约段 + （命中时）图型模板段。
     *
     * <p><b>装配顺序是刻意排的</b>：内核与契约对所有请求都一样，放在最前就是
     * KV-cache 的**稳定前缀**；图型模板是唯一的变量，放在最后。反过来（图型段
     * 插在中间）会把"内核 + 契约"这个公共前缀切碎，每换一种图就丢一次缓存。
     *
     * <p>图型的判定是**确定性关键词匹配**（{@link DiagramPromptRegistry#forRequest}），
     * 不额外调 LLM 分类 —— 那要多付一次调用，而我们的 SLA 是百秒级。没命中
     * 就只发内核，行为与拆分前完全一致。
     *
     * @param requestText 用户在输入框里的原始需求（用于挑图型模板）；空则只发内核
     * @return system 消息正文
     * @throws IOException 读资源文件失败
     */
    public String buildSystemBlock(String requestText) throws IOException {
        String block = stripComments(loadResource(SYSTEM_PROMPT_PATH))
                + "\n\n" + buildContractBlock();
        DiagramTemplate template = diagramPrompts.forRequest(requestText);
        if (template == null) {
            return block;
        }
        return block + "\n\n" + diagramPrompts.render(template);
    }

    /**
     * 提示词版本号（/ Q3-A）—— 从 md 治理头现读。
     *
 * 为什么必须现读、不能写死常量： 最初把版本号做成
     * `GenerationService.PROMPT_VERSION` 里的硬编码字符串，结果是
     * 10-06 到 10-10 期间 md 连改十余版（→），那个常量一直
     * 停在 ``。它不报错、不告警，只是安静地记错 ——
     * 而它唯一的用途就是给 M14（评估回归）按版本对照新旧出图质量，
     * 记错等于给回归门禁喂假基线，比"没记"更糟。
     *
 * 改成从治理头派生后，"人改了 md 忘了改 Java"这条断链**结构上消失**：
     * 版本号只有一个来源，就是那份文件自己的首行。
     *
 * 取值失败（治理头被删 / 格式变了）不抛异常，而是回退到
     * `"unknown"` 并打 WARN —— 版本号是溯源元数据，不该让整个出图链路挂掉。
     *
     * @return 形如 ``；取不到时为 `unknown`
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
     * 去掉 md 里的 HTML 注释（`<!-- ... -->`）。
     *
 *  用户 strip：md 首行那条 `L0 治理头` 是一份
     * 给人 / CI 看的变更史，明写着「不属模型指令」，但它一直原样发给了模型
     * —— 实测占 839B ≈ md 的 14%，按 3.4 字节/token 折算约 245 token / 轮，
     * 每问一次付一次。而版本号、改动原因这些东西模型读了完全没用。
     *
 * 更糟的是它会随每次改动膨胀（提示词越维护越长），而它对 KV-cache
     * 前缀还起反作用：本类注释里「版本号等元数据不进正文，否则每轮前缀变化、
     * KV-cache 失效」那条纪律，正是被这行亲手违反的。strip 之后 md 顶部
     * 直接是 `# System Prompt`，前缀稳定且只剩真正的指令。
     *
 * 注释仍留在源文件里：写变更史是必要的，只是不该付费发给模型。
     * CI / 人读文件照样看得到。
     */
    private static String stripComments(String md) {
        return PromptText.stripComments(md);
    }

    /**
     * 契约段：由 `scene.schema.json` **派生**出的紧凑字段表。
     *
 * 为什么不再是「原样贴 JSON」（旧写法）：实测整份 schema 注入进来要吃掉
     * 3000+ token，占 system 块六成以上，其中大半是 JSON 语法括号与重复的
     * `"type":"number"` / id 正则。模型真正需要的只是「每个类型能写哪些键、
     * 取值是什么」。改为按 schema 现算一张字段表 ——
     *
 * - 省 token：同一份信息从 3000+ 降到 500 上下（详见 `buildSystemBlock` 注释）；
     *
 * - 不漂移：字段表是**算出来的**，契约里加一个键，提示词里自动多一项，
     *       不存在「改了 schema 忘了改提示词」这条经典断链；
     *
 * - 更好读：模型产的是 JSON，按类型分组的字段清单比一大坨
     *       `draft-07` 嵌套更容易对照。
     *
 * 本方法仍是**纯静态**的（同样受 `resourceCache` 缓存、同样进 system、
     * 同样是 KV-cache 稳定前缀），所以 KV-cache 收益一点没丢。
     *
     * <p>产物本身也**缓存**（report 2 优化 #7）：它对所有请求逐字节相同，
     * 原先每请求重算一次纯属重复劳动。首次调用算出后存入
     * {@link #contractBlockCache}，之后直接返回同一实例。
     *
     * @return 契约段正文
     * @throws IOException 读资源文件失败
     */
    public String buildContractBlock() throws IOException {
        String cached = contractBlockCache;
        if (cached != null) {
            return cached;
        }
        String built = renderContractBlock();
        contractBlockCache = built;
        return built;
    }

    /**
     * 契约段的实际渲染（每次调用都会从头算）。
     *
     * <p>与 {@link #buildContractBlock()} 拆开是为了让缓存逻辑只有一处：
     * 公开入口只负责「查缓存 / 回填」，渲染细节留在私有方法里。
     *
     * @return 契约段正文（未走缓存）
     * @throws IOException 读资源文件失败
     */
    private String renderContractBlock() throws IOException {
        JsonNode definitions = schemaDefinitions();
        StringBuilder sb = new StringBuilder();
        sb.append("## 契约（scene.schema.json，字段与类型以此为准）\n");
        sb.append("场景 = {\"elements\":[...]}，数组顺序即层级；坐标单位 px、"
                + "原点是左上角，x 向右、y 向下增大（屏幕坐标，不是数学坐标），"
                + "就是最终落点。\n");
        // ⚠️ 这句话必须点名 type（血的教训）：
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
        // ⚠️ ：原来只有"不要写裸字符串"这半条，漏了空壳。实测中等用例
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
     * 按元素类型取契约片段（，`agent.ReadContractTool` 的按需加载入口）
     *
 * 为什么独立成方法而不是切 buildContractBlock() 的文本：
     * 那份产物是整块字符串，按行反切等于给契约加第二个真相源。这里直接
     * 复用同一组私有渲染器（`branchNames` / `renderProp` /
     * `requiredSuffix`）与同一份 schema 现算 —— 单一真相源不变，
     * 只是多一个"按类型出片段"的出口。BRANCH_NOTES / FIELD_HINTS /
     * "空壳会被打回"三条口径原样带上，保证片段与整块不漂移。
     *
     * @param branch 契约分支名（CONTRACT_BRANCHES 之一）
     * @return 该类型的契约片段（自包含，可直接回喂给模型）
     * @throws IllegalArgumentException 分支名不在 CONTRACT_BRANCHES 里
     * @throws IOException              读 schema 资源失败
     */
    public String contractSection(String branch) throws IOException {
        boolean known = false;
        for (String b : CONTRACT_BRANCHES) {
            if (b.equals(branch)) {
                known = true;
                break;
            }
        }
        if (!known) {
            throw new IllegalArgumentException("未知契约分支: " + branch
                    + "，允许: " + String.join("/", CONTRACT_BRANCHES));
        }
        JsonNode definitions = schemaDefinitions();
        JsonNode def = definitions.get(branch);
        JsonNode props = def.get("properties");
        StringBuilder sb = new StringBuilder();
        sb.append("## 契约 · ").append(branchNames(def)).append("：");
        Iterator<String> names = props.fieldNames();
        boolean first = true;
        while (names.hasNext()) {
            String name = names.next();
            if ("type".equals(name) || SKIP_IN_TABLE.contains(name)) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append(renderProp(name, props.get(name), definitions));
            first = false;
        }
        sb.append(requiredSuffix(def)).append(BRANCH_NOTES.getOrDefault(branch, "")).append('\n');
        sb.append("id 用英文字母数字下划线连字符（不许空格 / 中文 / 点 / 斜杠）；"
                + "文字用 label 或 containerId，label 里必须有非空 text，"
                + "没内容就整个键别写（空壳会被打回）。\n");
        return sb.toString();
    }

    /**
     * 分支的展示名：`shape` 展开成「rectangle / ellipse / diamond」（取自它
     * `type` 的 enum），其余分支用自己 `type` 的 const。
     */
    private static String branchNames(JsonNode def) {
        return SchemaContractRenderer.branchNames(def);
    }

    /**
     * 必填键后缀：把契约里的 `required` 附在行尾
     * （模型漏写必填键会被直接打回）。
     *
 * ⚠️  起**不再剔除 type**：原先认为它"自明"（行首就写着
     * rectangle / arrow…）而省掉，但这恰恰让模型以为 type 不属于该写的键
     * —— 实战里 15 模块大图第一轮 38 个 issue 全是"缺少必填字段 type"。
     * 真值是 schema 的 required，就照抄，别再自作聪明。
     */
    private static String requiredSuffix(JsonNode def) {
        return SchemaContractRenderer.requiredSuffix(def);
    }

    /**
     * 单个字段的紧凑写法：`fillStyle` → `fillStyle[hachure|...]`，
     * `opacity` → `opacity 0-100`，`label` → `label{"text"`}。
     * 未知的 `$ref` 递归展开一层，不认识就退回字段名，绝不抛异常 ——
     * 提示词拼装失败等于整条生成链路跪掉，这里必须稳。
     */
    private static String renderProp(String name, JsonNode node, JsonNode definitions) {
        return SchemaContractRenderer.renderProp(
                name, node, definitions, FIELD_HINTS);
    }

    private static String renderPropBase(String name, JsonNode node, JsonNode definitions) {
        return SchemaContractRenderer.renderPropBase(
                name, node, definitions, FIELD_HINTS);
    }

    /**
     * 数组的简写：points → `[[0,0],[dx,dy]...]`，其余 → `[...]`.
     *
 * ⚠️ ：原来写的是 `[[dx,dy]...]`，看不出**首点必须是 [0,0]**
     * —— 因为 points 是相对 x/y 的偏移（Excalidraw 约定，前端
     * `withArrowGeometry` 也是按 `[[0,0],[终点-起点]]` 生成的）。
     * 模型据此把**绝对坐标**写进 points，同时把 x/y 也设成第一个点，
     * 于是落点 = (x+dx, y+dy) ≈ 坐标翻倍：实测画猫那组 9 条线全被画到视野外，
     * 而校验器只检查每个点是不是两个数字、**不检查首点**，静默通过。
     * 渲染成 `[[0,0],[dx,dy]...]` 让这个约定自解释。
     */
    private static String arrayShape(JsonNode node) {
        return SchemaContractRenderer.arrayShape(node);
    }

    /** {code exclusiveMinimum:0} 表示「必须大于 0」，提示词里写成 `(>0)`。 */
    private static boolean isExclusivePositive(JsonNode node) {
        return SchemaContractRenderer.isExclusivePositive(node);
    }


    /**
     * 动态层内容块：当前场景 + 修改规则 + 用户需求
     *
 * 这一块**每轮变化**（用户换了需求、图被改了），所以进 user 消息。
     *
 *  增量投喂：编辑模式下元素数超过
     * {@value #EDIT_INLINE_MAX_ELEMENTS} 时，当前场景不再整份贴出，
     * 改为 compactSceneBlock（结构摘要 + 被改元素全文 + 引用骨架）。
     * 小图（≤ 阈值）仍走整份贴出 —— 那条路径的文案与  逐字节一致，
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
     * 修改规则段（引入； 抽成方法，两条路径共用同一份文案）。
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
        sb.append("  - ⚠️ **「再画一个 X / 加一个 X」≠ 重新画**：只要 X 是**具体要新增的对象**"
                + "（如「再画一只猪」「加一个监控图表」「补一张饼图」），这叫**追加**，"
                + "必须走「改这张图」的保真路径，旧元素一个都不能丢；"
                + "只有用户说「重新画 / 换一张 / 全部重做 / 这图不要了 / 重新来」才算另画一张、才允许丢弃。\n");
        sb.append("- 你输出的是**修改后的完整场景**（全量 elements），不是差异片段、不是新增部分。\n");
        sb.append("- **没有要求改动的元素必须原样保留**：`id` / `type` / 几何（x / y / width / height）"
                + "/ `label` / 样式全部照抄，不要重新编号、不要重排坐标、不要精简掉你认为多余的元素。\n");
        sb.append("- 「删除某元素」= 结果里不包含它（同时删掉连到它的箭头）。\n");
        sb.append("- 删的是**链路中间**的节点时，必须补桥接边：原本 A → 中间 → B 两条边要合并成一条 A → B"
                + "（两端绑到 A 和 B），除非用户明确说「断开」；"
                + "删掉节点后原图必须仍然连通，不能留下悬空的断头。\n");
        sb.append("- 「新增元素」= 起一个不与现有 id 重复的新 id（沿用现有命名风格）。\n");
        sb.append("- 「改文字 / 改颜色」= 只动那一个字段，其余照抄。\n");
        sb.append("- 只有「另画一张」的情况，或用户明确要求「重新画 / 换一张 / 全部重做」，"
                + "才允许丢弃原有元素。\n\n");
        return sb.toString();
    }

    /**
 * 大图编辑模式的紧凑场景块。
     *
 * 由三部分组成（对齐计划里写的「结构摘要 + 被改元素全文 + 引用」）：
     *
 * - 结构摘要 —— 每个元素一行骨架（id / type / 几何 / label 文本 /
     *       绑定端点 / group），**全量**列出。之所以骨架里必须带几何与 label：
     *       输出侧仍要求"未点名元素原样保留"（md 约束 5），若摘要丢了这些信息，
     *       模型就只能瞎猜几何 —— 图会散。带上以后，未点名元素可被逐字复现，
     *       **无需后端做合并**；
     *
 * - 被改元素全文 —— 用户这句话里点名到的元素（按 id 或 label 文本
     *       命中），给出完整 JSON，省得模型只凭骨架猜样式；
     *
 * - 引用骨架 —— 箭头 / 线的端点、containerId 归属，供模型在
     *       删元素时顺手清掉悬空箭头（md 约束 5）。
     *
     * @param scene    已剥离 `_userElements` 的场景根节点
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
     * 单个元素的一行骨架。
     *
 * 格式：`id type (x,y,w x h) start->end "label" group=... `，
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
        String group = GroupPath.display(el);
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
     * 引用骨架行：端点绑定或 containerId 归属。
     *
     * @param el 元素节点
     * @return `e1: n1 -> n2` 或 `t1: in n1`；无引用时返回空串
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
     * 判断元素是否被用户这句话"点名"。
     *
 * 朴素判据：用户文本里出现了该元素的 id 或 label 文本。
     * 命中就把它整份 JSON 附上 —— 宁可多给一个，也不少给；判据保守
     * （长度 < 2 不参战）以免短标签（如「是」）到处误命中。
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
     * 取 `start` / `end` 这类端点对象里的 id。
     *
     * @param node 端点节点（可能是 `{"id":"n1"`} 或缺失）
     * @return 端点 id；取不到返回空串
     */
    private String endpoint(JsonNode node) {
        if (node != null && node.isObject() && node.hasNonNull("id")) {
            return node.get("id").asText();
        }
        return "";
    }

    /**
     * 取元素的可见文字：优先 `label.text`（图形），其次 `text`（文字元素）。
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
     * 数字字段读取（骨架里用；整数去小数点，避免 `0.0` 这类噪音）。
     *
     * @param node  节点
     * @param field 字段名
     * @return 简洁数字串；非数字返回 `"?"`
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
     * 单行 JSON把元素整份塞进「被改元素」段）。
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
     * 容错解析：场景 JSON 不可解析时返回 null，调用方退回整份贴出。
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
     * 元素计数。
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
     * 修正块：上一轮校验未通过
     *
 * 不再声明 IOException —— 它本身没有任何 I/O。
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
     * 场景段：把会话模型里的"用户手绘"旁路摘掉之后的 JSON。
     *
 * 为什么要摘：`_userElements` 是后端内部字段，
     * 混在"当前场景"里会让 LLM 以为它是场景的一部分，
     * 进而原样抄进输出 —— 那反而成了新的重复源。
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
     * 用户手绘元素段
     *
 * 用户手绘的线**不在 elements 里**（后端不持有它们的几何），
     * 于是 LLM 完全不知道用户已经在那里连了一条线，会再画一条 ——
     * 用户看到的就是"同一处两条线"（实测：
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
     * 取 schema 的 {@code definitions} 节点（解析后缓存，report 2 优化 #7）。
     *
     * <p>{@link #buildContractBlock()} 与 {@link #contractSection(String)}
     * 原先各自解析一遍 14.3KB 的 {@code scene.schema.json}；文件读已由
     * {@link #resourceCache} 省掉，JSON 解析没有。这里统一成一份缓存的
     * 已解析树，两个出口共享。
     *
     * <p>返回的 {@code JsonNode} 是缓存的**同一实例**，调用方只读不改
     * （本类全部用法都是 {@code get}/{@code fieldNames} 只读遍历）。
     *
     * @return {@code definitions} 节点
     * @throws IOException 读 / 解析 schema 失败
     */
    private JsonNode schemaDefinitions() throws IOException {
        JsonNode cached = definitionsCache;
        if (cached != null) {
            return cached;
        }
        JsonNode parsed =
                objectMapper.readTree(loadResource(SCHEMA_PATH)).get("definitions");
        definitionsCache = parsed;
        return parsed;
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
