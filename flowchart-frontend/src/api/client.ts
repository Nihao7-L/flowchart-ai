import type { ChartType, Model, Format, DoneEvent, ErrorEvent, ProgressEvent, RefineParams, SessionDetail } from './types'

/** 生成请求参数 */
export interface GenerateParams {
  text: string
  type: ChartType
  model: Model
  format: Format
  useRag: boolean
  useTool: boolean
  /** 会话 ID（任务40：多轮记忆），首次不传由后端建新 */
  sessionId?: string
}

/** SSE 回调：分事件类型派发 */
export interface GenerateHandlers {
  onProgress: (e: ProgressEvent) => void
  onDone: (e: DoneEvent) => void
  onError: (e: ErrorEvent) => void
}

/** 调用 /api/generate/stream，解析 SSE 帧。返回 AbortController 用于中断。 */
export function generateStream(
  params: GenerateParams,
  handlers: GenerateHandlers,
  signal?: AbortSignal
): AbortController {
  const ctrl = signal ? undefined : new AbortController()
  const effectiveSignal = signal ?? ctrl!.signal

  const qs = new URLSearchParams({
    text: params.text,
    type: params.type,
    model: params.model,
    format: params.format
  })
  if (params.useRag) qs.set('useRag', 'true')
  if (params.useTool) qs.set('useTool', 'true')
  if (params.sessionId) qs.set('sessionId', params.sessionId)

  ;(async () => {
    try {
      const res = await fetch('/api/generate/stream?' + qs.toString(), { signal: effectiveSignal })
      if (!res.ok || !res.body) throw new Error('HTTP ' + res.status)
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          handleFrame(frame, handlers)
        }
      }
    } catch (err: any) {
      if (err.name === 'AbortError') {
        handlers.onError({ message: '已停止生成' })
      } else {
        handlers.onError({ message: '生成失败: ' + (err.message || err) })
      }
    }
  })()

  return ctrl!
}

function handleFrame(frame: string, h: GenerateHandlers) {
  let eventName = 'message'
  const dataLines: string[] = []
  frame.split('\n').forEach(line => {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  })
  if (!dataLines.length) return
  let data: any
  try { data = JSON.parse(dataLines.join('\n')) } catch { return }
  if (eventName === 'progress') h.onProgress(data as ProgressEvent)
  else if (eventName === 'done') h.onDone(data as DoneEvent)
  else if (eventName === 'error') h.onError(data as ErrorEvent)
}

/**
 * 任务45：调用 /api/refine/stream（POST，请求体带当前图 + 修改指令），解析 SSE 帧。
 * 与 generateStream 共用同一套 SSE 帧解析（handleFrame）。返回 AbortController 用于中断。
 */
export function refineStream(
  params: RefineParams,
  handlers: GenerateHandlers,
  signal?: AbortSignal
): AbortController {
  const ctrl = signal ? undefined : new AbortController()
  const effectiveSignal = signal ?? ctrl!.signal

  ;(async () => {
    try {
      const res = await fetch('/api/refine/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params),
        signal: effectiveSignal
      })
      if (!res.ok || !res.body) throw new Error('HTTP ' + res.status)
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          handleFrame(frame, handlers)
        }
      }
    } catch (err: any) {
      if (err.name === 'AbortError') {
        handlers.onError({ message: '已停止修改' })
      } else {
        handlers.onError({ message: '修改失败: ' + (err.message || err) })
      }
    }
  })()

  return ctrl!
}

/**
 * 任务40：取会话详情（历史消息 + 当前图）。
 * 前端「历史记录」点击某条会话时调用：messages 恢复对话气泡，currentGraphJson 恢复画布。
 */
export async function fetchSession(
  sessionId: string
): Promise<{ ok: boolean; data?: SessionDetail; msg?: string }> {
  try {
    const res = await fetch('/api/session/' + encodeURIComponent(sessionId))
    const json = await res.json()
    if (json.code === 200) return { ok: true, data: json.data as SessionDetail }
    return { ok: false, msg: json.message || '会话不存在或已过期' }
  } catch (err: any) {
    return { ok: false, msg: '加载会话失败: ' + (err.message || err) }
  }
}
