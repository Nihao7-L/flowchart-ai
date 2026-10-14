import { convertToExcalidrawElements } from '@excalidraw/excalidraw';
import type {
  SceneElement,
  SceneElementType,
  UnboundArrowBrief,
  UserElementBrief,
} from '../types';

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
 *
 * ⚠️ 刻意**不含** `group`：那是后端的语义层字段（模块名），排版用它分区，
 * 渲染器既不认识它、也不需要它 —— 语义层与渲染层分开正是 v2-40 的架构
 * （"语义编译"）。白名单守卫用例里对它做了显式豁免。
 */
const SKELETON_KEYS = [
  'type', 'id', 'x', 'y', 'width', 'height', 'angle',
  'label', 'strokeColor', 'backgroundColor', 'fillStyle',
  'strokeWidth', 'strokeStyle', 'roughness', 'roundness',
  'link', 'opacity', 'fileId', 'url',
  'points', 'start', 'end', 'startArrowhead', 'endArrowhead', 'closed',
  'text', 'fontSize', 'fontFamily', 'textAlign', 'verticalAlign',
  'autoResize', 'name', 'children', 'containerId',
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
 * ⚠️ 补丁一：`frame.children` —— **2026-09-18 已是"透传 + 兜底"，不再是"补空数组"**。
 *
 * 背景：`convertToExcalidrawElements` 对 frame 强制访问 `children.forEach(...)`，
 * 拿到 undefined 即抛 `TypeError: Cannot read properties of undefined (reading 'forEach')`，
 * 后果与 text 漏字段一样：**整批元素转换失败，画布全空**。
 * 实测（2026-09-16，探针矩阵）：frame 无 children → THROW；children:[] → PASS。
 *
 * 但 `children` **不是**"渲染器实现细节、无语义"（2026-09-18 对照实测推翻）——
 * 它是「框 ↔ 内容」归属的**唯一**途径：传成员 id 列表时，成员元素的 `frameId`
 * 被反填为 frame 的 id；传 `[]` 时成员 `frameId` 恒为 `null`，框与内容在数据
 * 模型里毫无关系（拖框带不走内容、删框也带不走内容）。
 *
 * 所以落法是（v2-40 P1 已落地）：**后端 `ScenePlanner` 按分组与几何算出成员
 * 列表写进契约**，「不让 LLM 填」的动机不变（它填不准、填错会连带影响整批
 * 转换）。这里负责两件事：不是数组就补 `[]`（防整批转换失败）、滤掉指向
 * 不存在元素的 id（防 LLM 越权填的脏数据）。回归锁在
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

  // magicframe 在 Excalidraw 0.18 是独立类型，但 convertToExcalidrawElements 的
  // skeleton 路径不认它（实测整批 fatal）。契约里 magicframe「语义同 frame」，
  // 这里降级成 frame 处理（children 由后续 frame 分支兜底），让它至少能渲染成框。
  if (out.type === 'magicframe') {
    out.type = 'frame';
  }

  // 数值兜底：NaN / Infinity / undefined / 非数字字符串都归一成 0。
  // 只处理 x/y —— 它们是 getCommonBounds 的输入，也是 NaN 的唯一来源。
  for (const key of ['x', 'y']) {
    const value = Number(out[key]);
    if (!Number.isFinite(value)) {
      out[key] = 0;
    }
  }

  if (out.type === 'frame') {
    // 2026-10-12 修复「frame 删名 bug」：停止无条件 delete out.name。
    // 分区框的标题就写在 name 上（Excalidraw 把 frame.name 渲染为框内居中标题），
    // 之前一律剥掉导致所有分区框显示成灰色默认 "Frame"。
    // 现在保留 name；由提示词约束「分区用 frame：标题写进 name，不再额外画独立标题」
    // 来避免叠字。name 仅作展示，不影响下方 children 成员表逻辑。
    // 成员表由**后端**算出（v2-40 P1，见上方"补丁一"）：
    //   1. 不是数组就补空数组 —— 转换器对 frame 强制 `children.forEach`，
    //      undefined 会让整批转换抛错、画布全空；
    //   2. 滤掉指向不存在元素（或指向自己）的 id —— LLM 越权填的成员不可信，
    //      而引用不存在的 id 同样会让转换失败。
    const raw = Array.isArray(out.children) ? out.children : [];
    out.children = raw.filter(
      (id): id is string =>
        typeof id === 'string' && id !== out.id && byId.has(id),
    );
  }

  if (out.type === 'arrow') {
    return withArrowGeometry(out, byId);
  }

  // line 闭合填充（#1，2026-09-28）：Excalidraw 0.18.1 的 line 元素**没有**
  // `closed` 布尔字段 —— 它靠 `isPathALoop(points)`（首点 ≈ 末点）判定是否
  // 闭合并填充（dist/.../chunk 渲染分支 9549-9553：只有成环才填 backgroundColor）。
  // 骨架里的 `closed:true` 被 `convertToExcalidrawElements` 直接丢弃（实测产物
  // 无该字段、points 未闭合），所以这里显式把首点补到末尾形成闭环；
  // backgroundColor / fillStyle 已由 SKELETON_KEYS 透传。这样"用线画填充
  // 多边形 / 等轴测伪 3D"才真能填色。已闭合（首末点重合）则不重复追加。
  if (out.type === 'line' && out.closed === true) {
    const pts = Array.isArray(out.points)
      ? (out.points as number[][]).slice()
      : [];
    if (pts.length >= 2) {
      const [fx, fy] = pts[0];
      const [lx, ly] = pts[pts.length - 1];
      if (fx !== lx || fy !== ly) {
        pts.push([fx, fy]);
        out.points = pts;
      }
    }
    // `closed` 字段对渲染无意义（转换器不认），但保留无害，且能明示意图。
  }

  // freedraw：Excalidraw 要求 pressures（0~1 的笔压）与 points 等长，
  // 契约里没这个字段（LLM 也编不出来），这里按中压 0.5 合成，避免转换器抛错。
  if (out.type === 'freedraw') {
    const pts = Array.isArray(out.points) ? (out.points as number[][]) : [];
    const pressures = out.pressures as number[] | undefined;
    if (!Array.isArray(pressures) || pressures.length !== pts.length) {
      out.pressures = pts.map(() => 0.5);
    }
  }

  // embeddable：契约用 `url` 表达嵌入地址，Excalidraw 的 embeddable 元素认 `link`，
  // 这里做一次字段名对齐（缺 link 时回退 url）。
  if (out.type === 'embeddable' && !out.link && out.url) {
    out.link = out.url;
  }

  // A1d（2026-09-26 实测发现）：`containerId` 只能指向**能装文字的容器**。
  //
  // 契约 `text.containerId` 的原话是"把本文字挂进某**图形**内当内文字
  // （与 shape.label 二选一）"—— 也就是 rectangle / ellipse / diamond；
  // 另外 Excalidraw 自身支持"箭头文字"（containerId 指向 arrow / line）。
  // 指向 **frame** 是**非法**用法：画框的标题另有来源（`frame.name`），
  // 框 ↔ 内容的唯一数据通道是 `frame.children`（反填 `frameId`）。
  //
  // 后果不是报错，而是**静默不可见** —— 2026-09-26 实测（f30_scale）：
  // 12 个 `containerId` 指向 frame 的子文字，元素在场景里、x/y/w/h 全部正常、
  // `isDeleted=false`，但 3x 截图里一个像素都没画出来（对照实验：仅把这 12 个
  // 的 `containerId` 摘掉、其余一字不改，文字立刻出现在框内正确位置）。
  // 用户看到的还是"AI 生成了但画布是空的/内容缺了一块"。
  //
  // 处理：摘掉非法 containerId，让文字降级成独立文字元素（坐标本来就是对的）。
  // 这治的是"渲染不丢内容"；真正的修法在后端 —— 框内文字应由 `frame.children`
  // 表达归属，而不是给文字填 frame 的 id 当 containerId。
  if (out.type === 'text' && typeof out.containerId === 'string') {
    const container = byId.get(out.containerId);
    if (!container || !TEXT_CONTAINER_TYPES.has(String(container.type))) {
      delete out.containerId;
    }
  }

  return out;
};

/**
 * 元素的有效尺寸（宽/高）。
 *
 * 为什么不能默认 0：契约 `scene.schema.json` 的 `text` 定义**没有**
 * `width` / `height` 字段（裸文字的尺寸由渲染器按字体测出），而绑定式
 * 箭头的几何推导发生在渲染器测量之前 —— 把缺尺寸当 0 会让 `centerOf`
 * 退化成"文字左上角"，16 条线全从字头上穿过（2026-10-13 t2 实测）。
 *
 * 裸文字按字数估：
 *   - CJK（含全角）≈ 0.9 × fontSize / 字，Latin ≈ 0.45 × fontSize / 字
 *     （与提示词契约段的宽度真值 Latin≈9px、CJK≈18px @fontSize 20 同源）；
 *   - 行高 ≈ 1.25 × fontSize（Excalidraw 默认 lineHeight）。
 * 非文字元素缺尺寸时维持旧口径 0（shape 的 w/h 在契约里必填，实际到不了）。
 */
const effectiveSize = (
  el: Record<string, unknown>,
): { width: number; height: number } => {
  const w = Number(el.width);
  const h = Number(el.height);
  // 注意零尺寸也算缺失：ensureFiniteSize 会先给缺宽高的文字盖 0/0，
  // 箭头治愈等下游拿到的"有限值"可能是假的 0 —— 文字不可能宽高全 0
  if (Number.isFinite(w) && Number.isFinite(h) && (w > 0 || h > 0)) {
    return { width: w, height: h };
  }
  if (String(el.type) !== 'text') {
    return { width: 0, height: 0 };
  }
  const text = String(el.text ?? '');
  if (!text) {
    return { width: 0, height: 0 };
  }
  const fontSize = Number(el.fontSize);
  const fs = Number.isFinite(fontSize) && fontSize > 0 ? fontSize : 20;
  const lines = text.split('\n');
  let maxLineWidth = 0;
  for (const line of lines) {
    let lineWidth = 0;
    for (const ch of line) {
      lineWidth += ch.charCodeAt(0) > 0x2e80 ? fs * 0.9 : fs * 0.45;
    }
    maxLineWidth = Math.max(maxLineWidth, lineWidth);
  }
  return { width: maxLineWidth, height: lines.length * fs * 1.25 };
};

/** 元素中心点（绑定式箭头需要一个真实起点，否则箭头会落在原点） */
const centerOf = (el: Record<string, unknown>): { x: number; y: number } => {
  const x = Number(el.x);
  const y = Number(el.y);
  const size = effectiveSize(el);
  return {
    x: (Number.isFinite(x) ? x : 0) + size.width / 2,
    y: (Number.isFinite(y) ? y : 0) + size.height / 2,
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
  const size = effectiveSize(el);
  const center = centerOf(el);
  const halfWidth = size.width / 2;
  const halfHeight = size.height / 2;
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
 * 方案 4（2026-10-14）：锚定包围盒 —— 箭头"声明要指向的那块东西"占的范围。
 *
 * 与 {@link effectiveSize} 的分工：effectiveSize 回答"这个元素本身多大"，
 * anchorBBox 回答"端点该落在哪块范围上"。关键差异在线类元素：
 * line / freedraw / arrow 在契约里**没有 width/height**（几何由 points 定义），
 * effectiveSize 对它们返回 0×0 —— 直接当锚会让锚点退化成元素 x/y 原点
 * （= points 包围盒左上角，t2「箭头扎字角上」的同族变体，2026-10-14 S1 实测）。
 * 这里对带 points 的线类元素现算包围盒，口径与 {@link ensureFiniteSize} 一致
 * （"points 自带宽高定义，重算永远比沿用可靠"）。
 */
const LINEAR_ANCHOR_TYPES = new Set<string>(['line', 'freedraw', 'arrow']);

/** 单个元素的锚定包围盒（不含 group 聚合）。`depth` 用于箭头→箭头链条的递归防抖 */
const singleAnchorBBox = (
  el: Record<string, unknown>,
  byId: Map<string, Record<string, unknown>> | undefined,
  depth: number,
): { x1: number; y1: number; x2: number; y2: number } => {
  const nx = Number(el.x);
  const ny = Number(el.y);
  const x = Number.isFinite(nx) ? nx : 0;
  const y = Number.isFinite(ny) ? ny : 0;
  const rawPoints = el.points as unknown;
  if (
    LINEAR_ANCHOR_TYPES.has(String(el.type)) &&
    Array.isArray(rawPoints) &&
    rawPoints.length >= 2
  ) {
    const xs: number[] = [];
    const ys: number[] = [];
    for (const p of rawPoints as unknown[][]) {
      const px = Number(p?.[0]);
      const py = Number(p?.[1]);
      if (Number.isFinite(px)) xs.push(px);
      if (Number.isFinite(py)) ys.push(py);
    }
    if (xs.length >= 2 || ys.length >= 2) {
      return {
        x1: x + Math.min(...xs),
        y1: y + Math.min(...ys),
        x2: x + Math.max(...xs),
        y2: y + Math.max(...ys),
      };
    }
  }
  // 箭头目标且自带几何不存在（byId 里是原始契约元素，points 还没派生）：
  // 按它自己的绑定递归推导两端几何，取两端点的包围盒。
  // （2026-10-14 S6 实测：箭头→箭头不递归会锚到场景原点 (0,0)。）
  if (
    String(el.type) === 'arrow' &&
    byId &&
    depth <= ANCHOR_RECURSION_LIMIT &&
    (!Array.isArray(rawPoints) || (rawPoints as unknown[]).length < 2)
  ) {
    const geo = boundArrowGeometry(el, byId, depth);
    if (geo) {
      const xs = [geo.from.x, geo.to.x];
      const ys = [geo.from.y, geo.to.y];
      return {
        x1: Math.min(...xs),
        y1: Math.min(...ys),
        x2: Math.max(...xs),
        y2: Math.max(...ys),
      };
    }
  }
  const size = effectiveSize(el);
  return { x1: x, y1: y, x2: x + size.width, y2: y + size.height };
};

/**
 * 箭头→箭头→箭头… 链式推导的深度上限：到层就放弃递归、退回 x/y 原点兜底。
 * 防御模型的循环引用（a→b→a）把转换拖成死循环。
 */
const ANCHOR_RECURSION_LIMIT = 3;

/**
 * 按绑定关系推导一条箭头的两端几何（与 withArrowGeometry 缺几何路径同一套数学）。
 * 目标元素不存在 / 两端都没声明时返回 null，调用方自行兜底。
 */
function boundArrowGeometry(
  skeleton: Record<string, unknown>,
  byId: Map<string, Record<string, unknown>>,
  depth: number,
): { from: { x: number; y: number }; to: { x: number; y: number } } | null {
  const startId = (skeleton.start as { id?: unknown } | undefined)?.id;
  const endId = (skeleton.end as { id?: unknown } | undefined)?.id;
  if (typeof startId !== 'string' && typeof endId !== 'string') {
    return null;
  }
  const startEl = typeof startId === 'string' ? byId.get(startId) : undefined;
  const endEl = typeof endId === 'string' ? byId.get(endId) : undefined;
  const startBB = startEl ? anchorBBox(startEl, byId, depth + 1) : null;
  const endBB = endEl ? anchorBBox(endEl, byId, depth + 1) : null;
  const startCenter = startBB ? bboxCenter(startBB) : { x: 0, y: 0 };
  const endCenter = endBB
    ? bboxCenter(endBB)
    : { x: startCenter.x + 120, y: startCenter.y };
  const from = startBB ? edgePointTowardsBBox(startBB, endCenter) : startCenter;
  const to = endBB ? edgePointTowardsBBox(endBB, startCenter) : endCenter;
  return { from, to };
}

/**
 * 锚定包围盒，带 group 聚合：意图目标是组合图形的某个成员时，
 * 锚到**整组**的并集包围盒，而不是单个成员 —— 否则箭头只指到"部件"。
 * （校验器口径：group 只许贴在矩形/椭圆/菱形上；这里宽进严出，不重复限类型。）
 * `depth` 只服务箭头→箭头的递归推导，公开调用方恒为 0。
 */
const anchorBBox = (
  el: Record<string, unknown>,
  byId?: Map<string, Record<string, unknown>>,
  depth = 0,
): { x1: number; y1: number; x2: number; y2: number } => {
  const base = singleAnchorBBox(el, byId, depth);
  const group = el.group as unknown;
  if (typeof group !== 'string' || !group || !byId) {
    return base;
  }
  let { x1, y1, x2, y2 } = base;
  for (const other of byId.values()) {
    if (other === el || (other as Record<string, unknown>).group !== group) {
      continue;
    }
    const ob = singleAnchorBBox(other, byId, depth + 1);
    x1 = Math.min(x1, ob.x1);
    y1 = Math.min(y1, ob.y1);
    x2 = Math.max(x2, ob.x2);
    y2 = Math.max(y2, ob.y2);
  }
  return { x1, y1, x2, y2 };
};

const bboxCenter = (bb: { x1: number; y1: number; x2: number; y2: number }) => ({
  x: (bb.x1 + bb.x2) / 2,
  y: (bb.y1 + bb.y2) / 2,
});

/** 包围盒版本的边缘交点（与 {@link edgePointTowards} 同一套数学，作用对象换成锚定包围盒） */
const edgePointTowardsBBox = (
  bb: { x1: number; y1: number; x2: number; y2: number },
  target: { x: number; y: number },
): { x: number; y: number } => {
  const center = bboxCenter(bb);
  const halfWidth = (bb.x2 - bb.x1) / 2;
  const halfHeight = (bb.y2 - bb.y1) / 2;
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
    return center;
  }
  return { x: center.x + dx * scale, y: center.y + dy * scale };
};

/**
 * 把一个悬空端点沿"作者画的出发方向"吸附到锚定包围盒边缘。
 *
 * `neighbor` 是该端点的相邻折点（首点取 pts[1]，末点取 pts[len-2]）：
 * 吸附方向 = 包围盒中心 → 相邻折点，即箭头自己画出的走向，不另造方向。
 *
 * 两种情况**不动**（作者意图优先）：
 *   - 端点已落在包围盒内/上（箭头已经"够到"目标，吸附反而画蛇添足）；
 *   - 相邻折点在包围盒内（朝内吸会反向拖拽，退回原点最稳）。
 *
 * @returns 吸附后的坐标；不需要吸附时返回 null（调用方保持原点）。
 */
const snapPointToAnchorBBox = (
  point: { x: number; y: number },
  bb: { x1: number; y1: number; x2: number; y2: number },
  neighbor: { x: number; y: number },
): { x: number; y: number } | null => {
  const inside =
    point.x >= bb.x1 && point.x <= bb.x2 && point.y >= bb.y1 && point.y <= bb.y2;
  if (inside) {
    return null;
  }
  const neighborInside =
    neighbor.x >= bb.x1 &&
    neighbor.x <= bb.x2 &&
    neighbor.y >= bb.y1 &&
    neighbor.y <= bb.y2;
  if (neighborInside) {
    return null;
  }
  const edge = edgePointTowardsBBox(bb, neighbor);
  const center = bboxCenter(bb);
  // 零尺寸/同心退化（scale>=1 会返回中心）：中心可能比原点更歪，宁可不吸
  if (edge.x === center.x && edge.y === center.y) {
    return null;
  }
  return edge;
};

/**
 * 方案 4 主入口：给"自带几何"的绑定式箭头把首/末点吸附到**声明的端点目标**。
 *
 * 背景（2026-10-14 t2/S4/S5 实测）：模型给箭头写了 x/y + points 时，几何完全
 * 按模型坐标渲染 —— 端点距所绑元素边缘悬空 16~140px，箭头"指向空气"。
 * 治愈逻辑豁免 ≥3 点折线（保护刻意绕行），校验器 v10.0.0 起放开"绑定+points"
 * 并存 ⇒ 这类箭头此前落在修正无人区。
 *
 * 规则（作者意图优先，最小干预）：
 *   - **只动首/末点**，中段绕行路径一个点都不碰（回流/折线语义保留）；
 *   - 只吸附**声明了 start/end 的那一端**：绑得上就锚到绑定元素，绑不上
 *     （line/freedraw/arrow/image 等）也锚到该元素 —— "不可绑 ≠ 丢失意图"，
 *     Excalidraw 层面保持自由端（不崩），几何忠实于模型声明的指向；
 *   - 端点已在目标包围盒内/上 ⇒ 不动；
 *   - 不可绑的声明端记入 `customData.chartflowIntentTarget` 留痕，供治愈/审计追溯。
 */
const snapArrowEndsToAnchors = (
  skeleton: Record<string, unknown>,
  startEl: Record<string, unknown> | undefined,
  endEl: Record<string, unknown> | undefined,
  startDeclared: boolean,
  endDeclared: boolean,
  byId: Map<string, Record<string, unknown>>,
): Record<string, unknown> => {
  const rawPoints = skeleton.points as unknown;
  if (!Array.isArray(rawPoints) || rawPoints.length < 2) {
    return skeleton;
  }
  const sx = Number(skeleton.x);
  const sy = Number(skeleton.y);
  const ox = Number.isFinite(sx) ? sx : 0;
  const oy = Number.isFinite(sy) ? sy : 0;
  const abs = (rawPoints as number[][]).map((p) => ({
    x: ox + (Number(p?.[0]) || 0),
    y: oy + (Number(p?.[1]) || 0),
  }));

  let changed = false;
  const intent: Record<string, string> = {};

  if (startDeclared && startEl) {
    const snapped = snapPointToAnchorBBox(
      abs[0],
      anchorBBox(startEl, byId),
      abs[1],
    );
    if (snapped) {
      abs[0] = snapped;
      changed = true;
    }
    if (!isBindableTarget(startEl) && startEl.id != null) {
      intent.start = String(startEl.id);
    }
  }
  const last = abs.length - 1;
  if (endDeclared && endEl) {
    const snapped = snapPointToAnchorBBox(
      abs[last],
      anchorBBox(endEl, byId),
      abs[last - 1],
    );
    if (snapped) {
      abs[last] = snapped;
      changed = true;
    }
    if (!isBindableTarget(endEl) && endEl.id != null) {
      intent.end = String(endEl.id);
    }
  }

  if (!changed && Object.keys(intent).length === 0) {
    return skeleton;
  }

  const out: Record<string, unknown> = {
    ...skeleton,
    x: abs[0].x,
    y: abs[0].y,
    points: abs.map((p) => [p.x - abs[0].x, p.y - abs[0].y]),
  };
  if (Object.keys(intent).length > 0) {
    out.customData = {
      ...((skeleton.customData as Record<string, unknown> | undefined) ?? {}),
      chartflowIntentTarget: intent,
    };
  }
  return out;
};

/**
 * Excalidraw 允许作为箭头绑定端点的类型。
 *
 * 取值不是拍脑袋，而是照着转换器里那段 `bindLinearElementToElement` 的
 * switch 逐个抄的（0.18.x，`src/data/transform.ts`）：
 * <ul>
 *   <li>`rectangle` / `ellipse` / `diamond` —— switch 里显式列出的三个
 *       `case`；</li>
 *   <li>`text` —— 它不在 switch 里，但有独立的
 *       `if (startType === 'text')` 分支，同样能绑。</li>
 * </ul>
 * 其余一切类型（`frame` / `embeddable` / `image` / `freedraw` / `line` /
 * `arrow` / `magicframe`）统统落进 `default: assertNever(..., true)` 分支，
 * **不构造任何端点元素** —— 于是 `bindLinearElement(linearElement, undefined,
 * 'start', ...)` 下一行就是 `hoveredElement.id`：
 * `TypeError: Cannot read properties of undefined (reading 'id')`，整批失败。
 *
 * 对照契约 `scene.schema.json`：`shape` 的 `type` 枚举正好就是
 * rectangle / ellipse / diamond；`text` 是带 `text` 字段的独立定义。
 */
const BINDABLE_TYPES = new Set<string>([
  'rectangle',
  'ellipse',
  'diamond',
  'text',
]);

/** 一个元素能不能当箭头的绑定端点（不存在 ⇒ 不能） */
const isBindableTarget = (
  el: Record<string, unknown> | undefined,
): boolean => !!el && BINDABLE_TYPES.has(String(el.type));

/**
 * 能"装文字"的容器类型（= 契约 `text.containerId` 的合法目标）。
 *
 * 三个图形来自契约原文（"挂进某图形内当内文字，与 shape.label 二选一"）；
 * `arrow` / `line` 是 Excalidraw 的"箭头文字"（线段中点上的标签）。
 * **frame / magicframe 不在其中**：画框标题是 `frame.name`，框内归属走
 * `frame.children`；给 frame 填 containerId 的文字在 Excalidraw 里
 * 一个像素都画不出来（见 normalize 里的 A1d 说明）。
 */
const TEXT_CONTAINER_TYPES = new Set<string>([
  'rectangle',
  'ellipse',
  'diamond',
  'arrow',
  'line',
]);

/**
 * 绑定式箭头的几何推导。
 *
 * LLM 输出形如 `{id:"a1", type:"arrow", start:{id:"n1"}, end:{id:"n2"}}` ——
 * **没有任何坐标信息**，而 Excalidraw 的 skeleton 要求 arrow 必须有 `x` / `y` / `points`。
 * 这里用被绑定元素的几何把箭头补成合法的绝对坐标：
 *   - 起点 = 起点元素中心，`x` / `y` 即该绝对坐标；
 *   - `points` = `[[0,0], [终点中心 - 起点中心]]`（相对坐标，Excalidraw 约定）。
 *
 * 只补"缺什么"：契约若已给了 x/y 与 points，几何主体**原样透传**
 * （绕行路径是作者的事实），但首/末点会按声明的端点目标做**吸附**
 * （见 {@link snapArrowEndsToAnchors}，2026-10-14 方案 4）——
 * 模型把端点写飘 16~140px 时不再"指到空气"。
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

  // A1b（2026-09-21）+ A1c（2026-09-26）：绑不上就摘掉，别把整批拖下水。
  //
  // A1b 只挡住了「绑定目标**不存在**」这一种；A1c 补上第二种 ——
  // 目标**存在但类型不可绑**（embeddable 嵌网页、frame 画框都是实测踩到的）。
  // 这类绑定在转换器眼里是"合法引用"，不会被引擎自己解绑，于是走到
  // `bindLinearElement(..., undefined, ...)` 直接抛 TypeError。
  //
  // 摘掉后该端变自由端：只要另一头还站着，几何照旧按真实元素算（下面的
  // startEl / endEl 逻辑），箭头照样画到该去的地方；两头都不可绑时，
  // 按两个真实元素的中心连一条自由箭头，而不是丢个残桩在原点。
  const clean: Record<string, unknown> = { ...skeleton };
  if (startId && !isBindableTarget(startEl)) delete clean.start;
  if (endId && !isBindableTarget(endEl)) delete clean.end;

  const hasXY = Number.isFinite(Number(skeleton.x)) && Number.isFinite(Number(skeleton.y));
  const hasPoints = Array.isArray(skeleton.points) && skeleton.points.length >= 2;
  if (hasXY && hasPoints) {
    // 方案 4（2026-10-14）：自带几何不再"整段透传"——中段绕行保留，
    // 首/末点吸附到声明的端点目标（绑得上锚绑定元素，绑不上也锚该元素）。
    return snapArrowEndsToAnchors(clean, startEl, endEl, !!startId, !!endId, byId);
  }

  // 取「锚定包围盒的边缘交点」而非中心点：中心点会让箭头穿过图形、
  // 与框内文字叠在一起。用包围盒（线类元素按 points 现算，见 anchorBBox）
  // 才能让 line/freedraw 这类"契约无宽高"的目标也锚得准。
  const startBB = startEl ? anchorBBox(startEl, byId) : null;
  const endBB = endEl ? anchorBBox(endEl, byId) : null;
  const startCenter = startBB ? bboxCenter(startBB) : { x: 0, y: 0 };
  const endCenter = endBB
    ? bboxCenter(endBB)
    : { x: startCenter.x + 120, y: startCenter.y };
  const from = startBB ? edgePointTowardsBBox(startBB, endCenter) : startCenter;
  const to = endBB ? edgePointTowardsBBox(endBB, startCenter) : endCenter;

  return {
    ...clean,
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
/**
 * Excalidraw 元素基类字段的兜底默认值（每次调用新建，避免数组被多个元素共享）。
 *
 * 背景（2026-09-23 实测）：`convertToExcalidrawElements` 对 **freedraw / embeddable**
 * 只做**部分初始化** —— 转换产物缺 `angle` / `groupIds` / `frameId` / `seed` /
 * `version`（version 实测是 NaN）等基类字段；其余类型（rectangle / image ...）是完整元素。
 * 后果有两层：① `getCommonBounds` 的旋转数学读 `angle` 得 undefined → NaN → 视口损坏；
 * ② `api.updateScene` 会**静默丢弃**这些不完整元素（实测 convLen=1 → sceneLen=0，画布空白）。
 */
const baseElementDefaults = (): Record<string, unknown> => ({
  angle: 0,
  strokeColor: '#1e1e1e',
  backgroundColor: 'transparent',
  fillStyle: 'solid',
  strokeWidth: 2,
  strokeStyle: 'solid',
  roughness: 1,
  opacity: 100,
  groupIds: [],
  frameId: null,
  roundness: null,
  seed: 1,
  version: 1,
  versionNonce: 0,
  isDeleted: false,
  boundElements: null,
  link: null,
  locked: false,
});

/** 给元素补齐基类字段（只补缺的 / 非有限的，不覆盖已有值），让白板新类型真正能渲染 */
const completeBaseElements = (els: ConvertedElement[]): ConvertedElement[] =>
  els.map((el) => {
    const out = { ...el } as Record<string, unknown>;
    const defaults = baseElementDefaults();
    for (const key of Object.keys(defaults)) {
      const value = out[key];
      if (value === undefined || (typeof value === 'number' && !Number.isFinite(value))) {
        out[key] = defaults[key];
      }
    }
    return out as ConvertedElement;
  });

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

    const x = Number(el.x);
    const y = Number(el.y);
    // angle 兜底：freedraw / embeddable 经转换器后**没有** angle 字段（其余类型有），
    // 而 getCommonBounds / getElementAbsoluteCoords 的旋转数学读 angle 得
    // undefined → NaN → 视口损坏。缺则补 0（2026-09-23 实测：补后 bounds 恢复正常）。
    const rawAngle = Number((el as unknown as { angle?: unknown }).angle);
    const angle = Number.isFinite(rawAngle) ? rawAngle : 0;

    if (linear) {
      // 线类 / 手绘：尺寸必须由 points 包围盒决定（转换器不保证给 width/height，
      // freedraw 转换后 width/height 是 undefined；line 给的是默认 100 而非真实跨度）。
      // 用 points 自带的宽高重算，永远比沿用转换器默认值可靠。
      return {
        ...el,
        x: Number.isFinite(x) ? x : 0,
        y: Number.isFinite(y) ? y : 0,
        width: spanX,
        height: spanY,
        angle,
      } as ConvertedElement;
    }

    const w = Number(el.width);
    const h = Number(el.height);
    if (
      Number.isFinite(w) &&
      Number.isFinite(h) &&
      Number.isFinite(x) &&
      Number.isFinite(y) &&
      Number.isFinite(rawAngle)
    ) {
      return el;
    }

    return {
      ...el,
      x: Number.isFinite(x) ? x : 0,
      y: Number.isFinite(y) ? y : 0,
      width: Number.isFinite(w) ? w : 0,
      height: Number.isFinite(h) ? h : 0,
      angle,
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
  // 取**末点**作为终点（2 点直连时末点 === pts[1]，语义一致且对多点折线正确）。
  const p1 = Array.isArray(pts) && pts.length >= 2 ? pts[pts.length - 1] : [0, 0];
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
    // 2026-10-10：显式折线（≥3 点）是**作者刻意的绕行路由**（如回流箭头从框顶
    // 绕回上游，故意偏离「两端中心连线」），其几何本身就是事实，绝不能被边界
    // 不变量误判为「污染」拉直。只有 2 点直连（几何由两端图形决定）才参与自愈。
    // 实测：回流箭头 4 点折线 vs 期望边缘交点的偏差可达 ~577px > 容差，旧逻辑会
    // 把它压成穿过框身的直线，与目标图不符。
    const rawPts = (el as unknown as { points?: readonly (readonly number[])[] })
      .points;
    if (Array.isArray(rawPts) && rawPts.length >= 3) return el;
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
    // 标签文字可能没有 w/h（契约 text 定义就没有）——用有效尺寸估，
    // 否则标签左上角会钉在中点上、看起来偏右下半个身位
    const size = effectiveSize(el as Record<string, unknown>);
    return {
      ...el,
      x: midX - size.width / 2,
      y: midY - size.height / 2,
    } as ConvertedElement;
  });

  return withLabels;
};

/**
 * 补丁三：**frame 的几何以后端为准**（2026-09-18 修的真缺陷）。
 *
 * Excalidraw 的转换器把 frame 的 x / y / width / height 当「可选输入」：
 * 缺哪一项就用它自己从成员算出来的值补（`data/transform.ts`：
 * `const frameY = frame?.y || minY;`，其中 `minY` 是成员包围盒上沿再减
 * 一个 `PADDING = 10`）。坑就在这个 `||` —— **0 是 falsy**：
 * 后端明明发了 y = 0（最上面一排模块，完全合法的布局结果），转换器却当成
 * 「没给」，改用 `成员 minY(80) - 10 = 70` → 框从 0 掉到 70：既不在 20px
 * 网格上，也和它本该框住的节点错位。2026-09-18 真实 LLM 电池实测到的
 * 「3 个 frame 只有 2 个落在网格上」就是这一条（复放后端产物确认 y = 0）。
 *
 * 后端的 frame 几何是**算出来的**（模块包围盒 + 内边距，见 ScenePlanner），
 * 不是猜的 —— 所以转换之后按契约原值恢复，把后端当唯一真相源。
 * 只认「契约里给了有限数字」的那几项，其余仍用转换器算的值。
 */
const restoreFrameGeometry = (
  els: ConvertedElement[],
  byId: Map<string, Record<string, unknown>>,
): ConvertedElement[] =>
  els.map((el) => {
    if (el.type !== 'frame') return el;
    const src = byId.get(el.id);
    if (!src) return el;
    const pick = (key: string, fallback: number): number => {
      const value = Number(src[key]);
      return Number.isFinite(value) ? value : fallback;
    };
    return {
      ...el,
      x: pick('x', el.x),
      y: pick('y', el.y),
      width: pick('width', el.width),
      height: pick('height', el.height),
    } as ConvertedElement;
  });

/**
 * 补丁四：跨 frame 的绑定箭头解除 frame 归属（2026-09-18 修的真缺陷）。
 *
 * 症状：连接「架构区 → 流程区 → 统计区 → 告警区」这类跨区箭头，渲染后
 * 整段消失（用户原话"一部分不见了"）。
 *
 * 根因：Excalidraw 转换器会按"绑定元素属于哪个 frame"给箭头反填 `frameId`，
 * 把箭头收编成该 frame 的子元素；而 frame 会裁剪子元素——箭头落在所属
 * frame 边界之外的那段被整体吃掉。实测 3 条跨区连线被裁 64%~88%。
 *
 * 修复：凡是两端绑定元素不属于同一个 frame 的箭头，清掉 `frameId` 变回自由
 * 元素（不再被裁剪）；框内箭头（两端同框）保持原 frameId，仍随 frame 移动。
 */
const detachCrossFrameArrows = (els: ConvertedElement[]): ConvertedElement[] => {
  const frameOf = new Map<string, string | null>();
  for (const e of els) {
    frameOf.set(e.id, e.frameId ?? null);
  }
  return els.map((e) => {
    if (e.type !== 'arrow') return e;
    const sb = e.startBinding?.elementId;
    const eb = e.endBinding?.elementId;
    const sf = typeof sb === 'string' ? (frameOf.get(sb) ?? null) : null;
    const ef = typeof eb === 'string' ? (frameOf.get(eb) ?? null) : null;
    return sf !== ef ? ({ ...e, frameId: null } as ConvertedElement) : e;
  });
};

/**
 * 补丁五：补全 `text.containerId` 的**双向绑定**（2026-10-10 修的真缺陷）。
 *
 * 症状（三图复刻验收 v2 实测）：agent_arch 37 个元素全部通过 convertScene
 * （0 dropped / 0 fatal / 0 NaN），截图里 17 个容器内文字**一个都没画**，
 * 而同样文字改用 shape.label 时（v1）正常显示；容器 boundElements 全为 null。
 *
 * 根因是一条「三方都不认账」的静默链：
 *   1. 契约说 text.containerId = 「挂进某图形当内文字」，模型照填；
 *   2. normalize 只校验 containerId 指向的容器**合法**，合法就放行 ——
 *      但渲染层找 bound text 只认**容器的 boundElements**
 *      （getBoundTextElement 读 boundElements，从不反查 containerId）；
 *   3. Excalidraw 转换器第二遍只处理 `label.text`（bindTextToContainer
 *      会回填 boundElements 并调 redrawTextBoundingBox 居中），**没有
 *      「独立 text 骨架自带 containerId」的分支**。
 * 结果：文字带着合法 containerId 躺在场景里（x/y/w/h 全对、isDeleted=false），
 * 渲染时既不算独立文字（isTextElement && containerId 会被过滤跳过），
 * 也算不成 bound 文字 —— 一个像素都画不出来，无报错、无 dropped。
 *
 * 修法（照抄 bindTextToContainer 对 label 做的两步，改在转换产物上补）：
 *   1. 把 {type:'text', id} 回填进容器 boundElements（幂等，已有则跳过）；
 *   2. 重新居中：形状用包围盒中心，arrow/line 用折线长度中点（贴线不落空），
 *      等价 redrawTextBoundingBox 的 CENTER/MIDDLE 效果，并补齐两个对齐缺省；
 *   3. 容器不在本批（三级降级逐元素转换时）→ 摘掉 containerId 降级成独立
 *      文字（坐标本来就在容器位置），宁可位置略偏也不许静默消失（与 A1d 同纪律）。
 */

/** bindContainerTexts 内部用的可变视图（ConvertedElement 是只读联合，直接改会打架） */
type BoundTextMut = {
  id: string;
  type: string;
  containerId?: unknown;
  x: number;
  y: number;
  width?: number;
  height?: number;
  textAlign?: string;
  verticalAlign?: string;
  boundElements?: Array<{ type: string; id: string }> | null;
  points?: readonly (readonly number[])[];
};

/** 折线长度中点（绝对坐标）。总长为 0 / points 缺失时返回 null 走兜底 */
const linearMidpoint = (
  el: Pick<BoundTextMut, 'x' | 'y' | 'points'>,
): { x: number; y: number } | null => {
  const pts = Array.isArray(el.points) ? el.points : [];
  if (pts.length < 2) return null;
  let total = 0;
  for (let i = 1; i < pts.length; i++) {
    total += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
  }
  if (!(total > 0)) return { x: el.x + pts[0][0], y: el.y + pts[0][1] };
  let remain = total / 2;
  for (let i = 1; i < pts.length; i++) {
    const len = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
    if (len > 0 && remain <= len) {
      const t = remain / len;
      return {
        x: el.x + pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * t,
        y: el.y + pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * t,
      };
    }
    remain -= len;
  }
  return null;
};

const bindContainerTexts = (els: ConvertedElement[]): ConvertedElement[] => {
  const byId = new Map<string, BoundTextMut>();
  for (const e of els) {
    byId.set(e.id, e as unknown as BoundTextMut);
  }
  for (const e of els) {
    const text = e as unknown as BoundTextMut;
    if (text.type !== 'text' || typeof text.containerId !== 'string') continue;
    const container = byId.get(text.containerId);
    if (!container) {
      delete text.containerId;
      continue;
    }
    const bound = Array.isArray(container.boundElements) ? container.boundElements : [];
    if (!bound.some((b) => b.type === 'text' && b.id === text.id)) {
      container.boundElements = [...bound, { type: 'text', id: text.id }];
    }
    if (text.textAlign == null) text.textAlign = 'center';
    if (text.verticalAlign == null) text.verticalAlign = 'middle';
    // 契约 text 定义没有 width/height（ensureFiniteSize 也会盖 0/0）——
    // 按有效尺寸估，否则标签左上角钉在容器中心、视觉上偏右下半个身位
    const size = effectiveSize(text as Record<string, unknown>);
    const center =
      container.type === 'arrow' || container.type === 'line'
        ? linearMidpoint(container)
        : {
            x: Number(container.x) + Number(container.width) / 2,
            y: Number(container.y) + Number(container.height) / 2,
          };
    if (center) {
      text.x = center.x - size.width / 2;
      text.y = center.y - size.height / 2;
    }
  }
  return els;
};

/** 统一出口：打标记 + 尺寸兜底，保证任何路径产出的元素都能安全取景 */
const finishConversion = (
  els: ConvertedElement[],
  dropped: string[],
  byId: Map<string, Record<string, unknown>>,
  fatal?: string,
): SceneConversion => ({
  elements: tagModel(
    ensureFiniteSize(
      completeBaseElements(
        restoreFrameGeometry(detachCrossFrameArrows(bindContainerTexts(els)), byId),
      ),
    ),
  ),
  dropped,
  fatal,
});

/**
 * 把后端 IR 转成 Excalidraw 元素，**任何一个元素坏掉都不许拖垮整批**。
 *
 * 策略（fail loud, degrade gracefully）：见 {@link convertWithIsolation} ——
 * 三级降级，且第三级保证"只要还有一个元素转得出来就绝不返回空场景"。
 *
 * 之所以需要 2/3 步：LLM 输出是**无界**的，schema 只约束了我们想到的字段，
 * Excalidraw 转换器还有我们控制不了的隐含前提（children / text 就是两例）。
 * 过去这类异常被 catch 静默吞掉，表现为"聊天说已生成 N 个元素、画布空白"。
 */
/**
 * Excalidraw 转换器认识的元素类型（与 scene.schema.json 的 type 枚举全集一致）。
 *
 * A1b（2026-09-21）修复：LLM 越界输出契约外的类型（典型如「时序图」被它用
 * `lifeline` / `actor` 等契约没有的原语拼出来）时，Excalidraw 的
 * `convertToExcalidrawElements` 在解析**指向该元素的箭头绑定**时会去读那个
 * 未知类型元素的属性，抛 `TypeError: Cannot read properties of undefined
 * (reading 'id')`，整批转换失败 → 画布全空 + fatal。转换前先按这个白名单
 * 隔离未知类型，坏类型进入 dropped 让用户看到被跳过（不再静默崩）。
 */
const KNOWN_EXCALIDRAW_TYPES = new Set<SceneElementType>([
  'rectangle',
  'ellipse',
  'diamond',
  'arrow',
  'line',
  'text',
  'frame',
  'freedraw',
  'image',
  'embeddable',
  'magicframe',
]);

/** 一条箭头 skeleton 是否"带着绑不上的绑定"（= 毒元素头号嫌疑） */
const isSuspiciousBinding = (skeleton: Record<string, unknown>): boolean =>
  skeleton.type === 'arrow' && (!!skeleton.start || !!skeleton.end);

/**
 * 三级降级兜底（A1c，2026-09-26）。详见 {@link convertScene} 上方的说明。
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
    // freedraw（手绘路径）现在是白板的一等能力：用户说「画几笔」「圈一下」时
    // LLM 可以输出 freedraw。它在 Excalidraw 里的取景边界由 points 推算，
    // 本文件的 ensureFiniteSize 已按 points 包围盒兜底 width/height/x/y，
    // 不会出现 2026-09-16 那种 [NaN,NaN,NaN,NaN] 毁视口的问题。
    // （用户手绘不经过本函数、只回写坐标，这里只管 AI 生成的 freedraw。）
    // A1b（2026-09-21）：契约外的未知类型（如时序图原语 lifeline）会让
    // Excalidraw 转换器在解析箭头绑定时崩溃。转换前先隔离，而非等到批量
    // 转换抛错连累整批元素都画不出来。
    if (!KNOWN_EXCALIDRAW_TYPES.has(el.type)) {
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

  return convertWithIsolation(skeletons, byId, dropped);
};

/**
 * A1c（2026-09-26）：**把"整批失败"变成"最多丢一条"**。
 *
 * 背景：2026-09-26 的 32 组实测里，f21/f30 拿回的仍是空画布，尽管
 * `convertScene` 本来写了"整批失败就逐元素重试"。实测把这条路径钉死了 ——
 *
 * <pre>
 *   整批 runConverter           → THROW bindLinearElement (reading 'id')
 *   逐元素 runConverter([x])    → 全过（毒箭头单独转时绑定会被 A1b 摘掉，转得出来）
 *   幸存者整批 runConverter     → 又 THROW（幸存者＝全集，等于第一次）
 *   ⇒ 兜底逻辑一个毒元素都没抓到，dropped 为空、elements 为空
 * </pre>
 *
 * 也就是说「逐元素试转」这一步**根本测不出毒元素**（毒元素的毒只在同批里
 * 才发作）。所以这里换成三级降级，且保证第三级**永不返回空数组**：
 * <ol>
 *   <li>整批转换 —— 绑定式箭头需要同批元素在场才能解析；</li>
 *   <li>抛了就按"最像毒药"的优先序剔除嫌疑元素后重试（每次剔除都记进
 *       {@code dropped}，不静默）；</li>
 *   <li>仍不行就逐元素转换后拼接。**哪怕只救回一个元素也绝不放空**。
 *       逐元素转换保不住箭头绑定（绑定目标不在单元素批次里），但它是最后
 *       一道防线，宁可画一条自由箭头，也不能让画布全白。</li>
 * </ol>
 */
const convertWithIsolation = (
  skeletons: Record<string, unknown>[],
  byId: Map<string, Record<string, unknown>>,
  dropped: string[],
): SceneConversion => {
  const attempt = (
    list: Record<string, unknown>[],
  ): { ok: boolean; result: SceneConversion } => {
    try {
      return {
        ok: true,
        result: finishConversion(runConverter(list), dropped, byId),
      };
    } catch (e) {
      return { ok: false, result: { elements: [], dropped, fatal: errText(e) } };
    }
  };

  const whole = attempt(skeletons);
  if (whole.ok && whole.result.elements.length > 0) {
    return whole.result;
  }

  // 第二级：逐个剔除嫌疑元素后重试。循环体每次都 splice 掉一个，长度单调
  // 递减，必然终止；没有可剔除的嫌疑时跳出，交给第三级逐元素兜底。
  const remaining = [...skeletons];
  while (remaining.length > 0) {
    const victimIndex = remaining.findIndex(isSuspiciousBinding);
    if (victimIndex < 0) break;
    const [victim] = remaining.splice(victimIndex, 1);
    if (victim.id !== undefined) {
      dropped.push(String(victim.id));
    }
    const retry = attempt(remaining);
    if (retry.ok && retry.result.elements.length > 0) {
      return retry.result;
    }
  }

  // 第三级：逐元素转换 + 拼接。这是"绝不返回空数组"的最后保证。
  const salvaged: ConvertedElement[] = [];
  for (const skeleton of remaining) {
    try {
      salvaged.push(...runConverter([skeleton]));
    } catch {
      if (skeleton.id !== undefined) {
        dropped.push(String(skeleton.id));
      }
    }
  }

  if (salvaged.length === 0) {
    return {
      elements: [],
      dropped,
      fatal: '所有元素都无法转换为画布元素',
    };
  }

  try {
    return finishConversion(salvaged, dropped, byId);
  } catch (e) {
    // 收尾阶段（几何恢复 / 字段补齐）理论上不会抛；真抛了也别把已救回的
    // 元素丢掉 —— 宁可少几个基类字段，也不能让画布变空。
    return {
      elements: tagModel(completeBaseElements(salvaged)),
      dropped,
      fatal: errText(e),
    };
  }
};
