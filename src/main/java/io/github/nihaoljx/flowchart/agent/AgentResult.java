package io.github.nihaoljx.flowchart.agent;

/**
 * agent run 的终态（v2-55）
 *
 * <p>对齐 s17 原则"不伪装成功"：只有 {@code COMPLETED} 才带成品图；
 * 超限/超时输出 {@code PARTIAL}（最后一份读得通的进度，可能为 null），
 * 由调用方按"给用户一张能看的图 + 如实说明"处置，与 legacy 兜底同路径。
 *
 * @param status    终态：COMPLETED（校验零 issue）/ PARTIAL（超限或超时）
 * @param sceneJson COMPLETED 时必非空；PARTIAL 时为最后一份可解析场景（可空）
 * @param steps     实际消耗的模型轮数（含纯文本轮）
 * @param reason    终态原因的一句话说明（SSE/日志用）
 */
public record AgentResult(Status status, String sceneJson,
                          int steps, String reason) {

    public enum Status { COMPLETED, PARTIAL }
}
