package io.github.nihaoljx.flowchart.client;

/**
 * 当前任务的"难度档"，用于按难度升档选模型（2026-10-07）。
 *
 * <p>生成线程内设置，路由据此决定哪些候选可用。难度由**两个**信号取大者：
 * <ul>
 *   <li><b>轮次</b>：round 1 只用 {@code minRound <= 1} 的便宜 / 不限量候选；
 *       round &gt;= 2 说明首轮没通过校验，属"难任务"，才放开限量的强模型。</li>
 *   <li><b>需求规模</b>：用户一开口就点了十几个模块时，第 1 轮就已经
 *       注定难做 —— 2026-10-07 实测 15 模块大图首轮四个便宜候选全超时，
 *       白烧一轮。此时直接把难度档提到 2，让强模型第 1 轮就能上。</li>
 * </ul>
 *
 * <p>用 ThreadLocal 而非方法参数：不改动 {@link LlmProvider} 接口，
 * GenerationService 只需在任务开始前 set 一次。
 */
public final class LlmEffort {

    /** 默认轮次：1（还没跑过任何一轮） */
    private static final int DEFAULT_ROUND = 1;

    /** 规模升档能达到的最高难度档：只提到 2，不越级 */
    private static final int MAX_SCALE_EFFORT = 2;

    private static final ThreadLocal<Integer> ROUND =
            ThreadLocal.withInitial(() -> DEFAULT_ROUND);

    /** 需求规模档（见 service.RequestScale 的 SMALL / MEDIUM / LARGE） */
    private static final ThreadLocal<Integer> SCALE =
            ThreadLocal.withInitial(() -> 1);

    private LlmEffort() {
    }

    public static void setRound(int round) {
        ROUND.set(Math.max(1, round));
    }

    public static int currentRound() {
        return ROUND.get();
    }

    /**
     * 设置本次需求的规模档。
     *
     * @param scale 1=小 / 2=中 / 3=大（只在 3 时抬升难度档）
     */
    public static void setScale(int scale) {
        SCALE.set(Math.max(1, scale));
    }

    public static int currentScale() {
        return SCALE.get();
    }

    /**
     * 有效难度档 = max(轮次, 规模换算出的档)。
     *
     * <p>只有"大活"才抬到 2：中活仍先走便宜模型，靠第 2 轮升档兜 ——
     * 限量强模型的额度很紧，不能让中等需求也随手烧掉。
     */
    public static int effectiveEffort() {
        int fromScale = currentScale() >= 3 ? MAX_SCALE_EFFORT : 1;
        return Math.max(currentRound(), fromScale);
    }

    /** 清空两个 ThreadLocal，避免线程复用时的串味 */
    public static void clear() {
        ROUND.remove();
        SCALE.remove();
    }
}
