// 与后端 Result / SSE 事件契约对齐的类型

export type ChartType = 'flowchart' | 'mindmap' | 'architecture'
export type Model = 'mimo' | 'kimi'
export type Format = 'svg' | 'mermaid'

/** GraphJson：任务 43 唯一图模型，LLM 输出契约 + 内部权威 + 前端契约 */
/** 后端严格认识的节点类型（plantuml/mermaid 渲染用） */
export type BackendNodeType = 'start' | 'process' | 'decision' | 'end' | 'component' | 'mindmap'
/** 前端编辑器可用的节点形状（包含后端不认识的扩展形状，序列化时降级映射） */
export type GraphNodeType = BackendNodeType | 'input' | 'output' | 'database' | 'document' | 'rectangle' | 'rounded' | 'ellipse' | 'circle' | 'diamond' | 'parallelogram' | 'cloud'

export interface GraphNode {
  id: string
  type: GraphNodeType
  label: string
  position?: { x: number; y: number } | null
  /** 前端扩展：节点样式（后端可忽略，refine 透传即可） */
  style?: { fill?: string; stroke?: string; color?: string } | null
}
export interface GraphEdge {
  id?: string
  source: string
  target: string
  label?: string | null
}
export interface GraphJson {
  title?: string
  nodes: GraphNode[]
  edges: GraphEdge[]
}

/** Refine 请求参数（任务45：画布编辑回流） */
export interface RefineParams {
  type: ChartType
  graphJson: GraphJson
  instruction: string
  model: Model
  format: Format
  useRag: boolean
  useTool: boolean
  /** 会话 ID（任务40：多轮记忆），随 refine 上报以延续上下文 */
  sessionId?: string
}

/** SSE 事件载荷 */
export interface ProgressEvent {
  type: 'info' | 'token_usage' | 'cache_hit' | 'tool_call' | 'tool_result' | 'thinking'
  message?: string
  // token_usage / cache_hit
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
  // tool_call
  provider?: string
  tool?: string
  input?: string
  // tool_result
  items?: Array<{ title: string; url: string; snippet: string }>
  // thinking 流式标记
  delta?: boolean
  // 当前真正调用的 provider（区别于"我选的"）
  currentProvider?: string
}

/** done 事件：最终结果 */
export interface DoneEvent {
  graphJson?: GraphJson
  svg?: string
  plantUml?: string
  mermaid?: string
  format: Format
  type: ChartType
  // RAG 引用
  ragSources?: string[]
  ragScores?: number[]
  ragContext?: string
  ragHits?: Array<{ chunkId: string; title: string; snippet: string; score: number }>
  ragDegraded?: boolean
  /** 会话 ID（任务40：多轮记忆），首次生成由后端建并返回 */
  sessionId?: string
}

/** error 事件 */
export interface ErrorEvent {
  message: string
  issues?: Array<{ field: string; reason: string; hint?: string }>
  aiSummary?: string
}

/** 对话面板里的一条气泡（前端本地维护的会话显示记录） */
export interface ChatMsg {
  role: 'user' | 'assistant'
  content: string
  at: number
}

/** 历史记录条目（localStorage 持久化，仅存 id + 标题；内容按需从后端拉） */
export interface SessionMeta {
  id: string
  title: string
  at: number
}

/** GET /api/session/{id} 返回的会话详情（后端 Session 的 JSON） */
export interface SessionDetail {
  sessionId: string
  messages: Array<{ role: string; content: string }>
  currentGraphJson: GraphJson | null
  store?: string
}
