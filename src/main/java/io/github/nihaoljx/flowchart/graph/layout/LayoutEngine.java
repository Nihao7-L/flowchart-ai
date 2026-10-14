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
}
