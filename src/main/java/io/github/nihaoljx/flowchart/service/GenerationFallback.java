package io.github.nihaoljx.flowchart.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method
        .annotation.SseEmitter;

/**
 * 生成失败的兜底纪律（唯一出口）
 *
 * 收拢"这次生成没成，给用户看什么"的全部决策：
 * 兜底话术（fallbackOutcome）、兜底场景选材（fallbackScene）
 * 与下发收尾（deliverFallback）。
 *
 * 原则：**宁可给一张不完美的图，也不能给空画布** ——
 * 空白画布是这个产品能出的最高危事故。与之相对，
 * 真实后端故障（加载提示词资源失败）不走兜底：
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

    /** 兜底场景选材依赖种子工位（空场景判定 / 版本盖章 / 占位合成） */
    private final SceneSeedBuilder seeds;

    public GenerationFallback(SceneSeedBuilder seeds) {
        this.seeds = seeds;
    }

    /**
     * 兜底说明里"图现在是什么状态"那半句
     *
     * 两种兜底场景（LLM 调用失败 / agent 循环未通过校验）
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
     * 构造兜底场景：**至少一个能画出来的元素**
     *
     * LLM 调用失败 / agent 循环未通过校验，都走这里选材。
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
