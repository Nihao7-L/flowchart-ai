import { convertToExcalidrawElements } from '@excalidraw/excalidraw';
import type { SceneElement, UnboundArrowBrief, UserElementBrief } from '../types';

/**
 * 「AI 生成元素」的标记位，写在 Excalidraw 元素的 `customData` 上。
 *
 * 用途：把「后端 IR 生成」与「用户手绘」两类元素区分开。
 * 生成新图时只替换前者，用户自己画的图案必须保留
 * （2026-09-16 用户反馈：先画一个图案再生成，之前画的不见了）。
 */
export const MODEL_FLAG = 'chartflowModel';

export type ConvertedElement = ReturnType<typeof convertToExcalidrawElements>[number];

export interface SceneConversion {
  /** 可直接喂给 `updateScene` 的元素（已打上 MODEL_FLAG） */
  elements: ConvertedElement[];
  /** 因转换失败被跳过的元素 id（用于向用户报错，不再静默） */
  dropped: string[];
  /** 整批都救不回来时的错误摘要 */
  fatal?: string;
}

const errText = (e: unknown): string =>
  e instanceof Error ? `${e.name}: ${e.message}` : String(e);

/** 判断一个场景元素是否由后端 IR 生成 */
export const isModelElement = (el: { customData?: unknown }): boolean => {
  const data = el.customData as Record<string, unknown> | null | undefined;
  return data?.[MODEL_FLAG] === true;
};

/**
 * 两端都绑定到图形的箭头。
 *
 * <p>这类箭头的几何**完全由两端图形决定**，自身那份 `x` / `y` / `points`
 * 只是 Excalidraw 算出来的中间结果。坐标回写时必须把它们排除掉：
 * 否则后端 IR（唯一真相源）会凭空长出一份会随时间漂移的冗余坐标，
 * 下一轮生成又把它当"用户给的坐标"读回去（v2-33）。
 *
 * <p>只绑一端的箭头**不能**排除 —— 自由那一端的位置是它自己的事实。
 */
export const isFullyBoundArrow = (el: {
  type?: string;
  startBinding?: unknown;
  endBinding?: unknown;
}): boolean => el.type === 'arrow' && !!el.startBinding && !!el.endBinding;

/**
 * 后端 IR → Excalidraw skeleton 的字段白名单。
 *
 * 后端 schema 是 additionalProperties:false，直接展开 `{...el}` 语义上也安全；
 * 但显式白名单的好处是：契约新增字段时这里会立刻提醒"前端还没跟上"，
 * 而不是把未知字段悄悄喂给 Excalidraw。
 */
const SKELETON_KEYS = [
  'type', 'id', 'x', 'y', 'width', 'height', 'angle',
  'label', 'strokeColor', 'backgroundColor', 'fillStyle',
  'strokeWidth', 'strokeStyle', 'roughness', 'roundness',
  'link', 'opacity',
  'points', 'start', 'end', 'startArrowhead', 'endArrowhead',
  'text', 'fontSize', 'fontFamily', 'textAlign', 'verticalAlign',
  'autoResize', 'name',
] as const;

/**
 * 只搬运"有值"的字段。
 *
 * ⚠️ 不能写 `text: el.text` 这种无条件赋值：Excalidraw 的转换器对 text 元素的
 * `text` 字段直接 `.replace()`，拿到 undefined 就抛
 * `TypeError: Cannot read properties of undefined (reading 'replace')`，
 * 且是**整批**失败（整个场景都画不出来）。
 */
export const toSkeleton = (el: SceneElement): Record<string, unknown> => {
  const raw = el as unknown as Record<string, unknown>;
  const skeleton: Record<string, unknown> = {};
  for (const key of SKELETON_KEYS) {
    if (raw[key] !== undefined) {
      skeleton[key] = raw[key];
    }
  }
  return skeleton;
};

/**
 * 契约 → 转换器入参的适配补丁。
 *
 * ⚠️ 补丁一：`frame` 缺 `children`。
 * scene.schema.json 的 frame 分支（additionalProperties:false）**没有** `children`，
 * 而 `convertToExcalidrawElements` 对 frame 强制访问 `children.forEach(...)`，
 * 拿到 undefined 即抛 `TypeError: Cannot read properties of undefined (reading 'forEach')`。
 * 后果与 text 漏字段一样：**整批元素转换失败，画布全空**。
 * 实测（2026-09-16，探针矩阵）：frame 无 children → THROW；children:[] → PASS。
 *
 * ⚠️ **2026-09-18 修正**：`children` 的性质判断变了 —— 它**不是**"渲染器
 * 实现细节、无语义"，而是「框 ↔ 内容」归属的**唯一**途径（对照实测：传
 * 成员 id 列表时，成员元素的 `frameId` 被反填为 frame 的 id；传 `[]` 时
 * 成员 `frameId` 恒为 `null`，框与内容在数据模型里毫无关系 ——
 * 拖框带不走内容、删框也带不走内容）。
 *
 * 「不让 LLM 填」这个动机不变（它填不准、填错会连带影响整批转换），
 * 但落法要改成：**由后端按分组与几何算出成员列表、这里填进去**（v2-40 P1）。
 * 在 P1 落地前本补丁维持 `[]`，以免行为在本阶段发生漂移；回归锁在
 * `frontend/tools/scene-smoke.mjs` 的 frame 用例里。
 *
 * ⚠️ 补丁二：**x / y 必须是有限数字**（这是"画布空白"的真凶）。
 * 契约里 arrow 的 `x` / `y` 是可选的（绑定式箭头只需 `start.id` + `end.id`），
 * 于是转换产物带 `x = undefined`。后果不是抛错，而是**静默毁掉整个视口**：
 *   `getCommonBounds(elements)` → `[NaN, NaN, NaN, NaN]`
 *   → `scrollToContent` 用 NaN 算视口 → 视口损坏
 *   → 元素全被画到视口外，画布看起来一片空白，只剩一个「滚动回到内容」按钮。
 * 2026-09-16 浏览器实测（`tools/browser-nan.sh`）：
 *   bounds 修复前 = ["NaN","NaN","NaN","NaN"]，canvas 非白像素 0；
 *   补上 x/y 后 = ["0.5","-1.52","720","240"]，canvas 非白像素 18843。
 * 所以这里兜底补 0 —— 但注意：**只在转换前补**才有意义，
 * 转换后再改 x/y 不会重算箭头位置（会让箭头堆在原点）。
 */
const normalize = (
  skeleton: Record<string, unknown>,
  byId: Map<string, Record<string, unknown>>,
): Record<string, unknown> => {
  const out: Record<string, unknown> = { ...skeleton };

  // 数值兜底：NaN / Infinity / undefined / 非数字字符串都归一成 0。
  // 只处理 x/y —— 它们是 getCommonBounds 的输入，也是 NaN 的唯一来源。
  for (const key of ['x', 'y']) {
    const value = Number(out[key]);
    if (!Number.isFinite(value)) {
      out[key] = 0;
    }
  }

  if (out.type === 'frame') {
    out.children = [];
  }

  if (out.type === 'arrow') {
    return withArrowGeometry(out, byId);
  }

  return out;
};

/** 元素中心点（绑定式箭头需要一个真实起点，否则箭头会落在原点） */
const centerOf = (el: Record<string, unknown>): { x: number; y: number } => {
  const x = Number(el.x);
  const y = Number(el.y);
  const w = Number(el.width);
  const h = Number(el.height);
  return {
    x: (Number.isFinite(x) ? x : 0) + (Number.isFinite(w) ? w : 0) / 2,
    y: (Number.isFinite(y) ? y : 0) + (Number.isFinite(h) ? h : 0) / 2,
  };
};

/**
 * 元素包围盒边界上、朝着 `target` 的那个交点。
 *
 * 为什么不用中心点：中心点会让箭头**从图形内部穿过去**，
 * 与图形内的 label 文字叠在一起（2026-09-16 截图实测）。
 * 用边缘交点才符合"连线连到框上"的直觉。
 *
 * 用包围盒近似（ellipse / diamond 的精确边界需要各自的几何公式，
 * 视觉差异很小，不值得为此增加复杂度）。
 * `target` 落在元素内部时退回中心点，避免算出反向或零长度线段。
 */
const edgePointTowards = (
  el: Record<string, unknown>,
  target: { x: number; y: number },
): { x: number; y: number } => {
  const width = Number(el.width);
  const height = Number(el.height);
  const center = centerOf(el);
  const halfWidth = Number.isFinite(width) ? width / 2 : 0;
  const halfHeight = Number.isFinite(height) ? height / 2 : 0;
  if (halfWidth <= 0 && halfHeight <= 0) {
    return center;
  }

  const dx = target.x - center.x;
  const dy = target.y - center.y;
  if (dx === 0 && dy === 0) {
    return center;
  }

  const scaleX = dx !== 0 ? halfWidth / Math.abs(dx) : Number.POSITIVE_INFINITY;
  const scaleY = dy !== 0 ? halfHeight / Math.abs(dy) : Number.POSITIVE_INFINITY;
  const scale = Math.min(scaleX, scaleY);
  if (!Number.isFinite(scale) || scale >= 1) {
    // 目标在元素内部（或元素没有尺寸）
    return center;
  }
  return { x: center.x + dx * scale, y: center.y + dy * scale };
};

/**
 * 绑定式箭头的几何推导。
 *
 * LLM 输出形如 `{id:"a1", type:"arrow", start:{id:"n1"}, end:{id:"n2"}}` ——
 * **没有任何坐标信息**，而 Excalidraw 的 skeleton 要求 arrow 必须有 `x` / `y` / `points`。
 * 这里用被绑定元素的几何把箭头补成合法的绝对坐标：
 *   - 起点 = 起点元素中心，`x` / `y` 即该绝对坐标；
 *   - `points` = `[[0,0], [终点中心 - 起点中心]]`（相对坐标，Excalidraw 约定）。
 *
 * 只补"缺什么"：契约若已给了 x/y 与 points 就原样透传，不做二次加工。
 * 端点找不到时退回原点 + 一小段水平线，保证至少是个合法元素而不是 NaN。
 */
const withArrowGeometry = (
  skeleton: Record<string, unknown>,
  byId: Map<string, Record<string, unknown>>,
): Record<string, unknown> => {
  const bindingId = (field: 'start' | 'end'): string | undefined => {
    const binding = skeleton[field] as { id?: unknown } | undefined;
    return typeof binding?.id === 'string' ? binding.id : undefined;
  };

  const startId = bindingId('start');
  const endId = bindingId('end');
  if (!startId && !endId) {
    // 纯 points 路径的箭头，几何自带，不干预
    return skeleton;
  }

  const startEl = startId ? byId.get(startId) : undefined;
  const endEl = endId ? byId.get(endId) : undefined;

  const hasXY = Number.isFinite(Number(skeleton.x)) && Number.isFinite(Number(skeleton.y));
  const hasPoints = Array.isArray(skeleton.points) && skeleton.points.length >= 2;
  if (hasXY && hasPoints) {
    return skeleton;
  }

  // 取「边缘交点」而非中心点：中心点会让箭头穿过图形、与框内文字叠在一起
  const startCenter = startEl ? centerOf(startEl) : { x: 0, y: 0 };
  const endCenter = endEl
    ? centerOf(endEl)
    : { x: startCenter.x + 120, y: startCenter.y };
  const from = startEl ? edgePointTowards(startEl, endCenter) : startCenter;
  const to = endEl ? edgePointTowards(endEl, startCenter) : endCenter;

  return {
    ...skeleton,
    x: from.x,
    y: from.y,
    points: hasPoints ? skeleton.points : [[0, 0], [to.x - from.x, to.y - from.y]],
  };
};

const runConverter = (
  skeletons: Record<string, unknown>[],
): ConvertedElement[] =>
  convertToExcalidrawElements(
    skeletons as unknown as Parameters<typeof convertToExcalidrawElements>[0],
    // ⚠️ 必须显式 false。默认 true 会重新生成所有元素 id，后果有二：
    //   (a) 箭头 start/end 绑定与后端 IR 的 id 脱钩；
    //   (b) 坐标回写 PUT /api/model/canvas 带的是随机 id，后端 model 永远匹配不上。
    { regenerateIds: false },
  );

/** 给转换产物打上 AI 来源标记（便于场景合并与坐标回写时区分） */
const tagModel = (els: ConvertedElement[]): ConvertedElement[] =>
  els.map(
    (el) =>
      ({
        ...el,
        customData: { ...(el.customData ?? {}), [MODEL_FLAG]: true },
      }) as ConvertedElement,
  );

/**
 * 尺寸兜底 —— NaN 的**第二个**来源。
 *
 * 2026-09-16 实测：`freedraw` 转换后 `width` / `height` 是 `undefined`
 * （其余类型转换器会自己算），而 `getCommonBounds` 用 `x + width` 求右边界，
 * `200 + undefined` 即 NaN → 视口再次损坏 → 画布照样空白。
 *
 * 缺尺寸时按 `points` 的包围盒推算（这正是 Excalidraw 对线类元素的定义），
 * 再不行补 0。x / y 也一并兜底，保证「坐标 + 尺寸」四项全为有限数字。
 */
const ensureFiniteSize = (els: ConvertedElement[]): ConvertedElement[] =>
  els.map((el) => {
    // points 只存在于线类元素上，在联合类型上直接访问不到，按需断言
    const rawPoints = (el as unknown as { points?: unknown }).points;
    const points: number[][] = Array.isArray(rawPoints) ? (rawPoints as number[][]) : [];
    const xs = points.map((p) => Number(p?.[0])).filter(Number.isFinite);
    const ys = points.map((p) => Number(p?.[1])).filter(Number.isFinite);
    const span = (values: number[]): number =>
      values.length ? Math.max(...values) - Math.min(...values) : 0;

    // 线类元素（arrow / line）的包围盒**必须**按 points 推算：
    // 转换器只对 arrow 会算，对 `line` 给的是默认尺寸
    // （2026-09-16 实测：points 横跨 800px，转换产物 width=100）
    // → 选中框与包围盒失真。points 自带宽高定义，重算永远比沿用可靠。
    const spanX = span(xs);
    const spanY = span(ys);
    const linear = points.length >= 2 && (spanX > 0 || spanY > 0);

    const width = linear ? spanX : Number(el.width);
    const height = linear ? spanY : Number(el.height);
    const x = Number(el.x);
    const y = Number(el.y);
    if (
      Number.isFinite(width) &&
      Number.isFinite(height) &&
      Number.isFinite(x) &&
      Number.isFinite(y)
    ) {
      return el;
    }

    return {
      ...el,
      x: Number.isFinite(x) ? x : 0,
      y: Number.isFinite(y) ? y : 0,
      width: Number.isFinite(width) ? width : 0,
      height: Number.isFinite(height) ? height : 0,
    } as ConvertedElement;
  });

/**
 * 画布元素的宽松视图。
 *
 * <p>Excalidraw 的元素类型是个大联合，各分支字段不同；这里只声明
 * 我们读得到的那几个（且全部可选），让联合类型能直接传进来。
 */
export interface RawCanvasElement {
  id?: string;
  type?: string;
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  customData?: unknown;
  containerId?: unknown;
  points?: readonly (readonly number[])[];
  text?: string;
  startBinding?: { elementId?: unknown } | null;
  endBinding?: { elementId?: unknown } | null;
}

/**
 * 画布上"用户自己画的"元素（v2-34）。
 *
 * <p>两条排除规则：
 * <ul>
 *   <li>带 {@link MODEL_FLAG} 的由后端 IR 生成；</li>
 *   <li>有 {@code containerId} 的是挂在别的元素上的文字（例如箭头的
 *       标签），它是那个元素的附属物 —— 把它当"用户手绘"上报，
 *       后端会看到一堆莫名其妙的文字元素。</li>
 * </ul>
 */
export const isUserElement = (el: RawCanvasElement): boolean =>
  !isModelElement(el) && el.containerId == null;

/**
 * 把用户手绘元素压成给后端看的轻量描述。
 *
 * <p>字段缺就缺（后端全部按可选处理）：这是"避重提示"，不是契约对象，
 * 多一个字段少一个字段都不该让同步失败。
 */
export const toUserElementBriefs = (
  elements: readonly RawCanvasElement[],
): UserElementBrief[] => {
  const briefs: UserElementBrief[] = [];
  for (const el of elements) {
    if (!isUserElement(el)) continue;
    const brief: UserElementBrief = {};
    if (el.id != null) brief.id = el.id;
    if (el.type != null) brief.type = el.type;
    if (typeof el.x === 'number' && Number.isFinite(el.x)) brief.x = el.x;
    if (typeof el.y === 'number' && Number.isFinite(el.y)) brief.y = el.y;
    if (typeof el.width === 'number' && Number.isFinite(el.width)) brief.width = el.width;
    if (typeof el.height === 'number' && Number.isFinite(el.height)) brief.height = el.height;
    if (Array.isArray(el.points)) brief.points = el.points;
    const startId = el.startBinding?.elementId;
    if (typeof startId === 'string') brief.startId = startId;
    const endId = el.endBinding?.elementId;
    if (typeof endId === 'string') brief.endId = endId;
    if (typeof el.text === 'string' && el.text.trim()) brief.text = el.text;
    briefs.push(brief);
  }
  return briefs;
};

/** 这一次要删哪些元素（v2-34） */
export interface RemovalPlan {
  /** 后端模型里有、画布上已不存在的元素 id（含被连坐的 AI 连线） */
  removedIds: string[];
}

/**
 * 算出这一次要删哪些元素。
 *
 * <p><b>必须排除本次转换被丢弃的元素</b>：它们从来没画上过画布，
 * 不是用户删的。误报会让后端模型被越删越少，而且是不可逆的。
 * 调用方还必须在"整批转换失败"时传空数组 —— 那种情况下所有元素
 * 都不在画布上，会被整体误判成"用户把图全删了"。
 *
 * <p><b>为什么要连坐删连线</b>：2026-09-16 在真实 UI 里实测 ——
 * 选中一个图形按 Delete，**它的箭头不会被一起删**，前端只上报了
 * `removedIds:["n2"]`，而连着 n2 的两条线原样留在画布上。
 * 结果就是画布上多出一条连向空处的线段，正是用户抱怨的
 * "让他删几个节点，画出来的图有的地方多加了这么多的线"。
 *
 * <p>只对**带模型标记**的连线连坐：用户自己手绘的线归他自己处置，
 * 我们无权因为他删了个图形就顺手抹掉他的涂画。
 *
 * <p><b>连坐必须查模型、不能查画布</b>：Excalidraw 在图形被删掉时会把
 * 存活那端的绑定一并清掉（删 n2 后 a12 由 `n1→n2` 变成 `n1→无`）。
 * 只顺着画布绑定找引用，一条都找不到 —— 这正是本功能第一次写完仍不生效的原因。
 */
export const planRemoval = (
  modelElements: readonly SceneElement[],
  canvasElements: readonly RawCanvasElement[],
  droppedIds: Iterable<string>,
): RemovalPlan => {
  const dropped = new Set(droppedIds);
  const alive = new Set<string>();
  for (const el of canvasElements) {
    if (el.id != null) alive.add(el.id);
  }

  const remove = new Set<string>();
  for (const el of modelElements) {
    if (!el.id || alive.has(el.id) || dropped.has(el.id)) continue;
    remove.add(el.id);
  }

  // 不动点扩散：删掉一个图形后，挂在它上面的 AI 连线也要删。
  //
  // ★ 必须查**模型**、不能查画布（2026-09-16 二次实测踩坑）：Excalidraw
  // 在图形被删掉时，会把存活那端的绑定一并清掉 —— 删 n2 后 a12 从
  // `n1→n2` 变成 `n1→无`，a23 从 `n2→n3` 变成 `无→n3`。画布上再也
  // 看不出这两条线曾连着 n2，顺着画布绑定去找引用会一条都找不到。
  // 模型（IR）是唯一真相源，它仍然记着 a12 绑 n1↔n2。
  //
  // 只遍历模型元素 = 只有 AI 连线会被连坐：用户手绘的线不在模型里，
  // 归他自己处置，我们无权因为他删了个图形就顺手抹掉他的涂画。
  for (let pass = 0; pass < 3; pass += 1) {
    let grew = false;
    for (const el of modelElements) {
      if (!el.id || remove.has(el.id)) continue;
      const refs = [el.start?.id, el.end?.id];
      if (refs.some((ref) => typeof ref === 'string' && remove.has(ref))) {
        remove.add(el.id);
        grew = true;
      }
    }
    if (!grew) break;
  }

  return { removedIds: Array.from(remove) };
};

/**
 * 找出"后端模型里写着绑定、画布上却已经解绑"的连线（v2-34）。
 *
 * <p>Excalidraw 在用户拖动一条线的身体、或拖端点圆点手柄时会
 * **解除两端绑定**（实测：`n1 → n2` 变成 `无 → 无`，元素本身还在、
 * 位置跟着鼠标走）。这个变化此前没有任何通道回写后端，后端模型里
 * 那条线仍然写着绑定 —— 下一轮渲染又把它绑回两端图形之间，
 * 用户拖的那一下白拖（2026-09-16 用户反馈："移动线条后线条就看不到了"）。
 *
 * <p>只报**状态真的变了**的那几条（模型说绑定、画布说不绑定），
 * 否则每拖一次图形都要把所有自由箭头重报一遍。
 */
export const collectUnboundArrows = (
  modelElements: readonly SceneElement[],
  canvasElements: readonly RawCanvasElement[],
): UnboundArrowBrief[] => {
  const modelBound = new Set<string>();
  for (const el of modelElements) {
    if (el.type === 'arrow' && el.id && el.start?.id && el.end?.id) {
      modelBound.add(el.id);
    }
  }

  const briefs: UnboundArrowBrief[] = [];
  for (const el of canvasElements) {
    if (el.id == null || el.type !== 'arrow') continue;
    if (!modelBound.has(el.id)) continue;
    if (el.startBinding || el.endBinding) continue;

    const x = el.x;
    const y = el.y;
    if (typeof x !== 'number' || !Number.isFinite(x)) continue;
    if (typeof y !== 'number' || !Number.isFinite(y)) continue;
    if (!Array.isArray(el.points)) continue;

    const pts: [number, number][] = [];
    for (const p of el.points) {
      const px = p[0];
      const py = p[1];
      if (typeof px !== 'number' || typeof py !== 'number') continue;
      if (!Number.isFinite(px) || !Number.isFinite(py)) continue;
      pts.push([px, py]);
    }
    if (pts.length < 2) continue;

    const brief: UnboundArrowBrief = { id: el.id, x, y, points: pts };
    if (typeof el.width === 'number' && Number.isFinite(el.width)) brief.width = el.width;
    if (typeof el.height === 'number' && Number.isFinite(el.height)) brief.height = el.height;
    briefs.push(brief);
  }
  return briefs;
};

/**
 * Bug B 修复（v2-35，2026-09-17 修正）：自愈被 Excalidraw 绑定重算污染的连线。
 *
 * 触发：拖动一个绑定元素使它在拖动途中与另一绑定元素重叠，且拖动伴随
 * fit() 缩放变化时，Excalidraw 内部绑定重算会把连线几何污染成垃圾坐标，
 * 实测既有 2^21 ≈ 2097152 这种哨兵，也有 -3907 这种「中段」坐标 ——
 * 两种都会把箭头甩到画布外 → 视觉上「连线透明消失 / 箭头消失」。
 * 该箭头的 start/end 绑定仍指向真实图形 id，但 x/y/points 被污染，
 * 且 Excalidraw 不会自愈（探针复现：a4.x = -2097053 永久卡死）。
 *
 * 检测**不靠坐标绝对值多大**的阈值（阈值法会漏掉 -3907 这种中段污染），
 * 而用**边界不变量**：一条两端都绑定的箭头，它的两个端点应当落在
 * 被绑元素的包围盒边界上（容差 {@link ARROW_HEAL_TOL}）。只要实际端点偏离
 * 期望位置超过容差，就判定为污染，用仍有效的绑定反推安全几何覆盖回去 ——
 * 不论污染量级多大都能抓到。连线回归两端图形之间。
 *
 * 同时把挂在它身上的文字标签（containerId === arrow.id）挪回新中点，
 * 否则箭头修好了、标签却留在旧位置（用户看到的「验证通过文字动不了」）。
 *
 * @returns 修正后的元素数组；没有任何连线被污染时返回 null（不触发 updateScene，避免无谓重绘）。
 */
const ARROW_HEAL_TOL = 300; // px：两端端点偏差之和超过此值即视为污染

/** 一条两端绑定的箭头，其端点「应当」落在哪。复用与生成期相同的几何推导 */
const expectedArrowEndpoints = (
  startEl: Record<string, unknown> | undefined,
  endEl: Record<string, unknown> | undefined,
): { from: { x: number; y: number }; to: { x: number; y: number } } => {
  const startCenter = startEl ? centerOf(startEl) : { x: 0, y: 0 };
  const endCenter = endEl
    ? centerOf(endEl)
    : { x: startCenter.x + 120, y: startCenter.y };
  // 端点取「朝向另一元素的包围盒边界交点」，与 withArrowGeometry 一致，
  // 这样治愈后的端点正好落在框边上，与绑定语义吻合。
  const from = startEl ? edgePointTowards(startEl, endCenter) : startCenter;
  const to = endEl ? edgePointTowards(endEl, startCenter) : endCenter;
  return { from, to };
};

const arrowEndpoints = (
  el: ConvertedElement,
): { start: { x: number; y: number }; end: { x: number; y: number } } => {
  const x = Number((el as unknown as { x?: unknown }).x);
  const y = Number((el as unknown as { y?: unknown }).y);
  const pts = (el as unknown as { points?: readonly (readonly number[])[] }).points;
  const p1 = Array.isArray(pts) && pts.length >= 2 ? pts[1] : [0, 0];
  const sx = Number.isFinite(x) ? x : 0;
  const sy = Number.isFinite(y) ? y : 0;
  return {
    start: { x: sx, y: sy },
    end: { x: sx + Number(p1?.[0] ?? 0), y: sy + Number(p1?.[1] ?? 0) },
  };
};

export const healCorruptedArrows = (
  elements: readonly ConvertedElement[],
): readonly ConvertedElement[] | null => {
  const byId = new Map<string, ConvertedElement>();
  for (const el of elements) {
    if (el.id != null) byId.set(el.id, el);
  }

  let changed = false;
  const healedIds = new Set<string>();
  const healed = elements.map((el) => {
    if (el.type !== 'arrow') return el;
    // 只修「两端都绑定」的箭头：它的几何完全由两端图形决定；
    // 自由端 / 单端绑定的几何是它自己的事实，不该被我们的反推覆盖。
    const sb = (el.startBinding as { elementId?: unknown } | null | undefined)
      ?.elementId;
    const eb = (el.endBinding as { elementId?: unknown } | null | undefined)
      ?.elementId;
    if (typeof sb !== 'string' || typeof eb !== 'string') return el;

    const startEl = byId.get(sb) as unknown as Record<string, unknown> | undefined;
    const endEl = byId.get(eb) as unknown as Record<string, unknown> | undefined;

    // 边界不变量：实际端点 vs 期望端点的偏差之和。
    // 合法连线偏差极小（矩形≈0、菱形≤~25px）；污染时偏差动辄上千 px。
    const { from, to } = expectedArrowEndpoints(startEl, endEl);
    const { start, end } = arrowEndpoints(el);
    const dev =
      Math.hypot(start.x - from.x, start.y - from.y) +
      Math.hypot(end.x - to.x, end.y - to.y);
    if (Number.isFinite(dev) && dev <= ARROW_HEAL_TOL) return el;

    // 用仍有效的绑定反推几何
    changed = true;
    if (el.id != null) healedIds.add(el.id);
    return {
      ...el,
      x: from.x,
      y: from.y,
      points: [
        [0, 0],
        [to.x - from.x, to.y - from.y],
      ],
    } as ConvertedElement;
  });

  if (!changed) return null;

  // 连带把**被修过的那几条**箭头的文字标签挪回新中点。
  //
  // 为什么只处理被修的：2026-09-17 像素级实测（probe_label_render）证明 ——
  // 绑定标签的 stored x/y 平日就是「过期」的（拖动后仍停在初始中点），
  // Excalidraw 渲染时按箭头几何现算，所以普通拖动下标签位置本来就是对的。
  // 只有当我们用 updateScene 从外部替换箭头几何（即 heal）时，才顺手把标签
  // stored 坐标也写正，避免这条外部替换路径让标签停在旧处。
  // 不加 `healedIds` 这个门的话，每次拖动都会因 stored 过期而触发一次无谓
  // updateScene（虽然不改渲染，但白刷一遍）。
  const healedById = new Map<string, ConvertedElement>();
  for (const el of healed) {
    if (el.id != null) healedById.set(el.id, el);
  }
  const withLabels = healed.map((el) => {
    if (el.type !== 'text') return el;
    const cid = (el as unknown as { containerId?: unknown }).containerId;
    if (typeof cid !== 'string' || !healedIds.has(cid)) return el;
    const host = healedById.get(cid);
    if (!host || host.type !== 'arrow') return el;
    const { start, end } = arrowEndpoints(host);
    const midX = (start.x + end.x) / 2;
    const midY = (start.y + end.y) / 2;
    const w = Number((el as unknown as { width?: unknown }).width);
    const h = Number((el as unknown as { height?: unknown }).height);
    return {
      ...el,
      x: Number.isFinite(w) ? midX - w / 2 : midX,
      y: Number.isFinite(h) ? midY - h / 2 : midY,
    } as ConvertedElement;
  });

  return withLabels;
};

/** 统一出口：打标记 + 尺寸兜底，保证任何路径产出的元素都能安全取景 */
const finishConversion = (
  els: ConvertedElement[],
  dropped: string[],
  fatal?: string,
): SceneConversion => ({
  elements: tagModel(ensureFiniteSize(els)),
  dropped,
  fatal,
});

/**
 * 把后端 IR 转成 Excalidraw 元素，**任何一个元素坏掉都不许拖垮整批**。
 *
 * 策略（fail loud, degrade gracefully）：
 * 1. 先整批转换（箭头 start/end 绑定需要同批元素在场才能解析）；
 * 2. 整批失败 → 逐元素试转，隔离出"毒元素"并记录 id；
 * 3. 幸存者再整批转换一次（保住箭头绑定）；
 * 4. 仍失败 → 返回空场景 + fatal 摘要，由调用方向用户显式报错。
 *
 * 之所以需要 2/3 步：LLM 输出是**无界**的，schema 只约束了我们想到的字段，
 * Excalidraw 转换器还有我们控制不了的隐含前提（children / text 就是两例）。
 * 过去这类异常被 catch 静默吞掉，表现为"聊天说已生成 N 个元素、画布空白"。
 */
export const convertScene = (elements: SceneElement[]): SceneConversion => {
  const dropped: string[] = [];

  const kept = elements.filter((el, index) => {
    // text 元素缺 text 会让转换器 `.replace` 抛错。
    // 后端 GraphValidator.validateText 已拦这一条，这里作为最后一道兜底。
    if (el.type === 'text' && !el.text?.trim()) {
      dropped.push(el.id ?? `#${index}`);
      return false;
    }
    // freedraw（手绘路径）在 AI 生成场景里没有正当用途：它是「人类手绘」的表达形式，
    // 让 LLM 编一条手绘轨迹毫无意义。而它在 Excalidraw 里的取景边界算不出来 ——
    // 2026-09-16 实测：points 合法、x/y/w/h 齐全，`getCommonBounds` 依然返回
    // [NaN, NaN, NaN, NaN]，照样毁掉视口让整张图看不见。
    // 用户手绘不经过本函数（它们在画布场景里，只回写坐标），所以跳过它不影响手绘。
    if (el.type === 'freedraw') {
      dropped.push(el.id ?? `#${index}`);
      return false;
    }
    return true;
  });

  // id → 原始元素 的索引：绑定式箭头要靠它找到端点图形的几何信息
  // （见 withArrowGeometry 的说明）
  const byId = new Map<string, Record<string, unknown>>();
  kept.forEach((el) => {
    if (el.id) {
      byId.set(el.id, el as unknown as Record<string, unknown>);
    }
  });

  const skeletons = kept.map((el) => normalize(toSkeleton(el), byId));

  if (skeletons.length === 0) {
    return { elements: [], dropped };
  }

  try {
    return finishConversion(runConverter(skeletons), dropped);
  } catch {
    // 整批失败：逐元素定位毒元素
    const survivors: Record<string, unknown>[] = [];
    skeletons.forEach((skeleton, index) => {
      try {
        runConverter([skeleton]);
        survivors.push(skeleton);
      } catch {
        dropped.push(String(skeleton.id ?? `#${index}`));
      }
    });

    if (survivors.length === 0) {
      return {
        elements: [],
        dropped,
        fatal: '所有元素都无法转换为画布元素',
      };
    }

    try {
      return finishConversion(runConverter(survivors), dropped);
    } catch (e) {
      return { elements: [], dropped, fatal: errText(e) };
    }
  }
};
