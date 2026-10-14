package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.graph.validate.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成失败的兜底纪律（唯一出口）
 *
 * 收拢"这次生成没成，给用户看什么"的全部决策：
 * 兜底话术（fallbackOutcome）、issue 摘要（issueDigest）、
 * 兜底场景选材（fallbackScene）与下发收尾（deliverFallback）。
 *
 * 原则：**宁可给一张不完美的图，也不能给空画布** ——
 * 空白画布是这个产品能出的最高危事故。与之相对，
 * 真实后端故障（加载 schema 失败、组装修正消息失败）不走兜底：
 * 那种情况下后端自己出了问题，给一张假图只是把事故盖住，
 * 必须保持 error 语义。
 */
public class GenerationFallback {

    private static final Logger LOG =
            LoggerFactory.getLogger(
                    GenerationFallback.class);

    /**
     * 失败兜底时，是否优先下发"本轮已产出的可解析场景"
     *
     * 仅在**新建模式**（没有原图可还）生效：宁可给一张画得不完美的图，
     * 也别退回占位框 —— 这就是"任务跑到一半断掉不丢进度"。
     * 编辑模式一律还原图：半成品覆盖用户原图比占位更糟。
     */
    private static final boolean DELIVER_LAST_PARSEABLE_ON_FAILURE = true;

    /**
     * 兜底说明里 issue 摘要的最大字符数
     *
     * issues 一多，全量拼出来能有两三千字 —— 那条说明会随 SSE 一起
     * 推给前端进消息列表，撑爆它比少写点原因糟得多。只截摘要，
     * 结论（"已把原来的图还给你，请把图形拆小后重试"）始终完整。
     */
    private static final int SUMMARY_MAX_LEN = 200;

    /**
     * 前端可见的校验明细上限
     *
     * 比 SUMMARY_MAX_LEN 宽一些：这条是发给"人"看的诊断信息
     * （前端消息列表 + 排查日志），不是兜底话术的一部分。
     * 一轮 30+ 个 issue 全量拼出来两三千字，会把通道撑爆。
     */
    private static final int DIGEST_MAX_LEN = 320;

    /** 摘要里单个原因最多显示几个字（超了截断，否则一条长原因就吃满额度） */
    private static final int DIGEST_REASON_CHARS = 18;

    /** 摘要里最多列几类原因 */
    private static final int DIGEST_TOP_REASONS = 3;

    /** 兜底场景选材依赖种子工位（空场景判定 / 版本盖章 / 占位合成） */
    private final SceneSeedBuilder seeds;

    public GenerationFallback(SceneSeedBuilder seeds) {
        this.seeds = seeds;
    }

    /**
     * 兜底说明里"图现在是什么状态"那半句
     *
     * 四种兜底场景（超时 / LLM 调用失败 / 返回空白 / 多轮修不动）
     * 统一用它收尾，措辞刻意**中性**：早先写的是「已把原来的图还给你」，
     * 在编辑模式下会被读成"我刚画的图被你退回来了"，把一次"没改动成功"
     * 误读成"出了错"。实际发生的是**图原样没动**，所以这么说。
     *
     * 新建模式下没有"原图"可还，只能给一条占位说明，两种要分开说，
     * 否则用户会对着一张占位框找"我原来的图呢"。
     *
     * @param editMode 本次是"改这张图"还是"画一张新图"
     * @return 描述图当前状态的一句话
     */
    public String fallbackOutcome(boolean editMode) {
        return editMode
                ? "本次未生成新内容，图保持原样"
                : "本次未生成新内容，给你一条占位说明";
    }

    /**
     * 把一轮校验问题压成一行"能看懂主因"的摘要。
     *
     * 为什么归类而不是原样罗列：大图一轮 30+ 个 issue 里，真正的原因
     * 往往只有两三类 —— 比如"箭头既给 start/end 又给 points"这一条就能
     * 同时命中十几个元素。原样罗列时前 200 字全被同一个原因的不同元素
     * 占满，看的人反而看不出主因是什么。
     *
     * 输出形如：
     * `共 35 个问题，前三类: 箭头路径… x17 / group 只属… x9；例: elements[12] …`
     *
     * @param issues 本轮校验问题
     * @return 单行摘要（长度受 DIGEST_MAX_LEN 约束）
     */
    public String issueDigest(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "0 个问题";
        }
        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (ValidationIssue issue : issues) {
            byReason.merge(
                    shorten(issue.reason(), DIGEST_REASON_CHARS),
                    1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> top = byReason.entrySet()
                .stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(DIGEST_TOP_REASONS)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(issues.size())
                .append(" 个问题，前三类: ");
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(top.get(i).getKey())
                    .append(" x").append(top.get(i).getValue());
        }
        ValidationIssue first = issues.get(0);
        sb.append("；例: ").append(first.field())
                .append(' ').append(first.reason());
        return sb.length() > DIGEST_MAX_LEN
                ? sb.substring(0, DIGEST_MAX_LEN) + "…"
                : sb.toString();
    }

    /** 只留前 n 个字符；null 当空串 */
    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 兜底说明里的摘要截断：超长截到 SUMMARY_MAX_LEN 加省略号，
     * 结论部分始终完整
     */
    public String truncatedSummary(String digest) {
        if (digest.length() > SUMMARY_MAX_LEN) {
            return digest.substring(0, SUMMARY_MAX_LEN)
                    + "…";
        }
        return digest;
    }

    /**
     * 构造兜底场景：**至少一个能画出来的元素**
     *
     * 超时 / LLM 调用失败 / 返回空白 / 多轮修不动，都走这里选材。
     *
     * 两级降级，顺序不可换：
     *
     * - 新建模式且本轮已产出过可解析场景 → 优先下发它（不丢进度）；
     *   编辑模式先把**原图**还回去（用户眼下那张就是合法场景，
     *   只是这次没改成功）；"手绘提升"分支的种子场景比原模型
     *   更贴近用户所见，那种情况下它优先。
     *
     * - 原图不存在 / 它自己也是空的（会话本来就没元素），才合成
     *   一条占位文字。
     *
     * 注意候选必须**非空**才收：原模型里 elements 为空、
     * 内容还堆在 _userElements 旁路，正是要修的那个毛病 ——
     * 收了等于换个姿势把空画布发出去。
     *
     * @param previousJson       会话里修改前的模型；可为 null
     * @param effectiveSceneJson 真正喂给模型的当前场景；可为 null
     * @param takeOver           是否走了"用户手绘提升"分支
     * @param bestSoFar          本轮已产出的可解析场景；可为 null
     * @param hint               新建模式下合成占位文字的内容
     * @return 至少一个元素的合法场景 JSON；合成彻底失败时返回 null
     *         （调用方据此退回 error 语义）
     */
    public String fallbackScene(
            String previousJson,
            String effectiveSceneJson,
            boolean takeOver, String bestSoFar, String hint) {
        // 新建模式：本轮已产出过可解析场景 → 优先下发它（不丢进度）
        if (DELIVER_LAST_PARSEABLE_ON_FAILURE
                && !takeOver
                && previousJson == null
                && bestSoFar != null
                && !bestSoFar.isBlank()
                && !seeds.isEmptyScene(bestSoFar)) {
            return seeds.stampSchemaVersion(bestSoFar);
        }
        String candidate = takeOver
                ? effectiveSceneJson : previousJson;
        if (candidate != null
                && !candidate.isBlank()
                && !seeds.isEmptyScene(candidate)) {
            // 盖版本号：下发到前端的每张图都带 IR 版本（ADR-2）。
            // 解析不了就原样返回，版本号是保险，不能因此阻断。
            return seeds.stampSchemaVersion(candidate);
        }
        return seeds.syntheticScene(hint);
    }

    /**
     * 下发兜底场景并正常收尾通道
     *
     * 原因走 validation 通道（前端当 agent 消息显示，
     * 与"第 N 轮校验失败"同一惯用法），场景本身走 result
     * —— 前端把它当终态开关。
     *
     * 这里**不落库**：兜底内容可能是用户原有的图（会话里本来就有），
     * 也可能是占位文字（落库会让下一次编辑以一条垃圾文字为起点）。
     * 两种都不写，用户重试时起点与今天看到的完全一致。
     *
     * @param emitter  SSE 发射器
     * @param note     为什么降级；也会写进占位文字里
     * @param fallback 兜底场景 JSON；为 null 表示兜底本身失败
     * @return true 表示已下发；false 表示调用方应退回 error 语义
     */
    public boolean deliverFallback(
            SseEmitter emitter, String note,
            String fallback) {
        if (fallback == null) {
            return false;
        }
        GenerationEvents.sendEvent(
                emitter, "validation", note);
        GenerationEvents.sendEvent(
                emitter, "result", fallback);
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            LOG.warn("SSE 通道已结束，跳过收尾: {}",
                    e.getMessage());
        }
        return true;
    }
}
