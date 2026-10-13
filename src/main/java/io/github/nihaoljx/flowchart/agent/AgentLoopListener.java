package io.github.nihaoljx.flowchart.agent;

/**
 * agent 循环的过程监听器（v2-55 步骤 3）
 *
 * <p>循环本体不碰 SSE —— 过程可见性通过这个回调交出去（接线层把它
 * 翻译成 {@code tool_call}/{@code tool_result} 事件）。所有方法都是
 * default 空实现：调用方只关心哪几类就覆写哪几类，监听器自身出异常
 * 由循环兜住（见 {@link AgentLoop}），绝不能让"上报进度"打穿生成。
 *
 * <p>监听器运行在生成线程上，实现必须快 —— 慢的落盘/推送请自行缓冲。
 */
public interface AgentLoopListener {

    /** 一步开始（模型调用前）。step 从 1 计，上限 {@link AgentLoop#MAX_STEPS} */
    default void onStep(int step, int maxSteps) {
    }

    /** 模型提议了一次工具调用（执行前） */
    default void onToolCall(ToolCall call) {
    }

    /** 工具执行完毕（观察结果已回喂消息账本） */
    default void onToolResult(ToolCall call, Observation observation) {
    }
}
