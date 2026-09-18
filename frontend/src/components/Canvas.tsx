import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Excalidraw, CaptureUpdateAction } from '@excalidraw/excalidraw';
// 类型子路径必须是 '@excalidraw/excalidraw/types'（包 exports 把 './*' 映射到 dist/types/excalidraw/*.d.ts）；
// 主入口 index.d.ts 并不导出 ExcalidrawImperativeAPI，写成 '.../types/types' 会报 TS2307
import type { ExcalidrawImperativeAPI } from '@excalidraw/excalidraw/types';
import type { CanvasSyncPayload, SceneElement } from '../types';
import type { RemovalPlan } from '../lib/sceneAdapter';
import {
  collectUnboundArrows,
  convertScene,
  healCorruptedArrows,
  isFullyBoundArrow,
  isModelElement,
  planRemoval,
  toUserElementBriefs,
} from '../lib/sceneAdapter';

interface CanvasProps {
  elements: SceneElement[];
  /** 画布现状同步：坐标 + 被删元素 + 用户手绘（v2-34） */
  onSync: (payload: CanvasSyncPayload) => void;
  /** 渲染出现问题时的上报口（画布本身没有 UI 反馈位，不能让失败静默） */
  onRenderIssue?: (message: string) => void;
}

/** 坐标回写防抖窗口。onChange 在拖拽时每帧都触发，不防抖会把后端请求刷屏 */
const POSITION_REPORT_DEBOUNCE_MS = 400;

/**
 * 中间画布（flex: 1）
 * 完全继承 PoC 原型的 Excalidraw 交互能力
 *
 * 场景所有权约定（2026-09-16 修正）：
 * - 后端 IR 生成的元素带 `customData.chartflowModel = true`；
 * - 用户手绘的元素不带该标记；
 * - 收到新 IR 时**只替换带标记的那部分**，用户画的一律保留
 *   （旧实现在这里无脑 `updateScene({ elements })`，直接吃掉用户手绘内容）。
 */
const Canvas: React.FC<CanvasProps> = ({ elements, onSync, onRenderIssue }) => {
  const [api, setApi] = useState<ExcalidrawImperativeAPI | null>(null);

  /** 上一次应用过的 AI 元素 id 签名，用于判断"是否新一轮生成" */
  const appliedSignatureRef = useRef('');
  /** 坐标回写防抖计时器 */
  const reportTimerRef = useRef<number | null>(null);
  /** 上一次已同步的载荷快照，用于过滤"无实质变化"的 onChange */
  const lastSyncRef = useRef('');
  /**
   * 上一次场景转换的结果。
   *
   * <p>算"用户删了什么"时必须参考它：被转换器丢弃的元素从未上过画布，
   * 把它们的 id 当成"用户删掉的"上报，后端模型会被越删越少。
   */
  const conversionRef = useRef<{ dropped: string[]; fatal?: string }>({
    dropped: [],
  });
  /** 视口适配的 requestAnimationFrame 句柄（卸载时要取消，否则会对已销毁实例调 API） */
  const viewportFrameRef = useRef<number | null>(null);

  /** 合并策略：AI 元素在下、用户元素在上（用户标注压在最顶层） */
  useEffect(() => {
    if (!api) return;

    const { elements: converted, dropped, fatal } = convertScene(elements);
    conversionRef.current = { dropped, fatal };

    // Bug A（2026-09-17）：用户先手绘、AI 首次"接管"画布时，旧逻辑会把
    // 用户手绘和 AI 新图叠在一起 → 用户看到两张图（手绘在右、AI 在左）。
    // 判据：本轮 AI 场景非空、且上一轮 AI 签名是空（此前画布只有手绘）。
    // 这种情况整体替换画布（不保留旧手绘），让 AI 吸收用户的原图。
    const isTakeover =
      converted.length > 0 && appliedSignatureRef.current === '';
    const userElements = isTakeover
      ? []
      : api
          .getSceneElements()
          .filter((el) => !isModelElement(el));

    api.updateScene({
      elements: [...converted, ...userElements],
      // 后端推送属于"远端更新 / 场景初始化"，按 0.18 语义用 CaptureUpdateAction.NEVER
      // （旧版写法 commitToHistory: false 在 0.18.1 已移除，会报 TS2353）
      captureUpdate: CaptureUpdateAction.NEVER,
    });

    const signature = converted.map((el) => el.id).join('|');
    const isNewGeneration = signature !== '' && signature !== appliedSignatureRef.current;
    appliedSignatureRef.current = signature;

    // 视口接管策略：只在"新一轮生成"（AI 元素 id 签名变化）时适配一次视口，
    // 且**把用户手绘一起纳入取景范围**。
    //   旧实现两处都错：① 无条件执行（每次 onChange 触发的重渲染都抢视口）；
    //   ② 只框 AI 元素 —— 用户的手绘被留在取景框外，观感就是"跳到另一个画布"。
    //   只框 AI 元素也不行：用户有手绘时 AI 图可能整个落在视口外，又变成"生成了看不到"。
    // 所以取景 = AI 元素 ∪ 用户元素：新图一定可见，用户的图案也仍在画面里。
    if (isNewGeneration) {
      // ⚠️ 必须用 `fitToContent`，不能用 `fitToViewport`：
      // Excalidraw 0.18 的 scrollToContent opts 是互斥联合类型
      //   {fitToContent?: boolean; fitToViewport?: never}
      // | {fitToViewport?: boolean; fitToContent?: never}
      // `fitToViewport` 是「适配 frame 视口」专用分支，对普通图形算不出正确 bounds ——
      // 后果就是聊天说"已生成 N 个元素"、画布却全空，只剩一个「滚动回到内容」按钮：
      // 元素已进场景，视口留在了原地。
      //
      // 另延后一帧再取景：`updateScene` 刚提交，等 Excalidraw 完成这次 store/布局提交，
      // 避免 bounds 基于上一帧的元素集合算。
      viewportFrameRef.current = window.requestAnimationFrame(() => {
        viewportFrameRef.current = null;
        try {
          api.scrollToContent([...converted, ...userElements], {
            fitToContent: true,
            animate: false,
          });
        } catch (e) {
          console.warn('[Canvas] scrollToContent 失败:', e);
        }
      });
    }

    // 失败必须让用户看见：画布空白本身没有任何解释能力
    if (fatal) {
      onRenderIssue?.(`画布渲染失败：${fatal}`);
    } else if (dropped.length > 0) {
      onRenderIssue?.(
        `画布渲染：${dropped.length} 个元素无法渲染已跳过（id: ${dropped.join(', ')}）`,
      );
    }
  }, [api, elements, onRenderIssue]);

  /**
   * 真正同步画布现状：坐标 + 被删元素 + 用户手绘（v2-34）。
   *
   * <p>此前只同步坐标，于是"用户删了什么""用户手绘了什么"后端完全
   * 不知情：被删的元素下一轮被 LLM 原样带回来（复活），用户手绘的线
   * AI 又看不见（于是再画一条）—— 同一处出现两条线。
   *
   * <p>仍然只回写 AI 元素，且整体无变化时不发请求。
   */
  const flushSync = useCallback(() => {
    if (!api) return;
    const apiScene = api.getSceneElements();
    // Bug B（2026-09-17 修正）：拖拽使绑定元素重叠 + fit 缩放时，Excalidraw
    // 会把绑定箭头的几何污染成垃圾坐标（实测既有 2^21 哨兵、也有 -3907 中段），
    // 连线被甩到画布外 → 视觉上「透明消失 / 箭头消失」。healCorruptedArrows 现用
    // 「端点必须落在被绑图形包围盒上」的不变量检测（不再靠坐标绝对值阈值），
    // 任何量级的污染都抓得到，并顺手把箭头标签挪回新中点。
    const healed = healCorruptedArrows(apiScene);
    if (healed) {
      api.updateScene({
        elements: healed,
        captureUpdate: CaptureUpdateAction.NEVER,
      });
    }
    const scene = healed ?? apiScene;

    const positions: Record<string, { x: number; y: number }> = {};
    scene
      .filter((el) => isModelElement(el))
      // 两端都绑定的箭头不回写：它的几何由两端图形决定，
      // 回写只会把 Excalidraw 的中间结果灌进后端 IR（见 isFullyBoundArrow）
      .filter((el) => !isFullyBoundArrow(el))
      .forEach((el) => {
        if (el.id && el.x != null && el.y != null) {
          positions[el.id] = { x: el.x, y: el.y };
        }
      });

    // 整批转换失败时禁止上报"删除"：否则"没画出来"会被误判成
    // "用户把图全删了"，后端模型被清空且不可逆
    const { dropped, fatal } = conversionRef.current;
    const plan: RemovalPlan = fatal
      ? { removedIds: [] }
      : planRemoval(elements, scene, dropped);

    // 孤儿连线要**真从画布上摘掉**：Excalidraw 删图形时不会连带删
    // 绑定它的箭头（2026-09-16 真实 UI 实测），留着就是一条连向空处的
    // 线段 —— 用户看到的就是"凭空多出来的线"。
    // 摘掉会触发 onChange，但下一轮算出的载荷与本次完全一致，
    // 会被签名比对挡住，不会二次发请求。
    const removedSet = new Set<string>(plan.removedIds);
    const survivors = scene.filter((el) => !(el.id != null && removedSet.has(el.id)));
    if (survivors.length !== scene.length) {
      api.updateScene({
        elements: survivors,
        captureUpdate: CaptureUpdateAction.NEVER,
      });
    }

    const userElements = toUserElementBriefs(scene);
    // 用户拖过某条线 = Excalidraw 解除了它的绑定。不回写的话，后端模型
    // 里它还是绑定的，下一轮渲染又把它绑回原处，用户白拖。
    const unboundArrows = fatal
      ? []
      : collectUnboundArrows(elements, scene);

    const payload: CanvasSyncPayload = {
      positions,
      removedIds: plan.removedIds,
      userElements,
      unboundArrows,
    };
    const signature = JSON.stringify(payload);
    if (signature === lastSyncRef.current) return;
    lastSyncRef.current = signature;
    onSync(payload);
  }, [api, elements, onSync]);

  /**
   * onChange 会在挂载 / 选中 / 悬停 / 拖拽每帧 / 撤销时都触发，
   * 所以必须防抖，并且只在"坐标真的变了"时才回写（v2-24a 缺陷#4）。
   */
  const handleChange = useCallback(() => {
    if (reportTimerRef.current !== null) {
      window.clearTimeout(reportTimerRef.current);
    }
    reportTimerRef.current = window.setTimeout(flushSync, POSITION_REPORT_DEBOUNCE_MS);
  }, [flushSync]);

  useEffect(
    () => () => {
      if (reportTimerRef.current !== null) {
        window.clearTimeout(reportTimerRef.current);
      }
      if (viewportFrameRef.current !== null) {
        window.cancelAnimationFrame(viewportFrameRef.current);
      }
    },
    [],
  );

  /**
   * dev 调试探针（v2-30 雏形）：把 Excalidraw 的命令式 API 挂到 `window.__chartflow`，
   * 供自动化测试与人工排查直接读真实状态（`getSceneElements()` / `getAppState()`）。
   *
   * 之所以需要它：画布空白这类问题在 UI 上没有任何反馈位，此前只能靠肉眼猜——
   * 有了探针，「元素到底在不在场景里」「视口停在哪个坐标」一次 eval 就能问清楚。
   * 生产构建下 `import.meta.env.DEV` 为 false，整段会被摇掉。
   */
  useEffect(() => {
    if (!import.meta.env.DEV || !api) {
      return;
    }
    const scope = window as unknown as { __chartflow?: ExcalidrawImperativeAPI };
    scope.__chartflow = api;
    return () => {
      delete scope.__chartflow;
    };
  }, [api]);

  return (
    <div className="canvas-container">
      <Excalidraw
        excalidrawAPI={setApi}
        onChange={handleChange}
        langCode="zh-CN"
        theme="light"
        initialData={{ appState: { viewBackgroundColor: '#ffffff' } }}
      />
    </div>
  );
};

export default Canvas;
