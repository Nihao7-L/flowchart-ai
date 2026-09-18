/** 后端 SSE 事件类型 */
export interface ChatEvent {
  type: 'thinking' | 'validation' | 'result' | 'error';
  data: string;
}

/** 聊天消息（前端展示用） */
export interface ChatMessage {
  role: 'user' | 'agent' | 'tool' | 'error';
  text: string;
  timestamp: number;
}

/** 元素标签：后端契约为对象而非字符串（见 scene.schema.json definitions.label） */
export interface SceneLabel {
  text: string;
}

/** 端点绑定（arrow 的 start/end）：指向某元素的 id */
export interface SceneBinding {
  id: string;
}

/** 相对坐标点 [dx, dy]（arrow/line/freedraw 用） */
export type ScenePoint = [number, number];

/**
 * 箭头端点形状（与 Excalidraw 的 Arrowhead 联合类型逐字对齐，12 种）
 *
 * <p>契约侧由后端按「关系语义」统一编译填入（v2-40 P2），不直接暴露给 LLM。
 * {@code crowfoot_*} 是数据库 ER 图的基数标记；2026-09-18 实测确认
 * 端点形状与端点绑定可共存，不必为了形状牺牲"拖动跟随"。
 */
export type SceneArrowhead =
  | 'arrow'
  | 'bar'
  | 'dot'
  | 'circle'
  | 'circle_outline'
  | 'triangle'
  | 'triangle_outline'
  | 'diamond'
  | 'diamond_outline'
  | 'crowfoot_one'
  | 'crowfoot_many'
  | 'crowfoot_one_or_many';

/** IR 元素类型枚举（与 scene.schema.json element.oneOf 的分支一致） */
export type SceneElementType =
  | 'rectangle'
  | 'ellipse'
  | 'diamond'
  | 'arrow'
  | 'line'
  | 'text'
  | 'frame'
  | 'freedraw';

/**
 * 后端场景 IR 的元素。
 *
 * 字段取自 `src/main/resources/schemas/scene.schema.json` 各分支
 * （shape / arrow / line / text / frame / freedraw）属性的**并集**——
 * 前端只做字段搬运，不做二次校验（结构合法性由后端 GraphValidator + schema 把关）。
 *
 * ⚠️ 新增后端字段时务必同步这里：Canvas 的骨架映射按白名单搬运，
 * 漏一个字段就可能导致整批元素转换失败（画布全空，且异常会被 catch 吞掉）。
 */
export interface SceneElement {
  id?: string;
  type: SceneElementType;
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  angle?: number;
  /** 形状内文字（rectangle/ellipse/diamond/arrow 可带） */
  label?: SceneLabel;
  strokeColor?: string;
  backgroundColor?: string;
  fillStyle?: 'hachure' | 'cross-hatch' | 'solid' | 'zigzag';
  strokeWidth?: number;
  strokeStyle?: 'solid' | 'dashed' | 'dotted';
  roughness?: number;
  /** 圆角（渲染层字段：由后端编译层按语义填入，Excalidraw 原生形态） */
  roundness?: { type: 1 | 2 | 3 };
  /** 超链接：点击该元素时跳转的 URL */
  link?: string;
  /** 0-100 */
  opacity?: number;
  /** arrow / line / freedraw 的相对坐标点 */
  points?: ScenePoint[];
  /** arrow 起点绑定 */
  start?: SceneBinding;
  /** arrow 终点绑定 */
  end?: SceneBinding;
  /** arrow 起点形状（渲染层字段：由后端按关系语义编译填入） */
  startArrowhead?: SceneArrowhead;
  /** arrow 终点形状；{@link SceneArrowhead} 的 crowfoot_* 为 ER 图基数标记 */
  endArrowhead?: SceneArrowhead;
  /** text 元素的正文（必填，缺失会让 Excalidraw 转换器整批抛错） */
  text?: string;
  fontSize?: number;
  /** 1=手绘 2=Helvetica 3=等宽 */
  fontFamily?: 1 | 2 | 3;
  textAlign?: 'left' | 'center' | 'right';
  /** 文字垂直对齐（渲染层字段：由后端填入） */
  verticalAlign?: 'top' | 'middle' | 'bottom';
  /** 文字尺寸随内容自适应（渲染层字段：由后端填入） */
  autoResize?: boolean;
  /** frame 的标题 */
  name?: string;
}

/**
 * 上报给后端的「用户手绘元素」轻量描述（v2-34）
 *
 * <p>只保留"能让 LLM 明白这里已经有东西了"的字段：类型、位置、
 * 端点（points / 绑定 id）、文字。样式一律不带 —— 后端拿它做
 * 避重提示与判重，不会用它渲染。
 */
export interface UserElementBrief {
  id?: string;
  type?: string;
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  points?: readonly (readonly number[])[];
  /** 该连线绑定到的图形 id（来自 Excalidraw 的 startBinding.elementId） */
  startId?: string;
  endId?: string;
  text?: string;
}

/**
 * 「被解绑的连线」的完整几何（v2-34）
 *
 * <p>拖动一条线时 Excalidraw 会解除它的端点绑定。要让后端模型如实
 * 反映这件事，光说"它不绑定了"不够 —— 还得把它此刻的几何一起送过去：
 * 原先它是靠两端图形算位置的，解绑之后必须有自己的 x / y / points，
 * 否则后端模型里会留下一条既没有绑定、又没有路径的坏箭头
 * （渲染时算不出长度，会污染整张图的取景边界）。
 */
export interface UnboundArrowBrief {
  id: string;
  x: number;
  y: number;
  points: readonly (readonly number[])[];
  width?: number;
  height?: number;
}

/**
 * 画布现状同步载荷（v2-34）
 *
 * <p>五类信息必须一起送。只送 positions 时，用户的删除、手绘、
 * 解绑对后端都是隐形的 —— 于是被删元素下一轮复活、手绘的线被重复画
 * 一条、拖走的线又被绑回原位（2026-09-16 用户实测）。
 */
export interface CanvasSyncPayload {
  positions: Record<string, { x: number; y: number }>;
  /** 后端模型里有、画布上已不存在的元素 id（= 用户删掉的，含连坐删除的连线） */
  removedIds: string[];
  userElements: UserElementBrief[];
  /** 模型里写着绑定、画布上已经解绑的连线（= 用户拖过它） */
  unboundArrows: UnboundArrowBrief[];
}

/** 后端场景 IR（精简版，完整契约见 scene.schema.json） */
export interface SceneIR {
  elements: SceneElement[];
}
