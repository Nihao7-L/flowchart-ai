package io.github.nihaoljx.flowchart.client;

/** 路由策略（任务34新增） */
public enum RoutingStrategy {
    FIXED,        // 固定用第一个（默认）
    ROUND_ROBIN,  // 轮询
    WEIGHTED      // 权重路由（暂未实现，留 TODO，目前等同 FIXED）
}
