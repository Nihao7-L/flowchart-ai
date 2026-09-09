import { create } from 'zustand'
import type { ChartType, Model, Format, GraphJson, ChatMsg, SessionMeta } from '../api/types'

/** 从 localStorage 读历史会话列表（坏数据静默丢弃，当作没有历史） */
function readHistory(): SessionMeta[] {
  try {
    const raw = localStorage.getItem('flowai-history')
    const arr = raw ? JSON.parse(raw) : []
    return Array.isArray(arr) ? arr.filter((x: any) => x && typeof x.id === 'string') : []
  } catch {
    return []
  }
}

/** 工具调用记录（与 Vue 端 toolCalls 同构） */
export interface ToolCall {
  callKey: string
  toolKey: string
  input: string
  label: string
  icon: string
  iconClass: string
  status: 'running' | 'done'
  resultCount: number
  /** 结构化结果列表（如 web_search 的网页列表：title/url/snippet/site） */
  items?: Array<{ title: string; url: string; snippet?: string; site?: string; date?: string }>
}

/** 统一进度时间线的一项（思考 / 工具调用 / 系统提示），按 SSE 到达顺序排布 */
export type ActivityKind = 'thinking' | 'tool' | 'info'
export interface ActivityItem {
  id: string
  kind: ActivityKind
  text?: string        // thinking / info 的文本
  tool?: ToolCall       // tool 类型携带完整 ToolCall
}

/** Token 消耗（SSE token_usage / cache_hit） */
export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  cacheHit: boolean
}

interface ChartState {
  // 输入与配置
  text: string
  chartType: ChartType
  model: Model
  format: Format
  useRag: boolean
  useTool: boolean
  theme: 'light' | 'dark'

  // 任务45：画布视图模式（默认画布=可编辑；svg/mermaid=只读预览）
  viewMode: 'canvas' | 'mermaid'

  // 过程状态
  // 过程状态
  loading: boolean
  errorMessage: string
  aiSummary: string
  /** 校验失败的具体问题列表（后端 ValidationIssue：field/reason/hint） */
  errorIssues: Array<{ field: string; reason: string; hint?: string }>
  streamingThinking: string
  thinkingSteps: string[]
  toolCalls: ToolCall[]
  /** 统一进度时间线：思考 / 工具调用 / 系统提示 按 SSE 到达顺序 */
  activity: ActivityItem[]
  progressLogs: Array<{ type: string; message: string }>
  currentProvider: string
  /** 任务40：会话记忆，当前连续对话的会话 ID（空=尚未建立） */
  sessionId: string
  /** Token 消耗（SSE token_usage / cache_hit） */
  tokenUsage: TokenUsage | null

  // 对话面板（右侧，Qoder 式）：展开态 / 当前会话的气泡 / 历史会话列表
  chatOpen: boolean
  chatMsgs: ChatMsg[]
  history: SessionMeta[]

  // 最终结果
  graphJson: GraphJson | null
  svg: string
  mermaid: string

  // actions
  setText: (v: string) => void
  setChartType: (t: ChartType) => void
  setModel: (m: Model) => void
  setFormat: (f: Format) => void
  setUseRag: (v: boolean) => void
  setUseTool: (v: boolean) => void
  setTheme: (t: 'light' | 'dark') => void
  toggleTheme: () => void
  setViewMode: (v: 'canvas' | 'mermaid') => void

  setLoading: (v: boolean) => void
  setError: (msg: string, aiSummary?: string, issues?: Array<{ field: string; reason: string; hint?: string }>) => void
  setAiSummary: (v: string) => void
  appendThinkingStep: (s: string) => void
  setStreamingThinking: (s: string, delta?: boolean) => void
  upsertToolCall: (tc: ToolCall) => void
  setToolResultCount: (callKey: string, n: number) => void
  /** 统一进度时间线：追加一项 / 更新某条工具调用 */
  pushActivity: (item: Omit<ActivityItem, 'id'>) => void
  upsertActivityTool: (callKey: string, patch: Partial<ToolCall>) => void
  pushProgressLog: (type: string, message: string) => void
  setCurrentProvider: (p: string) => void
  setSessionId: (id: string) => void
  /** 记录后端 SSE 推送的 token 用量 */
  setTokenUsage: (u: TokenUsage | null) => void

  // 对话面板
  setChatOpen: (v: boolean) => void
  addChatMsg: (role: ChatMsg['role'], content: string) => void
  setChatMsgs: (msgs: ChatMsg[]) => void
  /** 历史记录：置顶插入/更新一条（title 取用户首条输入截短） */
  upsertHistory: (id: string, title: string) => void
  removeHistory: (id: string) => void

  setResult: (g: GraphJson | null, svg: string, mermaid: string) => void
  resetResult: () => void
}

export const useChartStore = create<ChartState>((set) => ({
  text: '',
  chartType: 'flowchart',
  model: 'mimo',
  format: 'svg',
  useRag: false,
  useTool: false,
  theme: (localStorage.getItem('flowai-theme') as 'light' | 'dark') || 'light',

  viewMode: 'canvas',

  loading: false,
  errorMessage: '',
  aiSummary: '',
  errorIssues: [],
  streamingThinking: '',
  thinkingSteps: [],
  toolCalls: [],
  activity: [],
  progressLogs: [],
  currentProvider: '',
  // 任务40：sessionId 落 localStorage —— 后端会话已持久化到 Redis，
  // 前端刷新页面后仍能拿回同一个 sessionId，接着上一轮的图继续改
  sessionId: localStorage.getItem('flowai-session-id') || '',
  tokenUsage: null,

  chatOpen: localStorage.getItem('flowai-chat-open') !== '0',
  chatMsgs: [],
  history: readHistory(),

  graphJson: null,
  svg: '',
  mermaid: '',

  setText: (v) => set({ text: v }),
  setChartType: (t) => set({ chartType: t }),
  setModel: (m) => set({ model: m }),
  setFormat: (f) => set({ format: f }),
  setUseRag: (v) => set({ useRag: v }),
  setUseTool: (v) => set({ useTool: v }),
  setTheme: (t) => { localStorage.setItem('flowai-theme', t); set({ theme: t }) },
  toggleTheme: () => set((s) => {
    const next = s.theme === 'light' ? 'dark' : 'light'
    localStorage.setItem('flowai-theme', next)
    return { theme: next }
  }),

  setViewMode: (v) => set({ viewMode: v }),

  setLoading: (v) => set({ loading: v }),
  setError: (msg, aiSummary, issues) => set({ errorMessage: msg, aiSummary: aiSummary ?? '', errorIssues: issues ?? [] }),
  setAiSummary: (v) => set({ aiSummary: v }),
  appendThinkingStep: (s) => set((st) => ({ thinkingSteps: [...st.thinkingSteps, s] })),
  setStreamingThinking: (s, delta) => set((st) => ({
    streamingThinking: delta ? st.streamingThinking + s : s
  })),
  upsertToolCall: (tc) => set((st) => {
    const idx = st.toolCalls.findIndex(x => x.callKey === tc.callKey)
    if (idx >= 0) {
      const next = [...st.toolCalls]; next[idx] = { ...next[idx], ...tc }; return { toolCalls: next }
    }
    return { toolCalls: [...st.toolCalls, tc] }
  }),
  setToolResultCount: (callKey, n) => set((st) => ({
    toolCalls: st.toolCalls.map(tc => tc.callKey === callKey ? { ...tc, resultCount: n, status: 'done' } : tc)
  })),
  pushProgressLog: (type, message) => set((st) => ({
    progressLogs: [...st.progressLogs, { type, message }]
  })),
  pushActivity: (item) => set((st) => ({
    activity: [...st.activity, { ...item, id: `a${st.activity.length}-${Date.now()}` }]
  })),
  upsertActivityTool: (callKey, patch) => set((st) => ({
    activity: st.activity.map(a =>
      (a.kind === 'tool' && a.tool?.callKey === callKey)
        ? { ...a, tool: { ...a.tool!, ...patch } }
        : a
    )
  })),
  setCurrentProvider: (p) => set({ currentProvider: p }),
  setSessionId: (id: string) => {
    if (id) localStorage.setItem('flowai-session-id', id)
    else localStorage.removeItem('flowai-session-id')   // 传空=新建会话，清掉旧的
    set({ sessionId: id })
  },
  setTokenUsage: (u) => set({ tokenUsage: u }),

  // 对话面板
  setChatOpen: (v) => {
    localStorage.setItem('flowai-chat-open', v ? '1' : '0')
    set({ chatOpen: v })
  },
  addChatMsg: (role, content) => set((st) => ({
    chatMsgs: [...st.chatMsgs, { role, content, at: Date.now() }]
  })),
  setChatMsgs: (msgs) => set({ chatMsgs: msgs }),
  upsertHistory: (id, title) => set((st) => {
    const next: SessionMeta[] = [
      { id, title, at: Date.now() },
      ...st.history.filter(h => h.id !== id)
    ].slice(0, 50)   // 最多记 50 条，防 localStorage 无限膨胀
    localStorage.setItem('flowai-history', JSON.stringify(next))
    return { history: next }
  }),
  removeHistory: (id) => set((st) => {
    const next = st.history.filter(h => h.id !== id)
    localStorage.setItem('flowai-history', JSON.stringify(next))
    return { history: next }
  }),

  setResult: (g, svg, mermaid) => set({ graphJson: g, svg, mermaid }),
  resetResult: () => set({
    errorMessage: '', aiSummary: '', errorIssues: [],
    streamingThinking: '', thinkingSteps: [], toolCalls: [], activity: [], progressLogs: [],
    tokenUsage: null,
    graphJson: null, svg: '', mermaid: ''
  })
}))
