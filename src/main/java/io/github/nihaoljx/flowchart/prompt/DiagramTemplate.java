package io.github.nihaoljx.flowchart.prompt;

import java.util.List;

/**
 * 一份**图型提示词模板**（白话：某种图专用的一小段规则，例如「时序图该怎么画」）。
 *
 * <p>来源是一个 md 文件，形如：
 * <pre>
 * ---
 * diagramType: sequence
 * version: 1.0.0
 * title: 时序图
 * keywords: 时序图, 时序, sequence
 * layout: sequence
 * ---
 * 参与者横排、每人一条生命线……
 * </pre>
 *
 * <p>与内核 md（{@code system-prompt.md}）的分工：
 *
 * <ul>
 *   <li><b>内核</b>——所有图型共用（角色、输出格式、执行过程、裁决顺序）；
 *   <li><b>图型模板</b>——只有这一种图才需要的版面与声明规则，命中才注入。
 * </ul>
 *
 * <p>为什么要拆开：内核 md 已经顶到字节闸门（v10.13.0 时 9987B / 上限 10000B，
 * 只剩 13B）。每加一种图的规则都要从公共规则里挤字节，等于"加图型"和
 * "改通用规则"互相绑架。拆开后图型模板各自单独计预算，加图型不再动内核。
 *
 * <p><b>图型（diagramType）与版面（layout）是两件事</b>：图型是「这是什么图」
 * （gantt / fishbone / venn…），版面是「用哪套排版算法」（目前只有 generic 与
 * sequence）。25 种图型不必各写一套排版器 —— 模板里写 {@code layout: generic}
 * 即可复用通用排版，只有时序图这种版面特殊的才写 {@code layout: sequence}。
 *
 * @param diagramType 图型名，spec 的 {@code diagramType} 字面值（模型照抄）
 * @param version     模板版本（frontmatter 的 {@code version}，缺省 {@code 0.0.0}）
 * @param title       展示名，渲染成段标题
 * @param keywords    触发关键词，**确定性路由**的判据（不是让模型判断）
 * @param layout      排版用的布局家族名（缺省 {@code generic}）
 * @param body        已剥离注释的正文
 */
public record DiagramTemplate(String diagramType,
                              String version,
                              String title,
                              List<String> keywords,
                              String layout,
                              String body) {

    /** 没写 layout 的模板一律走通用排版 */
    private static final String DEFAULT_LAYOUT = "generic";

    public DiagramTemplate {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        layout = (layout == null || layout.isBlank())
                ? DEFAULT_LAYOUT
                : layout.trim();
        if (diagramType == null || diagramType.isBlank()) {
            throw new IllegalArgumentException("图型模板缺少 diagramType");
        }
    }
}
