package io.github.nihaoljx.flowchart.graph.layout;

import java.util.List;
import java.util.Map;

/**
 * 布局家族：一种「图型」的确定性排版器
 *
 * <p>多图型架构的扩展点（2026-10-16 立骨架）：LLM 在 spec 里声明
 * {@code diagramType}，SubmitSpecTool 按图型查注册表分发到对应家族。
 * 一个家族 = 一套行业公认画法的确定性实现 —— 参与者横排 + 生命线
 * （sequence）、通用力导向/分层（generic）、将来的 state / mindmap。
 *
 * <p>三条纪律（违反任何一条都会烂）：
 * <ol>
 *   <li>**纯函数**：入参只有 spec 语义（nodes/edges，无几何），出参是带
 *       几何的场景 JSON。家族内部永远不调 LLM、不产随机值 —— 同 spec
 *       必得同图（可单测、可回归）。</li>
 *   <li>**不抛异常**：家族排版失败由调用方降级到 generic（见
 *       {@link LayoutFamilyRegistry#forType}），但家族自己也应尽量
 *       内部兜底，把异常交给注册表是最后手段。</li>
 *   <li>**不改语义**：只摆坐标与样式，不删节点/改连线。</li>
 * </ol>
 */
public interface LayoutFamily {

    /** 图型名（spec 的 diagramType 值；generic 是兜底家族） */
    String type();

    /**
     * 把带占位几何的场景 JSON 排成该图型的最终形态。
     *
     * @param sceneJson buildScene 产出的场景 JSON（节点在原点、箭头只有绑定）
     * @param nodes     spec 节点（首次出现序 = 参与者声明序）
     * @param edges     spec 连线（数组序 = 叙事序）
     * @return 排版后的场景 JSON；异常时调用方负责降级
     */
    String arrange(String sceneJson,
                   Map<String, SpecNode> nodes,
                   List<SpecEdge> edges);
}
