package io.github.nihaoljx.flowchart.critique;

/**
 * 视觉批判器接口：语义同 {@link DiagramCritic}，但实现类应基于「渲染后的位图」
 * 做视觉理解（重叠观感、流向是否可读、是否贴合意图等）。
 *
 * <p>当前项目无服务端渲染，默认实现 {@link NoopVisionCritic} 返回空，避免阻断
 * 生成。真正的 LLM-with-vision 实现是后续 P1 工作（需要把 Excalidraw 场景
 * 渲染成 PNG 再交给视觉模型），集成点就在本接口。
 */
public interface VisionCritic extends DiagramCritic {
}
