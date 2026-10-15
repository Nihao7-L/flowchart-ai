package io.github.nihaoljx.flowchart.llm;

import java.util.List;
import java.util.Map;

/**
 * 一次 agent run 的共享上下文
 *
 * 工具之间通过它传递"当前场景"：`submit_scene` 写入、
 * `read_scene` 读取。字段分两级：
 *
 * - `processedScene` —— 走完管线且读得通的 JSON（可能带
 *       校验 issue）；超限输出 partial 时用它（最后一份合法进度）；
 *
 * - `validScene` —— 校验零 issue 的 JSON；只有它能作为
 *       成品落库下发。
 *
 * 线程口径：一次 run 由单线程驱动（工具串行执行），不设锁。
 */
public final class ToolContext {

    private final String userText;
    private final List<Map<String, Object>> userElements;

    /**
     * 编辑模式的"修改前场景"。
     *
 * 非 null = 本次是"改这张图"。改图保真检查（防整图重画 /
     * 防保留 id 但整图重排）需要拿提交结果与它对比 —— 只比对提交
     * 的场景与这个基线，缺了它，"删掉节点X"就可能被模型理解成
     * 整图重画（Bug A 同类事故）。
     */
    private final String previousJson;

    /**
     * 改图保真检查是否已做过。
     *
 * 保真检查只做一次（它是提醒不是门禁，
     * 第 2 轮 LLM 坚持重画就放行 —— 用户本来就可能明确要求重画）。
     * agent 循环没有"轮"的概念，对齐语义用"只看首次提交"：
     * 首次提交过后即标记，后续提交不再拦。
     */
    private boolean rewriteChecked;

    private String processedScene;
    private String validScene;

    public ToolContext(String userText, List<Map<String, Object>> userElements) {
        this(userText, userElements, null);
    }

    public ToolContext(String userText, List<Map<String, Object>> userElements,
                       String previousJson) {
        this.userText = userText == null ? "" : userText;
        this.userElements = userElements == null ? List.of() : List.copyOf(userElements);
        this.previousJson = previousJson;
    }

    /** 编辑基线（修改前场景 JSON）；新建模式为 null */
    public String previousJson() {
        return previousJson;
    }

    /** 改图保真检查是否已做过（只做一次：提醒，不是门禁） */
    public boolean rewriteChecked() {
        return rewriteChecked;
    }

    /** 标记改图保真检查已执行 */
    public void markRewriteChecked() {
        this.rewriteChecked = true;
    }

    /** 用户本次的原始需求（工具组提示/兜底文案时用） */
    public String userText() {
        return userText;
    }

    /** 用户手绘旁路元素（去重器要拿它与 LLM 的线比对；新建无手绘时为空表） */
    public List<Map<String, Object>> userElements() {
        return userElements;
    }

    /** 走完管线且读得通的场景 JSON（可能带 issue），尚无则为 null */
    public String processedScene() {
        return processedScene;
    }

    /** 记录管线产物（parseGate 已过） */
    public void processedScene(String json) {
        this.processedScene = json;
    }

    /** 校验零 issue 的场景 JSON，尚无则为 null */
    public String validScene() {
        return validScene;
    }

    /** 记录校验通过的场景 */
    public void validScene(String json) {
        this.validScene = json;
    }
}
