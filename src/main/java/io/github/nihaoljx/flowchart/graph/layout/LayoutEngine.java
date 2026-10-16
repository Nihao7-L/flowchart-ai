package io.github.nihaoljx.flowchart.graph.layout;

import java.util.Set;

/**
 * 布局引擎
 *
 * 把"场景里各元素的坐标怎么摆"这件事收敛成一个可替换的部件。
 * 调用方（`GenerationService`）只依赖本接口，不依赖具体算法 ——
 * 于是自研分层布局（SceneLayout）与 ELK（`ElkLayoutEngine`）
 * 可以按配置切换，而流水线一行不改。
 *
 * 契约（两种模式，实现者必须都支持）：
 *
 * - `fixedIds` 为空 = 新建：可以对整张图做全量重排；
 *
 * - `fixedIds` 非空 = 编辑：这些 id 的坐标必须
 *       一个像素都不许动，只给其余（新增）图形找位置。
 *       这条不是洁癖 —— SceneDrift 会把"整图重排"判为几何漂移，
 *       两边打架会让每次编辑都像换了一张图。
 *
 * 失败即降级：任何异常都应吞掉并返回入参原样（坐标保持
 * LLM 给的种子值），布局失败不该让整轮生成失败。
 */
public interface LayoutEngine {

    /**
     * 给场景里的图形重新分配坐标。
     *
     * @param json     已做过端点吸附与去重的场景 JSON
     * @param fixedIds 坐标必须原样保留的图形 id；空集 = 新建模式
     * @return 布局后的 JSON；无可摆放图形或发生异常时返回入参原样
     */
    String layout(String json, Set<String> fixedIds);

    /**
     * 模块内布局：与 {@link #layout} 同契约，但**禁止**"按渲染视口
     * 翻转主方向"那条启发式 —— 模块内的窄高是预期形态。
     *
     * <p>为什么要分开（2026-10-16 五图实测）：Transformer 的
     * ENCODER（6 节点）/ DECODER（10 节点）两个模块被排成
     * 1940x70 / 3900x70 的横排长条，整图成了 6040x80 的一根线。
     * 根因不是模型给的拓扑，而是引擎里那条"两个主方向都出理想区间
     * （0.5~2.0）就取更宽的一侧"的启发式 —— 它是为**整图**准备的
     * （渲染视口是横屏，竖条最伤缩放），却同样作用在模块子图上：
     * 一条 6 节竖链的长宽比本就是 0.28，必然"出区间"，于是每次都被
     * 扭成横排。而模块的窄高并不伤视口 —— 整图长宽比由模块之间的
     * 网格打包决定，不由单个模块决定。
     *
     * @param json     模块子图（只有本模块成员与内部边）
     * @param fixedIds 坐标必须原样保留的图形 id；空集 = 新建模式
     * @return 布局后的 JSON
     */
    default String layoutModule(String json, Set<String> fixedIds) {
        return layout(json, fixedIds);
    }

    /**
     * 指定主方向的模块内布局：只按给定方向排一次，不做方向择优。
     *
     * <p>为什么要交给调用方定方向（2026-10-16 五图实测）：模块自己的
     * 长宽比说明不了该横还是该竖 —— 认证图里「AWS Cloud」4 节点横排
     * 成一条带是对的（参考图就是横带），Transformer 的「DECODER」
     * 10 节点竖排成一列也是对的（参考图就是竖列）。判断依据在
     * **整图打包后**的长宽比，那只有 ScenePlanner 看得见（它要试遍
     * 两个主方向再按整图长宽比定稿）。所以方向决策上移到排版层，
     * 引擎只负责执行。
     *
     * @param json       模块子图（只有本模块成员与内部边）
     * @param fixedIds   坐标必须原样保留的图形 id；空集 = 新建模式
     * @param horizontal true = 自左而右，false = 自上而下
     * @return 布局后的 JSON
     */
    default String layoutModule(
            String json, Set<String> fixedIds, boolean horizontal) {
        return layoutModule(json, fixedIds);
    }
}
