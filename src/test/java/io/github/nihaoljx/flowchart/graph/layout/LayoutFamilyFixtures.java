package io.github.nihaoljx.flowchart.graph.layout;

import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.prompt.DiagramPromptRegistry;

import java.util.List;

/**
 * 测试夹具：默认的布局家族注册表（generic + sequence）
 *
 * <p>为什么抽出来：注册一个完整的 {@link LayoutFamilyRegistry} 要连带 new 出
 * ScenePlanner / ElkLayoutEngine 一串，那一行 195~238 字符，被**原样复制到
 * 4 个测试类、共 6 处**（AgentLoopTest / AgentLoopRealLlmIT /
 * AgentLoopVisualFeedbackTest / SubmitSpecToolTest），既撞 checkstyle 的
 * 120 字符行宽，也让"加一个新布局家族"要改 6 个地方。
 *
 * <p>加图型时改这里一处，所有用到默认注册表的测试一起跟上。
 */
public final class LayoutFamilyFixtures {

    private LayoutFamilyFixtures() {
    }

    /**
     * @return 含 generic 与 sequence 两个家族的注册表
     */
    public static LayoutFamilyRegistry defaultRegistry() {
        return new LayoutFamilyRegistry(List.of(
                new GenericLayoutFamily(new GenericLayoutFamily.ScenePlannerHolder(
                        new ScenePlanner(), new ElkLayoutEngine())),
                new SequenceLayout()));
    }

    /**
     * 图型提示词注册表（classpath 内置那 25 种模板，不依赖外部目录配置）。
     *
     * <p>与 {@link #defaultRegistry()} 放一起是因为调用方总是成对需要它们：
     * 装配 {@code AgentTools.defaultRegistry(...)} 时两个都得传。
     */
    public static DiagramPromptRegistry defaultPromptRegistry() {
        return new DiagramPromptRegistry();
    }
}
