package io.github.nihaoljx.flowchart.agent;

import java.util.List;
import java.util.Map;

/**
 * 一次 agent run 的共享上下文（v2-55）
 *
 * <p>工具之间通过它传递"当前场景"：{@code submit_scene} 写入、
 * {@code validate_scene} / {@code read_scene} 读取。字段分两级：
 * <ul>
 *   <li>{@code processedScene} —— 走完管线且读得通的 JSON（可能带
 *       校验 issue）；超限输出 partial 时用它（最后一份合法进度）；</li>
 *   <li>{@code validScene} —— 校验零 issue 的 JSON；只有它能作为
 *       成品落库下发。</li>
 * </ul>
 *
 * <p>线程口径：一次 run 由单线程驱动（工具串行执行），不设锁。
 */
public final class ToolContext {

    private final String userText;
    private final List<Map<String, Object>> userElements;
    private String processedScene;
    private String validScene;

    public ToolContext(String userText, List<Map<String, Object>> userElements) {
        this.userText = userText == null ? "" : userText;
        this.userElements = userElements == null ? List.of() : List.copyOf(userElements);
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
