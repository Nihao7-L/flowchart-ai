package io.github.nihaoljx.flowchart.service;

/**
 * 生成流程的时间预算（唯一的预算真相源）
 *
 * 职责只有一件事：说清"一次生成总共能花多久"。
 * 谁需要跟预算对齐（SSE 通道上限、agent run 截止时刻）都从这里读，
 * 不允许各处自设数字 —— 否则就会出现"生成还没跑完、通道先被掐断"。
 *
 * 轮次/单步超时不在这里：agent 循环按剩余预算自行折算
 * （AgentLoop.remainingSeconds），没有固定轮次概念。
 */
public final class GenerationBudget {

    /**
     * 单次生成的总时间预算（毫秒）
     *
     * SSE 通道上限由 DiagramController 取"本值 + 收尾余量"推导，
     * 两者因此恒等对齐。把 120 秒硬编码在控制器里、与本预算无关，
     * 而实测单轮 LLM 调用就要约 100 秒——慢图必然撞墙，现象是
     * 流被容器掐断、前端颗粒无收。
     *
     * 现值 3600 秒（1 小时）按实测推得：慢主力候选单次 tool_calls
     * 调用可达数分钟，8 步循环加上故障转移重试，窗口要容得下；
     * 预算不足会让后面的步骤窗口过小，慢候选必超时、别的候选又
     * 来不及试，误报"候选均不可用"提前收工。这是**上限**不是固定
     * 等待——简单图几步达标即停，只有高难度图才会逼近上限。
     */
    public static final long TOTAL_BUDGET_MS = 3_600_000L;

    private GenerationBudget() {
    }

    /**
     * 总预算的运行时读数。
     *
     * 为什么需要它：TOTAL_BUDGET_MS 是 static final 字面量，
     * 别的类若写 `GenerationBudget.TOTAL_BUDGET_MS + 20_000L` 就是一个
     * **编译期常量表达式**，javac 会把它内联进调用方的 class 文件，
     * 运行时不再回读本类 —— "只改本文件、调用方源码没动"时增量编译
     * 不重编译调用方，两边的值静默失配（曾导致 SSE 通道先于生成结束被
     * 掐断）。方法调用不是编译期常量，调用方永远读到当前值。
     * 需要与本值恒等对齐的地方一律调本方法，不要直接引用常量。
     */
    public static long totalBudgetMs() {
        return TOTAL_BUDGET_MS;
    }
}
