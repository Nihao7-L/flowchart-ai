import { createContext, useContext, useEffect, useMemo, useRef, useState, useCallback } from 'react'
import ReactFlow, {
  Background, MiniMap, NodeResizer,
  Handle, Position, addEdge, MarkerType,
  useEdgesState, useNodesState, useReactFlow,
  type Edge, type Node, type NodeProps, type Connection,
  type ReactFlowInstance, type OnSelectionChangeParams,
  type NodeTypes
} from 'reactflow'
import '@reactflow/node-resizer/dist/style.css'
import dagre from 'dagre'
import type { ChartType, GraphJson, GraphNodeType } from '../api/types'

/* ============================== 常量 ============================== */
const NODE_W = 150
const NODE_H = 46
const SEP_X = 34
const SEP_Y = 70
const EDGE_COLOR = '#94a3b8'
const DUP_OFFSET = 28

type ShapeKey =
  | GraphNodeType
  | 'mindmap-root' | 'mindmap-node'
  | 'swimlane' | 'lane'

interface ShapeDef {
  key: ShapeKey
  label: string
  category: 'basic' | 'flowchart' | 'architecture' | 'mindmap' | 'swimlane'
  icon: string
}

const SHAPES: ShapeDef[] = [
  // 基础图形
  { key: 'rectangle', label: '矩形', category: 'basic', icon: '▭' },
  { key: 'rounded', label: '圆角矩形', category: 'basic', icon: '▢' },
  { key: 'ellipse', label: '椭圆', category: 'basic', icon: '⬭' },
  { key: 'circle', label: '圆形', category: 'basic', icon: '○' },
  { key: 'diamond', label: '菱形', category: 'basic', icon: '◇' },
  { key: 'parallelogram', label: '平行四边形', category: 'basic', icon: '▱' },
  { key: 'cloud', label: '云', category: 'basic', icon: '☁' },
  // 流程图
  { key: 'start', label: '开始/结束', category: 'flowchart', icon: '⬭' },
  { key: 'process', label: '处理', category: 'flowchart', icon: '▭' },
  { key: 'decision', label: '判断', category: 'flowchart', icon: '◇' },
  { key: 'input', label: '输入/输出', category: 'flowchart', icon: '▱' },
  { key: 'document', label: '文档', category: 'flowchart', icon: '▭' },
  { key: 'database', label: '数据库', category: 'flowchart', icon: '⬭' },
  // 架构
  { key: 'component', label: '组件', category: 'architecture', icon: '▭' },
  // 泳道
  { key: 'swimlane', label: '泳池(横)', category: 'swimlane', icon: '▬' },
  { key: 'lane', label: '泳道(竖)', category: 'swimlane', icon: '▯' },
  // 思维导图
  { key: 'mindmap-root', label: '中心主题', category: 'mindmap', icon: '●' },
  { key: 'mindmap-node', label: '分支', category: 'mindmap', icon: '●' }
]

const CATEGORIES: { key: ShapeDef['category']; label: string }[] = [
  { key: 'basic', label: '基础图形' },
  { key: 'flowchart', label: 'Flowchart 流程图' },
  { key: 'architecture', label: '系统架构' },
  { key: 'swimlane', label: '泳池泳道' },
  { key: 'mindmap', label: '思维导图' }
]

const PRESETS: Record<string, { fill: string; stroke: string; color: string }> = {
  start: { fill: '#dcfce7', stroke: '#86efac', color: '#166534' },
  end: { fill: '#fee2e2', stroke: '#fca5a5', color: '#991b1b' },
  process: { fill: '#e0f2fe', stroke: '#7dd3fc', color: '#075985' },
  decision: { fill: '#fef3c7', stroke: '#fcd34d', color: '#92400e' },
  input: { fill: '#f3e8ff', stroke: '#d8b4fe', color: '#6b21a8' },
  output: { fill: '#f3e8ff', stroke: '#d8b4fe', color: '#6b21a8' },
  document: { fill: '#ffedd5', stroke: '#fdba74', color: '#9a3412' },
  database: { fill: '#dbeafe', stroke: '#93c5fd', color: '#1e40af' },
  component: { fill: '#ede9fe', stroke: '#c4b5fd', color: '#5b21b6' },
  rectangle: { fill: '#ffffff', stroke: '#94a3b8', color: '#1f2937' },
  rounded: { fill: '#ffffff', stroke: '#94a3b8', color: '#1f2937' },
  ellipse: { fill: '#ffffff', stroke: '#94a3b8', color: '#1f2937' },
  circle: { fill: '#ffffff', stroke: '#94a3b8', color: '#1f2937' },
  diamond: { fill: '#fef3c7', stroke: '#fcd34d', color: '#92400e' },
  parallelogram: { fill: '#ffffff', stroke: '#94a3b8', color: '#1f2937' },
  cloud: { fill: '#f1f5f9', stroke: '#cbd5e1', color: '#475569' },
  swimlane: { fill: '#f8fafc', stroke: '#64748b', color: '#334155' },
  lane: { fill: '#f8fafc', stroke: '#64748b', color: '#334155' },
  'mindmap-root': { fill: '#14b8a6', stroke: '#0d9488', color: '#ffffff' },
  'mindmap-node': { fill: '#ffffff', stroke: '#94a3b8', color: '#1f2937' },
  mindmap: { fill: '#14b8a6', stroke: '#0d9488', color: '#ffffff' }
}

/* ============================== 数据模型 ============================== */
type FlowNodeData = {
  label: string
  shape: ShapeKey
  fill?: string
  stroke?: string
  color?: string
  rotate?: number
  opacity?: number
  shadow?: boolean
  borderStyle?: 'solid' | 'dashed'
  textStyle?: { bold?: boolean; italic?: boolean; fontSize?: number; align?: 'left' | 'center' | 'right' }
}
type FlowNode = Node<FlowNodeData>

type FlowEdgeData = {
  label?: string
  kind?: 'straight' | 'step' | 'smoothstep'
  lineType?: 'solid' | 'dashed' | 'dotted'
  arrow?: 'end' | 'start' | 'both' | 'none'
  stroke?: string
  strokeWidth?: number
}
type FlowEdge = Edge<FlowEdgeData>

function shapeToBackendType(shape: ShapeKey, chartType: ChartType): GraphNodeType {
  if (chartType === 'mindmap') return 'mindmap'
  if (shape === 'start' || shape === 'end' || shape === 'decision' || shape === 'component' || shape === 'mindmap') return shape
  if (shape === 'input' || shape === 'output' || shape === 'database' || shape === 'document') return shape
  if (shape === 'swimlane' || shape === 'lane') return 'component'
  return 'process'
}
function backendTypeToShape(type: GraphNodeType, chartType: ChartType, isRoot?: boolean): ShapeKey {
  if (chartType === 'mindmap') return isRoot ? 'mindmap-root' : 'mindmap-node'
  if (type === 'start' || type === 'end' || type === 'decision' || type === 'component') return type
  if (type === 'input' || type === 'output' || type === 'database' || type === 'document') return type
  return 'process'
}

function defaultLabelFor(shape: ShapeKey): string {
  const m: Partial<Record<ShapeKey, string>> = {
    start: '开始', end: '结束', process: '处理', decision: '判断?',
    input: '输入', output: '输出', document: '文档', database: '数据库',
    component: '组件', rectangle: '矩形', rounded: '圆角矩形', ellipse: '椭圆',
    circle: '圆形', diamond: '判断?', parallelogram: '并行', cloud: '云',
    swimlane: '泳池', lane: '泳道', 'mindmap-root': '中心主题', 'mindmap-node': '分支'
  }
  return m[shape] || '新节点'
}

/* dagre 自动布局 */
function layout(nodes: Node[], edges: Edge[], direction: 'TB' | 'LR'): Node[] {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: direction, nodesep: SEP_X, ranksep: SEP_Y, marginx: 20, marginy: 20, align: 'UL' })
  nodes.forEach(n => {
    const w = (n.width as number) || NODE_W
    const h = (n.height as number) || NODE_H
    g.setNode(n.id, { width: w, height: h })
  })
  edges.forEach(e => g.setEdge(e.source, e.target))
  dagre.layout(g)
  return nodes.map(n => {
    const pos = g.node(n.id)
    const w = (n.width as number) || NODE_W
    const h = (n.height as number) || NODE_H
    return { ...n, position: { x: pos.x - w / 2, y: pos.y - h / 2 } }
  })
}

/* 放置自动避让：新节点与已有节点（含已放置的新节点）重叠时向右下推开，避免挤成一团 */
function boxesOverlap(a: { position: { x: number; y: number }; width?: number | null; height?: number | null },
                     b: { position: { x: number; y: number }; width?: number | null; height?: number | null }, gap = 10) {
  const aw = a.width || NODE_W, ah = a.height || NODE_H
  const bw = b.width || NODE_W, bh = b.height || NODE_H
  const ax = a.position.x, ay = a.position.y
  const bx = b.position.x, by = b.position.y
  return ax < bx + bw + gap && bx < ax + aw + gap && ay < by + bh + gap && by < ay + ah + gap
}
function deOverlapAdded(existing: FlowNode[], added: FlowNode[]): FlowNode[] {
  const placed: FlowNode[] = existing.map(n => n)
  const result: FlowNode[] = []
  for (const node of added) {
    const nodeW = node.width || NODE_W
    const nodeH = node.height || NODE_H
    let p = { ...node.position }
    let guard = 0
    while (placed.some(pl => boxesOverlap({ position: p, width: nodeW, height: nodeH }, pl)) && guard < 60) {
      p = { x: p.x + nodeW + 16, y: p.y + 10 }
      guard++
    }
    const n = { ...node, position: p }
    result.push(n)
    placed.push(n)
  }
  return result
}

/* 连线样式：箭头方向 + 颜色 + 线型。用内建边类型（自带重连锚点，可拖拽改指向） */
function edgeMarker(color: string) {
  return { type: MarkerType.ArrowClosed, color, width: 16, height: 16 } as const
}
function withEdgeVisuals(e: FlowEdge): FlowEdge {
  const d = e.data || {}
  const color = d.stroke || EDGE_COLOR
  const arrow = d.arrow || 'end'
  const lineType = d.lineType || 'solid'
  const kind = d.kind || 'smoothstep'
  const dash = lineType === 'dashed' ? '7 4' : lineType === 'dotted' ? '2 4' : undefined
  return {
    ...e,
    type: kind, // 'straight' | 'step' | 'smoothstep'（内建，支持重连手柄）
    reconnectable: true,
    label: d.label,
    labelBgStyle: { fill: 'var(--card-bg)', fillOpacity: 1 },
    labelStyle: { fontSize: 12 },
    markerEnd: arrow === 'end' || arrow === 'both' ? edgeMarker(color) : undefined,
    markerStart: arrow === 'start' || arrow === 'both' ? edgeMarker(color) : undefined,
    style: { stroke: color, strokeWidth: d.strokeWidth || 1.5, strokeDasharray: dash }
  }
}

/* ============================== 编辑器上下文 ============================== */
interface EditorCtx {
  editingNodeId: string | null
  setEditingNodeId: (id: string | null) => void
  commitLabel: (id: string, label: string) => void
}
const EditContext = createContext<EditorCtx>({
  editingNodeId: null, setEditingNodeId: () => {}, commitLabel: () => {}
})

/* ============================== 自定义节点 ============================== */
function RotationHandle({ cx, cy, initial, onRotate, onEnd }: {
  cx: number; cy: number; initial: number; onRotate: (deg: number) => void; onEnd: () => void
}) {
  const { screenToFlowPosition } = useReactFlow()
  const start = (e: React.PointerEvent) => {
    e.stopPropagation(); e.preventDefault()
    const p0 = screenToFlowPosition({ x: e.clientX, y: e.clientY })
    const a0 = Math.atan2(p0.y - cy, p0.x - cx) * 180 / Math.PI
    const move = (ev: PointerEvent) => {
      const p = screenToFlowPosition({ x: ev.clientX, y: ev.clientY })
      const a = Math.atan2(p.y - cy, p.x - cx) * 180 / Math.PI
      onRotate(Math.round(initial + (a - a0)))
    }
    const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); onEnd() }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }
  return <div className="rf-rotate-handle" onPointerDown={(e) => { window.dispatchEvent(new CustomEvent('rf-rotate-start')); start(e) }} title="拖动旋转" />
}

/** 用真实 SVG 几何绘制形状（替代仅 border-radius 的假形状）
 *  viewBox 0 0 100 100，preserveAspectRatio="none"，vector-effect 保持描边均匀。 */
function shapeGlyph(shape: ShapeKey, fill: string, stroke: string, borderStyle: 'solid' | 'dashed' | undefined) {
  const dash = borderStyle === 'dashed' || shape === 'decision' || shape === 'diamond' ? '6 5' : undefined
  const sw = 1.6
  const p = { fill, stroke, strokeWidth: sw, strokeDasharray: dash, vectorEffect: 'non-scaling-stroke' as const }
  switch (shape) {
    case 'rectangle': return <rect x={1} y={1} width={98} height={98} rx={2} {...p} />
    case 'rounded': return <rect x={1} y={1} width={98} height={98} rx={14} {...p} />
    case 'ellipse': case 'circle': return <ellipse cx={50} cy={50} rx={49} ry={49} {...p} />
    case 'start': case 'end': return <rect x={1} y={1} width={98} height={98} rx={49} {...p} />
    case 'diamond': case 'decision': return <polygon points="50,2 98,50 50,98 2,50" {...p} />
    case 'parallelogram': case 'input': case 'output': return <polygon points="24,2 98,2 76,98 2,98" {...p} />
    case 'cloud': return <path d="M28,74 C16,74 9,65 12,55 C5,50 8,39 18,38 C21,29 34,27 41,33 C47,26 61,26 65,34 C75,33 81,42 77,50 C86,54 84,70 74,72 C68,78 36,78 28,74 Z" {...p} />
    case 'document': return <path d="M24,10 L76,10 C88,10 89,20 89,26 L89,70 C89,82 80,89 70,89 L30,89 C20,89 11,82 11,70 L11,22 C11,14 17,10 24,10 Z M11,76 C28,70 34,86 50,86 C66,86 72,70 89,76" {...p} />
    case 'database': return <path d="M25,18 C25,9 75,9 75,18 L75,78 C75,87 25,87 25,78 Z M25,18 C25,27 75,27 75,18" {...p} />
    case 'mindmap-root': return <rect x={1} y={1} width={98} height={98} rx={28} {...p} />
    default: return <rect x={1} y={1} width={98} height={98} rx={8} {...p} />
  }
}

function CustomNode(props: NodeProps<FlowNodeData>) {
  const { id, data, selected, xPos, yPos } = props
  const rf = useReactFlow()
  const dim = rf.getNode(id)
  const w = (dim?.width as number) || NODE_W
  const h = (dim?.height as number) || NODE_H
  const label = data?.label || ''
  const shape = (data?.shape as ShapeKey) || 'process'
  const fill = data?.fill || PRESETS[shape]?.fill || '#fff'
  const stroke = data?.stroke || PRESETS[shape]?.stroke || '#94a3b8'
  const color = data?.color || PRESETS[shape]?.color || '#1f2937'
  const rotate = data?.rotate || 0
  const opacity = data?.opacity ?? 1
  const ts = data?.textStyle || {}
  const { editingNodeId, setEditingNodeId, commitLabel } = useContext(EditContext)
  const editing = editingNodeId === id
  const [val, setVal] = useState(label)
  useEffect(() => { if (editing) setVal(label) }, [editing, label])

  const isBig = shape === 'swimlane' || shape === 'lane'
  const minW = isBig ? 140 : 60
  const minH = isBig ? 70 : 36

  const textStyle: React.CSSProperties = {
    fontWeight: ts.bold ? 700 : 500,
    fontStyle: ts.italic ? 'italic' : 'normal',
    fontSize: ts.fontSize || 13,
    textAlign: ts.align || 'center',
    color
  }

  const nodeStyle: React.CSSProperties = {
    opacity,
    boxShadow: data?.shadow ? '0 6px 16px rgba(15,23,42,0.18)' : (selected ? '0 0 0 2px var(--primary)' : '0 2px 6px rgba(0,0,0,0.06)')
  }

  return (
    <div
      className={`rf-node rf-shape-${shape}` + (selected ? ' selected' : '')}
      style={{ ...nodeStyle, transform: `rotate(${rotate}deg)`, transformOrigin: 'center' }}
      onDoubleClick={() => setEditingNodeId(id)}
      title={label}
    >
      <svg className="rf-shape-svg" viewBox="0 0 100 100" preserveAspectRatio="none">
        {shapeGlyph(shape, fill, stroke, data?.borderStyle)}
      </svg>
      <Handle type="target" position={Position.Top} className="rf-handle" />
      <Handle type="target" position={Position.Left} className="rf-handle" />
      {editing ? (
        <input
          className="rf-node-input"
          autoFocus
          value={val}
          onChange={e => setVal(e.target.value)}
          onPointerDown={e => e.stopPropagation()}
          onDoubleClick={e => e.stopPropagation()}
          onBlur={() => { commitLabel(id, val.trim() || label); setEditingNodeId(null) }}
          onKeyDown={e => {
            if (e.key === 'Enter') { commitLabel(id, val.trim() || label); setEditingNodeId(null) }
            else if (e.key === 'Escape') setEditingNodeId(null)
          }}
        />
      ) : (
        <span className="rf-node-label" style={textStyle}>{label}</span>
      )}
      <Handle type="source" position={Position.Bottom} className="rf-handle" />
      <Handle type="source" position={Position.Right} className="rf-handle" />

      {selected && (
        <NodeResizer
          color="#14b8a6"
          isVisible={true}
          minWidth={minW}
          minHeight={minH}
          handleStyle={{ width: 8, height: 8 }}
          onResizeStart={() => window.dispatchEvent(new CustomEvent('rf-resize-start'))}
          onResizeEnd={() => window.dispatchEvent(new CustomEvent('rf-resize-end'))}
        />
      )}
      {selected && (
        <RotationHandle
          cx={xPos + w / 2}
          cy={yPos + h / 2}
          initial={rotate}
          onRotate={deg => {
            window.dispatchEvent(new CustomEvent('rf-rotate', { detail: { id, deg } }))
          }}
          onEnd={() => window.dispatchEvent(new CustomEvent('rf-rotate-end'))}
        />
      )}
    </div>
  )
}

const nodeTypeEntries: [string, typeof CustomNode][] = [
  ...SHAPES.map(s => [s.key, CustomNode] as [string, typeof CustomNode]),
  ['mindmap', CustomNode]
]
const nodeTypes = Object.fromEntries(nodeTypeEntries) as unknown as NodeTypes

/* ============================== 我的组件（localStorage） ============================== */
interface MyComponent { name: string; nodes: FlowNode[]; edges: FlowEdge[] }
function loadMyComponents(): MyComponent[] {
  try { return JSON.parse(localStorage.getItem('myComponents') || '[]') } catch { return [] }
}
function saveMyComponents(list: MyComponent[]) {
  localStorage.setItem('myComponents', JSON.stringify(list))
}

/* ============================== 主组件 ============================== */
export default function ChartFlow({
  graphJson, chartType, onChange
}: {
  graphJson: GraphJson
  chartType: ChartType
  onChange?: (g: GraphJson) => void
}) {
  const direction = chartType === 'architecture' ? 'LR' : 'TB'
  const wrapperRef = useRef<HTMLDivElement>(null)
  const [rfInstance, setRfInstance] = useState<ReactFlowInstance | null>(null)
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [editingNodeId, setEditingNodeId] = useState<string | null>(null)
  const [zoom, setZoom] = useState(1)
  const [myComponents, setMyComponents] = useState<MyComponent[]>(loadMyComponents)
  // 顶部 Ribbon 菜单栏：当前激活的 Tab（开始 / 插入 / 格式 / 视图）
  const [activeTab, setActiveTab] = useState<'home' | 'insert' | 'format' | 'view'>('home')

  const initialNodes = useMemo<FlowNode[]>(() => {
    const rootId = graphJson.nodes[0]?.id
    return graphJson.nodes.map((n) => {
      const isRoot = n.id === rootId
      const shape = backendTypeToShape(n.type, chartType, isRoot)
      const preset = PRESETS[shape] || PRESETS.process
      return {
        id: n.id,
        type: shape,
        data: {
          label: n.label, shape,
          fill: n.style?.fill || preset.fill,
          stroke: n.style?.stroke || preset.stroke,
          color: n.style?.color || preset.color
        },
        position: n.position || { x: 0, y: 0 },
        width: NODE_W, height: NODE_H
      }
    })
  }, [graphJson, chartType])

  const initialEdges = useMemo<FlowEdge[]>(() => {
    return graphJson.edges.map((e, i) => withEdgeVisuals({
      id: e.id || `e-${e.source}-${e.target}-${i}`,
      source: e.source, target: e.target,
      data: { label: e.label || undefined }
    }))
  }, [graphJson])

  const layoutedNodes = useMemo(
    () => layout(initialNodes, initialEdges, direction),
    [initialNodes, initialEdges, direction]
  )

  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNodeData>(layoutedNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState<FlowEdgeData>(initialEdges)

  useEffect(() => { setNodes(layoutedNodes); setEdges(initialEdges) }, [layoutedNodes, initialEdges, setNodes, setEdges])

  const nodesRef = useRef(nodes); nodesRef.current = nodes
  const edgesRef = useRef(edges); edgesRef.current = edges

  /* ---- 历史（undo/redo）---- */
  const past = useRef<string[]>([])
  const future = useRef<string[]>([])
  const snapshot = useCallback(() => {
    past.current.push(JSON.stringify({ nodes: nodesRef.current, edges: edgesRef.current }))
    if (past.current.length > 100) past.current.shift()
    future.current = []
  }, [])
  const undo = useCallback(() => {
    if (!past.current.length) return
    future.current.push(JSON.stringify({ nodes: nodesRef.current, edges: edgesRef.current }))
    const prev = JSON.parse(past.current.pop()!) as { nodes: FlowNode[]; edges: FlowEdge[] }
    setNodes(prev.nodes); setEdges(prev.edges.map(withEdgeVisuals))
    setTimeout(reportChange, 0)
  }, [setNodes, setEdges])
  const redo = useCallback(() => {
    if (!future.current.length) return
    past.current.push(JSON.stringify({ nodes: nodesRef.current, edges: edgesRef.current }))
    const nxt = JSON.parse(future.current.pop()!) as { nodes: FlowNode[]; edges: FlowEdge[] }
    setNodes(nxt.nodes); setEdges(nxt.edges.map(withEdgeVisuals))
    setTimeout(reportChange, 0)
  }, [setNodes, setEdges])

  /* ---- 序列化 & 上报 ---- */
  const serialize = useCallback((ns: FlowNode[], es: FlowEdge[]): GraphJson => ({
    title: graphJson.title,
    nodes: ns.map(n => {
      const shape = (n.data?.shape as ShapeKey) || 'process'
      return {
        id: n.id,
        type: shapeToBackendType(shape, chartType),
        label: (n.data?.label as string) || '',
        style: { fill: n.data?.fill, stroke: n.data?.stroke, color: n.data?.color }
      }
    }),
    edges: es.map(e => ({ id: e.id, source: e.source, target: e.target, label: (typeof e.data?.label === 'string' && e.data.label) ? e.data.label : null }))
  }), [graphJson.title, chartType])

  const reportChange = useCallback(() => {
    onChange?.(serialize(nodesRef.current, edgesRef.current))
  }, [onChange, serialize])

  /* ---- 节点文字 / 连线标签 提交 ---- */
  const commitLabel = useCallback((id: string, label: string) => {
    snapshot()
    setNodes(ns => ns.map(n => n.id === id ? { ...n, data: { ...n.data, label } } : n))
    setTimeout(reportChange, 0)
  }, [setNodes, reportChange, snapshot])

  const editCtx = useMemo<EditorCtx>(() => ({ editingNodeId, setEditingNodeId, commitLabel }), [editingNodeId, commitLabel])

  /* ---- 旋转（经自定义事件回写 store，避免在 Node 内直接 setState）---- */
  useEffect(() => {
    const onRot = (e: Event) => {
      const { id, deg } = (e as CustomEvent).detail
      setNodes(ns => ns.map(n => n.id === id ? { ...n, data: { ...n.data, rotate: deg } } : n))
    }
    const onRotEnd = () => setTimeout(reportChange, 0)
    const onResizeStart = () => snapshot()
    const onResizeEnd = () => setTimeout(reportChange, 0)
    const onRotateStart = () => snapshot()
    window.addEventListener('rf-rotate', onRot as EventListener)
    window.addEventListener('rf-rotate-end', onRotEnd)
    window.addEventListener('rf-resize-start', onResizeStart as EventListener)
    window.addEventListener('rf-resize-end', onResizeEnd)
    window.addEventListener('rf-rotate-start', onRotateStart)
    return () => {
      window.removeEventListener('rf-rotate', onRot as EventListener)
      window.removeEventListener('rf-rotate-end', onRotEnd)
    window.removeEventListener('rf-resize-start', onResizeStart as EventListener)
    window.removeEventListener('rf-resize-end', onResizeEnd)
    window.removeEventListener('rf-rotate-start', onRotateStart)
    }
  }, [setNodes, reportChange])

  /* ---- 连线 ---- */
  const onConnect = useCallback((c: Connection) => {
    if (!c.source || !c.target) return
    snapshot()
    setEdges(eds => addEdge(withEdgeVisuals({
      ...c, id: `e-${c.source}-${c.target}-${Date.now()}`,
      source: c.source!, target: c.target!,
      data: { label: undefined }
    }), eds))
    setTimeout(reportChange, 0)
  }, [setEdges, snapshot, reportChange])

  /* 边重连：拖动连线的一端重新接到另一节点（保留源标签和箭头设置） */
  const onReconnect = useCallback((oldEdge: Edge, newConn: Connection) => {
    if (!newConn.source || !newConn.target) return
    snapshot()
    setEdges(es => es.map(e => e.id === oldEdge.id ? withEdgeVisuals({
      ...e,
      source: newConn.source!, target: newConn.target!,
      sourceHandle: newConn.sourceHandle ?? undefined,
      targetHandle: newConn.targetHandle ?? undefined
    }) : e))
    setTimeout(reportChange, 0)
  }, [setEdges, snapshot, reportChange])

  /* ---- 拖拽添加图形 / 覆盖替换 ---- */
  const onDragOver = useCallback((e: React.DragEvent) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move' }, [])
  const onDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    if (!rfInstance) return
    const raw = e.dataTransfer.getData('application/reactflow')
    if (!raw) return
    const shape = raw as ShapeKey
    const preset = PRESETS[shape] || PRESETS.process
    const position = rfInstance.screenToFlowPosition({ x: e.clientX, y: e.clientY })
    // 覆盖替换：落在已有节点上则改其形状
    const el = document.elementFromPoint(e.clientX, e.clientY)?.closest('.react-flow__node') as HTMLElement | null
    const targetId = el?.getAttribute('data-id') || null
    snapshot()
    if (targetId) {
      const t = targetId
      setNodes(ns => ns.map(n => n.id === t ? {
        ...n, type: shape, selected: true,
        data: { ...n.data, shape, fill: preset.fill, stroke: preset.stroke, color: preset.color }
      } : { ...n, selected: false }))
      setSelectedIds([t])
    } else {
      const id = `n-${Date.now()}-${Math.random().toString(36).slice(2, 5)}`
      const newNode: FlowNode = {
        id, type: shape, position,
        data: { label: defaultLabelFor(shape), shape, fill: preset.fill, stroke: preset.stroke, color: preset.color },
        width: NODE_W, height: NODE_H
      }
      // 自动避让：落在已有节点上时向右下推开，避免重叠成一团
      const [placed] = deOverlapAdded(nodesRef.current, [newNode])
      setNodes(ns => [...ns.map(n => ({ ...n, selected: false })), placed])
      setSelectedIds([id])
    }
    setTimeout(reportChange, 0)
  }, [rfInstance, setNodes, snapshot, reportChange])

  /* ---- 删除 ---- */
  const deleteSelected = useCallback(() => {
    if (!selectedIds.length) return
    snapshot()
    setNodes(ns => ns.filter(n => !selectedIds.includes(n.id)).map(n => ({ ...n, selected: false })))
    setEdges(es => es.filter(e => !selectedIds.includes(e.id) && !selectedIds.includes(e.source) && !selectedIds.includes(e.target)).map(e => ({ ...e, selected: false })))
    setSelectedIds([])
    setTimeout(reportChange, 0)
  }, [selectedIds, setNodes, setEdges, snapshot, reportChange])

  /* ---- 复制 / 剪切 / 粘贴 / 快复制 ---- */
  const clipboard = useRef<{ nodes: FlowNode[]; edges: FlowEdge[] }>({ nodes: [], edges: [] })
  const cloneWithIds = (ns: FlowNode[], es: FlowEdge[], dx: number, dy: number) => {
    const idMap = new Map<string, string>()
    const clones = ns.map(n => {
      const nid = `n-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
      idMap.set(n.id, nid)
      return { ...n, id: nid, position: { x: n.position.x + dx, y: n.position.y + dy }, selected: true, data: { ...n.data } }
    })
    const cloneEdges = es
      .filter(e => idMap.has(e.source) && idMap.has(e.target))
      .map(e => ({
        ...e, id: `e-${idMap.get(e.source)}-${idMap.get(e.target)}-${Date.now()}`,
        source: idMap.get(e.source)!, target: idMap.get(e.target)!,
        data: { ...e.data }, selected: true
      }))
    return { clones, cloneEdges }
  }
  const copySelected = useCallback(() => {
    const ns = nodesRef.current.filter(n => selectedIds.includes(n.id))
    const es = edgesRef.current.filter(e => selectedIds.includes(e.id) || (selectedIds.includes(e.source) && selectedIds.includes(e.target)))
    clipboard.current = { nodes: ns.map(n => ({ ...n, data: { ...n.data } })), edges: es.map(e => ({ ...e, data: { ...e.data } })) }
  }, [selectedIds])
  const cutSelected = useCallback(() => {
    copySelected()
    deleteSelected()
  }, [copySelected, deleteSelected])
  const paste = useCallback(() => {
    const { nodes: cn, edges: ce } = clipboard.current
    if (!cn.length) return
    snapshot()
    const { clones, cloneEdges } = cloneWithIds(cn, ce, DUP_OFFSET, DUP_OFFSET)
    const placed = deOverlapAdded(nodesRef.current, clones)
    setNodes(ns => [...ns.map(n => ({ ...n, selected: false })), ...placed])
    setEdges(es => [...es.map(e => ({ ...e, selected: false })), ...cloneEdges.map(withEdgeVisuals)])
    setSelectedIds([...placed.map(n => n.id), ...cloneEdges.map(e => e.id)])
    setTimeout(reportChange, 0)
  }, [setNodes, setEdges, snapshot, reportChange])
  const duplicateSelected = useCallback((dx = DUP_OFFSET, dy = DUP_OFFSET) => {
    const sel = selectedIds.length ? selectedIds : nodesRef.current.filter(n => n.selected).map(n => n.id)
    if (!sel.length) return
    const ns = nodesRef.current.filter(n => sel.includes(n.id))
    const es = edgesRef.current.filter(e => sel.includes(e.source) && sel.includes(e.target))
    snapshot()
    const { clones, cloneEdges } = cloneWithIds(ns, es, dx, dy)
    const placed = deOverlapAdded(nodesRef.current, clones)
    setNodes(cur => [...cur.map(n => ({ ...n, selected: false })), ...placed])
    setEdges(cur => [...cur.map(e => ({ ...e, selected: false })), ...cloneEdges.map(withEdgeVisuals)])
    setSelectedIds([...placed.map(n => n.id), ...cloneEdges.map(e => e.id)])
    setTimeout(reportChange, 0)
  }, [selectedIds, setNodes, setEdges, snapshot, reportChange])

  /* ---- 我的组件 ---- */
  const saveAsComponent = useCallback(() => {
    const sel = selectedIds.length ? selectedIds : nodesRef.current.filter(n => n.selected).map(n => n.id)
    if (!sel.length) return
    const ns = nodesRef.current.filter(n => sel.includes(n.id))
    const es = edgesRef.current.filter(e => sel.includes(e.source) && sel.includes(e.target))
    if (!ns.length) return
    const xs = ns.map(n => n.position.x), ys = ns.map(n => n.position.y)
    const minX = Math.min(...xs), minY = Math.min(...ys)
    const shifted = ns.map(n => ({ ...n, position: { x: n.position.x - minX, y: n.position.y - minY }, selected: false, data: { ...n.data } }))
    const idMap = new Map(ns.map((n, i) => [n.id, shifted[i].id]))
    const shiftedEdges = es.map(e => ({ ...e, source: idMap.get(e.source)!, target: idMap.get(e.target)!, selected: false, data: { ...e.data } }))
    const name = (ns[0].data.label || '组件').slice(0, 8)
    const list = [...loadMyComponents(), { name, nodes: shifted, edges: shiftedEdges }]
    saveMyComponents(list); setMyComponents(list)
  }, [selectedIds])
  const deleteComponent = useCallback((idx: number) => {
    const list = loadMyComponents()
    list.splice(idx, 1)
    saveMyComponents(list)
    setMyComponents(list)
  }, [])
  const insertComponent = useCallback((c: MyComponent) => {
    if (!rfInstance) return
    snapshot()
    const center = rfInstance.screenToFlowPosition({
      x: wrapperRef.current!.getBoundingClientRect().left + wrapperRef.current!.clientWidth / 2,
      y: wrapperRef.current!.getBoundingClientRect().top + wrapperRef.current!.clientHeight / 2
    })
    const { clones, cloneEdges } = cloneWithIds(c.nodes, c.edges, 0, 0)
    const moved = clones.map(n => ({ ...n, position: { x: n.position.x + center.x, y: n.position.y + center.y } }))
    const placed = deOverlapAdded(nodesRef.current, moved)
    setNodes(cur => [...cur.map(n => ({ ...n, selected: false })), ...placed])
    setEdges(cur => [...cur.map(e => ({ ...e, selected: false })), ...cloneEdges.map(withEdgeVisuals)])
    setSelectedIds([...placed.map(n => n.id), ...cloneEdges.map(e => e.id)])
    setTimeout(reportChange, 0)
  }, [rfInstance, setNodes, setEdges, snapshot, reportChange])

  /* ---- 工具栏：布局 / 适应 / 缩放 ---- */
  const autoLayout = useCallback(() => {
    snapshot()
    setNodes(layout(nodesRef.current, edgesRef.current, direction))
    setTimeout(() => rfInstance?.fitView({ padding: 0.15 }), 0)
    setTimeout(reportChange, 0)
  }, [direction, rfInstance, setNodes, snapshot, reportChange])
  const fitView = useCallback(() => rfInstance?.fitView({ padding: 0.15 }), [rfInstance])
  const resetZoom = useCallback(() => rfInstance?.zoomTo(1, { duration: 200 }), [rfInstance])

  /* ---- 选中同步 ---- */
  const onSelectionChange = useCallback((p: OnSelectionChangeParams) => {
    setSelectedIds([...p.nodes.map(n => n.id), ...p.edges.map(e => e.id)])
  }, [])
  const selectedNode = nodes.find(n => selectedIds.length === 1 && n.id === selectedIds[0]) || null
  const selectedEdge = edges.find(e => selectedIds.length === 1 && e.id === selectedIds[0]) || null

  const updateNode = (patch: Partial<FlowNodeData>) => {
    if (!selectedNode) return
    snapshot()
    setNodes(ns => ns.map(n => n.id === selectedNode.id ? { ...n, data: { ...n.data, ...patch } } : n))
    setTimeout(reportChange, 0)
  }
  const changeShape = (shape: ShapeKey) => {
    if (!selectedNode) return
    snapshot()
    const preset = PRESETS[shape] || PRESETS.process
    setNodes(ns => ns.map(n => n.id === selectedNode.id ? {
      ...n, type: shape, data: { ...n.data, shape, fill: preset.fill, stroke: preset.stroke, color: preset.color }
    } : n))
    setTimeout(reportChange, 0)
  }
  const updateEdge = (patch: Partial<FlowEdgeData>) => {
    if (!selectedEdge) return
    snapshot()
    setEdges(es => es.map(e => e.id === selectedEdge.id ? withEdgeVisuals({ ...e, data: { ...e.data, ...patch } }) : e))
    setTimeout(reportChange, 0)
  }

  /* ---- 快捷键 ---- */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName
      const typing = tag === 'INPUT' || tag === 'TEXTAREA' || (e.target as HTMLElement)?.isContentEditable
      if (typing) return
      const ctrl = e.ctrlKey || e.metaKey
      if (ctrl && e.key.toLowerCase() === 'c') { e.preventDefault(); copySelected(); return }
      if (ctrl && e.key.toLowerCase() === 'x') { e.preventDefault(); cutSelected(); return }
      if (ctrl && e.key.toLowerCase() === 'v') { e.preventDefault(); paste(); return }
      if (ctrl && e.key.toLowerCase() === 'd') { e.preventDefault(); duplicateSelected(); return }
      if (ctrl && e.key.toLowerCase() === 'a') { e.preventDefault(); setNodes(ns => ns.map(n => ({ ...n, selected: true }))); setEdges(es => es.map(ed => ({ ...ed, selected: true }))); return }
      if (ctrl && e.key.toLowerCase() === 'z' && !e.shiftKey) { e.preventDefault(); undo(); return }
      if ((ctrl && e.key.toLowerCase() === 'y') || (ctrl && e.shiftKey && e.key.toLowerCase() === 'z')) { e.preventDefault(); redo(); return }
      if ((e.key === 'Delete' || e.key === 'Backspace')) { if (editingNodeId) return; e.preventDefault(); deleteSelected() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [copySelected, cutSelected, paste, duplicateSelected, undo, redo, deleteSelected, editingNodeId])

  const onNodeDragStart = useCallback((e: React.MouseEvent, _node: Node) => {
    if (e.ctrlKey || e.metaKey) duplicateSelected()
    else snapshot()
  }, [duplicateSelected, snapshot])
  const onNodeDragStop = useCallback(() => { setTimeout(reportChange, 0) }, [reportChange])

  return (
    <EditContext.Provider value={editCtx}>
      <div className="chart-flow">
        {/* ===== 顶部 Ribbon 菜单栏（Word 式：Tab + 功能区） ===== */}
        <div className="chart-flow-menubar">
          <div className="chart-flow-menu-tabs">
            <button className={'chart-flow-menu-tab' + (activeTab === 'home' ? ' active' : '')} onClick={() => setActiveTab('home')}>开始</button>
            <button className={'chart-flow-menu-tab' + (activeTab === 'insert' ? ' active' : '')} onClick={() => setActiveTab('insert')}>插入</button>
            <button className={'chart-flow-menu-tab' + (activeTab === 'format' ? ' active' : '')} onClick={() => setActiveTab('format')}>格式</button>
            <button className={'chart-flow-menu-tab' + (activeTab === 'view' ? ' active' : '')} onClick={() => setActiveTab('view')}>视图</button>
            <span className="chart-flow-menu-spacer" />
            {selectedNode ? (
              <span className="chart-flow-menu-sel">已选：{selectedNode.data?.label || '节点'}</span>
            ) : selectedEdge ? (
              <span className="chart-flow-menu-sel">已选：连线</span>
            ) : null}
          </div>

          <div className="chart-flow-ribbon">
            {/* 开始 */}
            {activeTab === 'home' && (
              <>
                <div className="rb-group">
                  <span className="rb-label">历史</span>
                  <button className="toolbar-btn" onClick={undo} title="撤销 (Ctrl+Z)">⟲ 撤销</button>
                  <button className="toolbar-btn" onClick={redo} title="重做 (Ctrl+Y)">⟳ 重做</button>
                </div>
                <div className="rb-group">
                  <span className="rb-label">剪贴板</span>
                  <button className="toolbar-btn" onClick={() => { if (selectedIds.length) copySelected() }} disabled={!selectedIds.length} title="复制 (Ctrl+C)">⧉ 复制</button>
                  <button className="toolbar-btn" onClick={() => { if (selectedIds.length) cutSelected() }} disabled={!selectedIds.length} title="剪切 (Ctrl+X)">✂ 剪切</button>
                  <button className="toolbar-btn" onClick={paste} title="粘贴 (Ctrl+V)">📋 粘贴</button>
                </div>
                <div className="rb-group">
                  <span className="rb-label">编辑</span>
                  <button className="toolbar-btn" onClick={() => duplicateSelected()} disabled={!selectedIds.length} title="快速复制 (Ctrl+D)">⧉ 快速复制</button>
                  <button className="toolbar-btn" onClick={() => { if (selectedIds.length) saveAsComponent() }} disabled={!selectedIds.length} title="保存选中为组件">⭐ 存为组件</button>
                  <button className="toolbar-btn danger" onClick={deleteSelected} disabled={!selectedIds.length} title="删除选中 (Delete)">🗑 删除</button>
                </div>
              </>
            )}

            {/* 插入（图形选择） */}
            {activeTab === 'insert' && (
              <>
                <div className="rb-group rb-shapes">
                  <span className="rb-label">图形</span>
                  <div className="rb-shape-scroll">
                    {CATEGORIES.map(cat => (
                      <div key={cat.key} className="rb-shape-cat">
                        <span className="rb-shape-cat-title">{cat.label}</span>
                        <div className="rb-shape-grid">
                          {SHAPES.filter(s => chartType === 'mindmap' ? s.category === 'mindmap' : s.category === cat.key).map(s => (
                            <div key={s.key} className="shape-item" draggable
                              onDragStart={e => e.dataTransfer.setData('application/reactflow', s.key)} title={s.label}>
                              <span className="shape-item-icon">{s.icon}</span>
                              <span className="shape-item-label">{s.label}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
                <div className="rb-group my-components-group">
                  <span className="rb-label">我的组件</span>
                  {myComponents.length === 0 ? (
                    <span className="rb-mut">选中图形后点「存为组件」</span>
                  ) : (
                    <>
                      <span className="my-components-count" title={`已保存 ${myComponents.length} 个组件`}>{myComponents.length}</span>
                      <div className="my-components-scroll" title="横向滚动查看更多组件">
                        {myComponents.map((c, i) => (
                          <div key={i} className="shape-item my-component-item" title={`插入：${c.name}`}
                            onClick={() => insertComponent(c)} style={{ cursor: 'pointer' }}>
                            <span className="shape-item-icon">⭐</span>
                            <span className="shape-item-label">{c.name}</span>
                            <button
                              className="my-component-del"
                              title="删除该组件"
                              onClick={(e) => { e.stopPropagation(); deleteComponent(i) }}
                            >×</button>
                          </div>
                        ))}
                      </div>
                    </>
                  )}
                </div>
              </>
            )}

            {/* 格式（属性） */}
            {activeTab === 'format' && (
              <>
                {!selectedNode && !selectedEdge && <div className="property-empty">选中节点或连线以编辑属性</div>}
                {selectedNode && (
                  <>
                    <div className="prop-menu">
                      <span className="prop-menu-label">节点</span>
                      <label className="prop-item"><span>文字</span>
                        <input type="text" value={selectedNode.data?.label || ''} onChange={e => updateNode({ label: e.target.value })} onBlur={reportChange} />
                      </label>
                      <label className="prop-item"><span>形状</span>
                        <select value={selectedNode.data?.shape as string}
                          onChange={e => changeShape(e.target.value as ShapeKey)}>
                          {SHAPES.filter(s => chartType === 'mindmap' ? s.category === 'mindmap' : s.category !== 'mindmap').map(s => (
                            <option key={s.key} value={s.key}>{s.label}</option>
                          ))}
                        </select>
                      </label>
                    </div>
                    <div className="prop-menu">
                      <span className="prop-menu-label">外观</span>
                      <label className="prop-item"><span>填充</span>
                        <input type="color" value={selectedNode.data?.fill || '#ffffff'} onChange={e => updateNode({ fill: e.target.value })} />
                      </label>
                      <label className="prop-item"><span>边框</span>
                        <input type="color" value={selectedNode.data?.stroke || '#94a3b8'} onChange={e => updateNode({ stroke: e.target.value })} />
                      </label>
                      <label className="prop-item"><span>线型</span>
                        <select value={selectedNode.data?.borderStyle || 'solid'} onChange={e => updateNode({ borderStyle: e.target.value as 'solid' | 'dashed' })}>
                          <option value="solid">实线</option>
                          <option value="dashed">虚线</option>
                        </select>
                      </label>
                      <label className="prop-item"><span>字色</span>
                        <input type="color" value={selectedNode.data?.color || '#1f2937'} onChange={e => updateNode({ color: e.target.value })} />
                      </label>
                      <label className="prop-item"><span>透明度 {Math.round((selectedNode.data?.opacity ?? 1) * 100)}%</span>
                        <input type="range" min={0.1} max={1} step={0.05} value={selectedNode.data?.opacity ?? 1}
                          onChange={e => updateNode({ opacity: Number(e.target.value) })} />
                      </label>
                      <label className="prop-item"><span>旋转 {Math.round(selectedNode.data?.rotate || 0)}°</span>
                        <input type="range" min={-180} max={180} step={1} value={selectedNode.data?.rotate || 0}
                          onChange={e => updateNode({ rotate: Number(e.target.value) })} />
                      </label>
                      <label className="prop-item"><span><input type="checkbox" checked={!!selectedNode.data?.shadow} onChange={e => updateNode({ shadow: e.target.checked })} /> 阴影</span></label>
                    </div>
                    <div className="prop-menu">
                      <span className="prop-menu-label">文字</span>
                      <button className={'style-btn' + (selectedNode.data?.textStyle?.bold ? ' active' : '')} onClick={() => updateNode({ textStyle: { ...selectedNode.data?.textStyle, bold: !selectedNode.data?.textStyle?.bold } })}><b>B</b></button>
                      <button className={'style-btn' + (selectedNode.data?.textStyle?.italic ? ' active' : '')} onClick={() => updateNode({ textStyle: { ...selectedNode.data?.textStyle, italic: !selectedNode.data?.textStyle?.italic } })}><i>I</i></button>
                      <select className="style-size" value={selectedNode.data?.textStyle?.fontSize || 13}
                        onChange={e => updateNode({ textStyle: { ...selectedNode.data?.textStyle, fontSize: Number(e.target.value) } })}>
                        {[11, 12, 13, 14, 16, 18, 20, 24].map(s => <option key={s} value={s}>{s}</option>)}
                      </select>
                      <select className="style-align" value={selectedNode.data?.textStyle?.align || 'center'}
                        onChange={e => updateNode({ textStyle: { ...selectedNode.data?.textStyle, align: e.target.value as any } })}>
                        <option value="left">左</option><option value="center">中</option><option value="right">右</option>
                      </select>
                    </div>
                  </>
                )}
                {selectedEdge && (
                  <div className="prop-menu">
                    <span className="prop-menu-label">连线</span>
                    <label className="prop-item"><span>标签</span>
                      <input type="text" value={selectedEdge.data?.label || ''} autoFocus
                        onChange={e => updateEdge({ label: e.target.value || undefined })} onBlur={reportChange}
                        placeholder="是 / 否" />
                    </label>
                    <label className="prop-item"><span>类型</span>
                      <select value={selectedEdge.data?.kind || 'smoothstep'}
                        onChange={e => updateEdge({ kind: e.target.value as FlowEdgeData['kind'] })}>
                        <option value="smoothstep">圆角</option>
                        <option value="straight">直线</option>
                        <option value="step">直角</option>
                      </select>
                    </label>
                    <label className="prop-item"><span>箭头</span>
                      <select value={selectedEdge.data?.arrow || 'end'}
                        onChange={e => updateEdge({ arrow: e.target.value as FlowEdgeData['arrow'] })}>
                        <option value="end">终点 →</option>
                        <option value="start">起点 ←</option>
                        <option value="both">双向 ↔</option>
                        <option value="none">无</option>
                      </select>
                    </label>
                    <label className="prop-item"><span>线型</span>
                      <select value={selectedEdge.data?.lineType || 'solid'}
                        onChange={e => updateEdge({ lineType: e.target.value as FlowEdgeData['lineType'] })}>
                        <option value="solid">实线</option>
                        <option value="dashed">虚线</option>
                        <option value="dotted">点线</option>
                      </select>
                    </label>
                    <label className="prop-item"><span>颜色</span>
                      <input type="color" value={selectedEdge.data?.stroke || EDGE_COLOR} onChange={e => updateEdge({ stroke: e.target.value })} />
                    </label>
                    <label className="prop-item"><span>线宽 {selectedEdge.data?.strokeWidth || 1.5}</span>
                      <input type="range" min={1} max={5} step={0.5} value={selectedEdge.data?.strokeWidth || 1.5}
                        onChange={e => updateEdge({ strokeWidth: Number(e.target.value) })} />
                    </label>
                  </div>
                )}
              </>
            )}

            {/* 视图 */}
            {activeTab === 'view' && (
              <>
                <div className="rb-group">
                  <span className="rb-label">布局</span>
                  <button className="toolbar-btn" onClick={autoLayout} title="自动排列">⟲ 自动排列</button>
                  <button className="toolbar-btn" onClick={fitView} title="适应画布">⊘ 适应画布</button>
                </div>
                <div className="rb-group">
                  <span className="rb-label">缩放</span>
                  <button className="toolbar-btn" onClick={() => rfInstance?.zoomOut({ duration: 150 })} title="缩小">－ 缩小</button>
                  <button className="toolbar-btn" onClick={resetZoom} title="重置为 100%">{Math.round(zoom * 100)}%</button>
                  <button className="toolbar-btn" onClick={() => rfInstance?.zoomIn({ duration: 150 })} title="放大">＋ 放大</button>
                </div>
              </>
            )}
          </div>
        </div>

        {/* ===== 画布（填满剩余区域） ===== */}
        <div className="chart-flow-canvas" ref={wrapperRef}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onReconnect={onReconnect}
            onInit={inst => { setRfInstance(inst); setZoom(inst.getZoom()) }}
            onMove={(_, v) => setZoom(v.zoom)}
            onDrop={onDrop}
            onDragOver={onDragOver}
            onNodeDragStart={onNodeDragStart}
            onNodeDragStop={onNodeDragStop}
            onSelectionChange={onSelectionChange}
            onNodeDoubleClick={(_, node) => setEditingNodeId(node.id)}
            onEdgeDoubleClick={(_, edge) => { setSelectedIds([edge.id]) }}
            nodeTypes={nodeTypes}
            deleteKeyCode={null}
            multiSelectionKeyCode={['Shift', 'Control']}
            selectionOnDrag
            panOnDrag={[1, 2]}
            panActivationKeyCode="Space"
            selectionKeyCode={null}
            zoomActivationKeyCode="Control"
            zoomOnScroll
            panOnScroll={false}
            fitView
            fitViewOptions={{ padding: 0.15 }}
            minZoom={0.2}
            maxZoom={3}
            connectionRadius={32}
            reconnectRadius={32}
            connectionLineStyle={{ stroke: '#14b8a6', strokeWidth: 2 }}
            defaultEdgeOptions={{ type: 'smoothstep', reconnectable: true }}
            attributionPosition="bottom-left"
          >
            <Background color="#94a3b8" gap={20} size={1} />
            <MiniMap nodeStrokeWidth={3} className="chart-flow-minimap" />
          </ReactFlow>

          {/* 底部缩放条 */}
          <div className="zoom-bar">
            <button className="zoom-bar-btn" onClick={() => rfInstance?.zoomOut({ duration: 150 })} title="缩小">－</button>
            <button className="zoom-bar-pct" onClick={resetZoom} title="重置为 100%">{Math.round(zoom * 100)}%</button>
            <button className="zoom-bar-btn" onClick={() => rfInstance?.zoomIn({ duration: 150 })} title="放大">＋</button>
            <button className="zoom-bar-btn" onClick={fitView} title="适应画布">⊘</button>
          </div>
        </div>
      </div>
    </EditContext.Provider>
  )
}
