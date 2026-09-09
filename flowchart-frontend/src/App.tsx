import { useEffect, useRef, useState } from 'react'
import { useChartStore } from './store/chartStore'
import { generateStream, refineStream, fetchSession } from './api/client'
import type { ChartType, Format, GraphJson, ProgressEvent } from './api/types'
import ChartFlow from './components/ChartFlow'
import MermaidView from './components/MermaidView'
import { ActivityContent } from './components/ActivityPanel'
import { downloadCurrentView } from './utils/download'

const CHART_TYPES: { value: ChartType; label: string; icon: string }[] = [
  { value: 'flowchart', label: '流程图', icon: '➡️' },
  { value: 'mindmap', label: '思维导图', icon: '🌳' },
  { value: 'architecture', label: '系统架构', icon: '🏗️' }
]

const TOOL_META: Record<string, { label: string; icon: string; iconClass: string }> = {
  read_file:         { label: '读取本地文件', icon: '📄', iconClass: 'read' },
  web_search:        { label: '搜索网页',     icon: '🔍', iconClass: 'search' },
  code_execute:      { label: '执行代码',     icon: '⚙️', iconClass: 'code' },
  retrieve_document: { label: '检索知识库',   icon: '📚', iconClass: 'rag' }
}
const toolCallKey = (toolKey: string, input: string) => `${toolKey}::${input}`

export default function App() {
  const s = useChartStore()
  const abortRef = useRef<AbortController | null>(null)

  // token / 进度浮层开关
  const [tokenOpen, setTokenOpen] = useState(false)
  const tokenStatusRef = useRef<HTMLSpanElement>(null)

  // 任务45：当前画布上的图（含人工编辑），作为 refine 基线
  const [editableGraph, setEditableGraph] = useState<GraphJson | null>(null)

  // 对话面板：历史展开全部 / 面板内轻提示 / 消息列表滚动锚点
  const [showAllHistory, setShowAllHistory] = useState(false)
  const [chatHint, setChatHint] = useState('')
  const msgsRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (msgsRef.current) msgsRef.current.scrollTop = msgsRef.current.scrollHeight
  }, [s.chatMsgs.length, s.loading, s.streamingThinking, s.activity.length])
  useEffect(() => {
    if (!chatHint) return
    const t = setTimeout(() => setChatHint(''), 2600)
    return () => clearTimeout(t)
  }, [chatHint])

  // 下载按钮反馈（成功后短暂显示 ✓）
  const [downloadHint, setDownloadHint] = useState('')
  useEffect(() => {
    if (!downloadHint) return
    const t = setTimeout(() => setDownloadHint(''), 1600)
    return () => clearTimeout(t)
  }, [downloadHint])

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', s.theme)
  }, [s.theme])

  /** 生成与 refine 共用的进度派发（思考流 / 工具调用 / info） */
  function handleProgress(e: ProgressEvent) {
    if (e.provider) s.setCurrentProvider(e.provider)
    if (e.type === 'thinking') {
      // 思考只进「实时思考」卡片，不写入 activity；生成结束/停止也不清空，作为整体输出保留
      s.setStreamingThinking(e.message || '', e.delta)
    } else if (e.type === 'tool_call') {
      const toolKey = e.provider || e.tool || ''
      const input = String(e.input || '').trim()
      const callKey = toolCallKey(toolKey, input)
      const meta = TOOL_META[toolKey] || { label: toolKey, icon: '🔧', iconClass: '' }
      const inputShort = input.length > 18 ? input.slice(0, 18) + '…' : input
      const label = inputShort ? `${meta.label} · ${inputShort}` : meta.label
      const tc = { callKey, toolKey, input, label, icon: meta.icon, iconClass: meta.iconClass, status: 'running' as const, resultCount: 0 }
      s.upsertToolCall(tc)
      s.pushActivity({ kind: 'tool', tool: tc })
    } else if (e.type === 'tool_result') {
      const callKey = toolCallKey(e.provider || '', String(e.input || '').trim())
      const items = e.items || []
      const n = items.length
      s.setToolResultCount(callKey, n)
      // 顺手把结构化结果（搜索网页 / 读取内容片段 / 知识库片段）存到工具卡，UI 展开时显示
      s.upsertActivityTool(callKey, { resultCount: n, status: 'done', items })
    } else if (e.type === 'info' && s.loading && e.message) {
      s.appendThinkingStep(e.message)
      s.pushActivity({ kind: 'info', text: e.message })
    } else if (e.type === 'token_usage' || e.type === 'cache_hit') {
      // 后端推送 token 用量，记录到 store（两种事件可能先后到达，做合并）
      const prev = useChartStore.getState().tokenUsage
      s.setTokenUsage({
        promptTokens: e.promptTokens ?? prev?.promptTokens ?? 0,
        completionTokens: e.completionTokens ?? prev?.completionTokens ?? 0,
        totalTokens: e.totalTokens ?? prev?.totalTokens ?? 0,
        cacheHit: e.type === 'cache_hit' ? true : (prev?.cacheHit ?? false)
      })
    }
    s.pushProgressLog(e.type, e.message || '')
  }

  function setChartType(t: ChartType) {
    s.setChartType(t)
  }

  /** 会话标题：优先取图标题，没有就截短首条输入 */
  function titleOf(text: string, g: GraphJson | null | undefined) {
    return (g?.title && g.title.trim()) || (text.length > 20 ? text.slice(0, 20) + '…' : text)
  }

  /** 相对时间（历史列表用）：刚刚 / n分钟前 / n小时前 / n天前 / 具体日期 */
  function relTime(ts: number) {
    const diff = Date.now() - ts
    const min = 60_000, hour = 3_600_000, day = 86_400_000
    if (diff < min) return '刚刚'
    if (diff < hour) return `${Math.floor(diff / min)}分钟前`
    if (diff < day) return `${Math.floor(diff / hour)}小时前`
    if (diff < 7 * day) return `${Math.floor(diff / day)}天前`
    return new Date(ts).toLocaleDateString()
  }

  /** 首次生成（画布上还没有图） */
  function doGenerate(text: string) {
    s.addChatMsg('user', text)
    s.setLoading(true)
    abortRef.current = generateStream(
      { text, type: s.chartType, model: s.model, format: s.format, useRag: s.useRag, useTool: s.useTool },
      {
        onProgress: handleProgress,
        onDone: (e) => {
          s.setResult(e.graphJson || null, e.svg || '', e.mermaid || '')
          setEditableGraph(e.graphJson || null)
          if (e.sessionId) s.setSessionId(e.sessionId)
          const title = titleOf(text, e.graphJson)
          s.addChatMsg('assistant', `✅ 已生成「${title}」，可在画布继续拖拽编辑`)
          if (e.sessionId) s.upsertHistory(e.sessionId, title)
          s.setLoading(false)
        },
        onError: (e) => {
          s.setError(e.message, e.aiSummary, e.issues)
          s.addChatMsg('assistant', `⚠️ ${e.message}`)
          s.setLoading(false)
        }
      }
    )
  }

  /** 有图后的发送 = refine（多轮对话：沿用 sessionId 让后端带历史上下文） */
  function doRefine(instruction: string) {
    if (!editableGraph) return
    // 去掉布局坐标与边 id，发给后端的是干净的"逻辑图"（与 graph-schema 对齐）
    const payloadGraph: GraphJson = {
      title: editableGraph.title,
      nodes: editableGraph.nodes.map(n => ({ id: n.id, type: n.type, label: n.label })),
      edges: editableGraph.edges.map(e => ({
        source: e.source,
        target: e.target,
        label: (e.label && e.label.trim()) ? e.label : null
      }))
    }
    s.addChatMsg('user', instruction)
    s.setLoading(true)
    abortRef.current = refineStream(
      { type: s.chartType, graphJson: payloadGraph, instruction, model: s.model, format: s.format, useRag: s.useRag, useTool: s.useTool, sessionId: s.sessionId || undefined },
      {
        onProgress: handleProgress,
        onDone: (e) => {
          s.setResult(e.graphJson || null, e.svg || '', e.mermaid || '')
          setEditableGraph(e.graphJson || null)
          if (e.sessionId) {
            s.setSessionId(e.sessionId)
            s.upsertHistory(e.sessionId, titleOf(instruction, e.graphJson))
          }
          const short = instruction.length > 16 ? instruction.slice(0, 16) + '…' : instruction
          s.addChatMsg('assistant', `✅ 已按「${short}」更新图表`)
          s.setLoading(false)
        },
        onError: (e) => {
          s.setError(e.message, e.aiSummary, e.issues)
          s.addChatMsg('assistant', `⚠️ ${e.message}`)
          s.setLoading(false)
        }
      }
    )
  }

  /** 统一发送入口：无图=生成，有图=修改（一个输入框贯穿整个对话，Qoder 式） */
  function onSend() {
    const text = s.text.trim()
    if (!text || s.loading) return
    s.setText('')
    if (hasResult && editableGraph) doRefine(text)
    else doGenerate(text)
  }

  /** 新建对话：清空画布与气泡，sessionId 清空（下次生成后端建新会话）；历史记录保留 */
  function newSession() {
    abortRef.current?.abort()
    s.resetResult()
    setEditableGraph(null)
    s.setText('')
    s.setSessionId('')
    s.setChatMsgs([])
    setShowAllHistory(false)
  }

  /** 打开历史会话：从后端（Redis）拉回消息气泡 + 当前图，接着上一轮继续改 */
  async function openSession(id: string) {
    const res = await fetchSession(id)
    if (!res.ok || !res.data) {
      s.removeHistory(id)   // 过期/不存在的会话直接从历史列表清掉
      setChatHint(res.msg || '会话不存在或已过期')
      return
    }
    const d = res.data
    abortRef.current?.abort()
    s.setSessionId(d.sessionId)
    const g = d.currentGraphJson || null
    s.setResult(g, '', '')   // 只回灌 graphJson，画布走可编辑渲染
    setEditableGraph(g)
    s.setChatMsgs(
      d.messages
        .filter(m => m.role === 'user' || m.role === 'assistant')
        .map(m => ({ role: m.role as 'user' | 'assistant', content: m.content, at: Date.now() }))
    )
    s.setError('', '')
    setChatHint('已恢复历史对话')
  }

  function onStop() {
    abortRef.current?.abort()
    s.setLoading(false)
  }

  async function onDownload() {
    const res = await downloadCurrentView({ format: s.format, graphJson: s.graphJson })
    setDownloadHint(res.ok ? `已下载 ${res.kind.toUpperCase()}` : (res.msg || '下载失败'))
  }

  const hasResult = !!(s.graphJson || s.svg || s.mermaid)
  const activeType = CHART_TYPES.find(t => t.value === s.chartType)!

  return (
    <div className="app-layout">
      {/* 左侧栏 */}
      <aside className="sidebar">
        <div className="sidebar-brand">
          <div className="logo">F</div>
          <span>FlowAI</span>
        </div>
        <button className="sidebar-new-btn" onClick={newSession} title="新建对话（画布与消息清空，历史保留）">
          <span>＋</span> 新建
        </button>

        {/* 历史对话直接平铺在侧栏（标题 + 相对时间，点击恢复），超出折叠 */}
        <div className="sidebar-section">
          历史{s.history.length > 0 ? ` (${s.history.length})` : ''}
        </div>
        {s.history.length === 0 && (
          <div className="sidebar-history-empty">暂无历史对话</div>
        )}
        {(showAllHistory ? s.history : s.history.slice(0, 6)).map(h => (
          <div
            key={h.id}
            className={'sidebar-history-item' + (s.sessionId === h.id ? ' active' : '')}
            onClick={() => openSession(h.id)}
            title={h.title}
          >
            <span className="sidebar-history-title">{h.title}</span>
            <span className="sidebar-history-time">{relTime(h.at)}</span>
          </div>
        ))}
        {s.history.length > 6 && (
          <button className="sidebar-history-more" onClick={() => setShowAllHistory(o => !o)}>
            {showAllHistory ? '收起' : `查看更多 (${s.history.length - 6})`}
          </button>
        )}

        <div className="sidebar-section">更多</div>
        <div className="sidebar-item disabled"><span>📋</span><span>模板中心</span></div>

        <div className="sidebar-section">设置</div>
        <div className="sidebar-item" onClick={s.toggleTheme}>
          <span>{s.theme === 'light' ? '🌙' : '☀️'}</span>
          <span>{s.theme === 'light' ? '暗色模式' : '明亮模式'}</span>
        </div>

        <div className="sidebar-spacer" />
        <div className="sidebar-footer">FlowAI v0.4 · M3 + 45</div>
      </aside>

      {/* 右侧工作区 */}
      <main className="workspace">
        {/* 顶部标题栏 */}
        <header className="topbar">
          <span className="topbar-title">{activeType.label}</span>
          <div className="topbar-spacer" />
          {s.currentProvider && (
            <span className="topbar-provider">由 {s.currentProvider} 生成</span>
          )}
        </header>

        {/* 进度条（生成中） */}
        {s.loading && <div className="topbar-progress"><div className="topbar-progress-bar" /></div>}

        {/* 画布 + 右侧对话面板 */}
        <div className="workspace-body">
        <section className="canvas-area">
          {/* 错误提示 + 校验问题明细 + aiSummary 折叠区（画布内顶部） */}
          {s.errorMessage && (
            <div className="error-msg-wrapper">
              <div className="error-msg">{s.errorMessage}</div>
              {s.errorIssues.length > 0 && (
                <ul className="error-issues">
                  {s.errorIssues.map((it, i) => (
                    <li key={i} className="error-issue">
                      <div className="error-issue-main">
                        <span className="error-issue-field">{it.field}</span>
                        <span className="error-issue-reason">{it.reason}</span>
                      </div>
                      {it.hint && <div className="error-issue-hint">💡 {it.hint}</div>}
                    </li>
                  ))}
                </ul>
              )}
              {s.aiSummary && (
                <details className="ai-summary-fold">
                  <summary>💬 查看 AI 实际说了什么</summary>
                  <div className="ai-summary-body">{s.aiSummary}</div>
                </details>
              )}
            </div>
          )}
          {hasResult ? (
            <>
              <div className="canvas-renderer">
                <Renderer chartType={s.chartType} onChartChange={setEditableGraph} />
              </div>
              {/* 下载/导出悬浮按钮（左下角，不再占用顶部横条） */}
              <div className="canvas-download-float">
                <button title="下载 / 导出当前视图" onClick={onDownload}>↓ 下载</button>
                {downloadHint && <span className="canvas-toolbar-hint">{downloadHint}</span>}
              </div>
            </>
          ) : (
            <div className="canvas-empty">
              <div>在右侧输入描述，点击发送生成 {activeType.label}</div>
              <div className="hint">例如：用户登录流程，三次密码错误锁定账号</div>
            </div>
          )}
        </section>

        {/* 右侧对话面板（可收起；收起后画布右缘显示浮动把手） */}
        {s.chatOpen ? (
          <aside className="chat-panel">
            <div className="chat-head">
              <span className="chat-head-title">💬 对话</span>
              <div className="chat-head-actions">
                <button className="chat-head-btn" title="新建对话" onClick={newSession}>＋</button>
                <button className="chat-head-btn" title="收起面板" onClick={() => s.setChatOpen(false)}>»</button>
              </div>
            </div>

            <div className="chat-msgs" ref={msgsRef}>
              {s.chatMsgs.length === 0 && !s.loading && (
                <div className="chat-empty">
                  开始你的第一句：描述想要的图表。<br />
                  之前的对话在 🕘 历史记录里，点击可恢复。
                </div>
              )}
              {s.chatMsgs.map((m, i) => (
                <div key={i} className={'chat-msg ' + m.role}>
                  <div className="chat-bubble">{m.content}</div>
                </div>
              ))}
              {/* 实时过程：思考流 + 工具调用直接作为一条消息嵌在聊天里（无需点击浮层） */}
              {(s.loading || s.activity.length > 0 || s.streamingThinking) && (
                <div className="chat-msg assistant activity">
                  <div className="chat-bubble chat-bubble-wide">
                    <ActivityContent />
                  </div>
                </div>
              )}
              {s.loading && (
                <div className="chat-msg assistant pending">
                  <div className="chat-bubble">{hasResult ? '⏳ 正在修改图表…' : '⏳ 正在生成图表…'}</div>
                </div>
              )}
            </div>

            {chatHint && <div className="chat-hint">{chatHint}</div>}

            {/* 输入区（原底部输入卡整体移入；token 浮层锚点不变） */}
            <div className="chat-input-area">
              <div className="input-card-wrapper">
                <div className="input-card">
                  {/* 生成模式（图表类型）选择 */}
                  <div className="input-card-mode">
                    <span className="input-card-mode-label">生成</span>
                    <div className="input-card-mode-opts">
                      {CHART_TYPES.map(t => (
                        <button
                          key={t.value}
                          className={'input-card-mode-btn' + (s.chartType === t.value ? ' active' : '')}
                          onClick={() => setChartType(t.value)}
                          title={`生成${t.label}`}
                        >
                          {t.icon} {t.label}
                        </button>
                      ))}
                    </div>
                  </div>
                  <div className="input-card-top">
                    <textarea
                      placeholder={hasResult ? '输入修改指令，如：加一个验证码步骤' : '例如：用户登录流程，三次密码错误锁定账号'}
                      value={s.text}
                      onChange={e => s.setText(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onSend() } }}
                      rows={1}
                    />
                    {s.loading ? (
                      <button className="input-card-send stop" onClick={onStop} title="停止生成">■</button>
                    ) : (
                      <button className="input-card-send" onClick={onSend} disabled={!s.text.trim()} title={hasResult ? '发送修改指令' : '生成'}>➤</button>
                    )}
                  </div>
                  <div className="input-card-bottom">
                    <label className={'pill-toggle' + (s.useRag ? ' on' : '')}>
                      <input type="checkbox" checked={s.useRag} onChange={e => s.setUseRag(e.target.checked)} /> 📚 知识库
                    </label>
                    <label className={'pill-toggle' + (s.useTool ? ' on' : '')}>
                      <input type="checkbox" checked={s.useTool} onChange={e => s.setUseTool(e.target.checked)} /> 🔧 工具
                    </label>
                    <div className="input-card-divider" />
                    <div className="input-card-field">
                      <span className="input-card-field-label">模型</span>
                      <select className="input-card-select" value={s.model} onChange={e => s.setModel(e.target.value as any)}>
                        <option value="mimo">MiMo</option>
                        <option value="kimi">Kimi</option>
                      </select>
                    </div>
                    <div className="input-card-divider" />
                    <div className="input-card-field">
                      <span className="input-card-field-label">格式</span>
                      <select className="input-card-select" value={s.format} onChange={e => s.setFormat(e.target.value as Format)} title="输出格式">
                        <option value="svg">标准（可编辑）</option>
                        <option value="mermaid">Mermaid（仅导出）</option>
                      </select>
                    </div>
                    <span
                      ref={tokenStatusRef}
                      className={'input-card-status token-status' + (tokenOpen ? ' open' : '')}
                      onClick={() => setTokenOpen(o => !o)}
                      title="查看 Token 消耗与进度时间线"
                    >
                      {s.tokenUsage ? (
                        <>
                          <span>⚡ {s.tokenUsage.totalTokens.toLocaleString()} token</span>
                          {s.tokenUsage.cacheHit && <span className="token-cache-tag">缓存</span>}
                        </>
                      ) : (
                        <span>{s.loading ? (s.streamingThinking ? '💭 思考中…' : '生成中…') : `${s.text.length} 字`}</span>
                      )}
                    </span>
                  </div>
                  <div className="input-card-hint">
                    {hasResult
                      ? '对话模式：输入会作为修改指令基于当前画布改图（多轮记忆）'
                      : s.format === 'mermaid' ? 'Mermaid 仅导出：后端只返 Mermaid 代码，画布不可拖拽编辑' : '标准模式：画布可拖拽编辑、可导出'}
                  </div>
                </div>

                {/* Token / 进度时间线 浮层（位于输入框上方、同宽、透明） */}
                {tokenOpen && (
                  <TokenPopup onClose={() => setTokenOpen(false)} anchorRef={tokenStatusRef} />
                )}
              </div>
            </div>
          </aside>
        ) : (
          <button className="chat-open-float" onClick={() => s.setChatOpen(true)} title="展开对话面板">💬</button>
        )}
        </div>
      </main>
    </div>
  )
}

/** 透明浮层：token 用量 + 进度时间线 */
function TokenPopup({ onClose, anchorRef }: { onClose: () => void; anchorRef: React.RefObject<HTMLSpanElement | null> }) {
  const ref = useRef<HTMLDivElement>(null)
  const s = useChartStore()

  useEffect(() => {
    function onMouseDown(e: MouseEvent) {
      const target = e.target as Node
      if (ref.current && !ref.current.contains(target) && anchorRef.current && !anchorRef.current.contains(target)) {
        onClose()
      }
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', onMouseDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onMouseDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [onClose, anchorRef])

  return (
    <div ref={ref} className="token-popup">
      <div className="token-popup-header">
        <span className="token-popup-title">⚡ Token 消耗 & 进度</span>
        <span className="token-popup-hint">再次点击状态区收起</span>
      </div>
      <div className="token-popup-body">
        {s.tokenUsage ? (
          <div className="token-usage-summary">
            <div className="token-usage-row">
              <span className="token-usage-label">Prompt</span>
              <span className="token-usage-value">{s.tokenUsage.promptTokens.toLocaleString()}</span>
            </div>
            <div className="token-usage-row">
              <span className="token-usage-label">Completion</span>
              <span className="token-usage-value">{s.tokenUsage.completionTokens.toLocaleString()}</span>
            </div>
            <div className="token-usage-row total">
              <span className="token-usage-label">Total</span>
              <span className="token-usage-value">{s.tokenUsage.totalTokens.toLocaleString()}</span>
              {s.tokenUsage.cacheHit && <span className="token-cache-tag">命中缓存</span>}
            </div>
          </div>
        ) : (
          <div className="token-usage-empty">尚未产生 token 用量，发送后会在这里显示。</div>
        )}
        <div className="token-popup-divider" />
        <ActivityContent />
      </div>
    </div>
  )
}

/** 根据 viewMode 选择渲染器（任务45：画布=可编辑 React Flow，svg/mermaid=只读预览） */
function Renderer({ chartType, onChartChange }: { chartType: ChartType; onChartChange: (g: GraphJson) => void }) {
  const s = useChartStore()
  if (s.viewMode === 'mermaid' && s.mermaid) {
    return <MermaidView source={s.mermaid} />
  }
  if (s.graphJson) {
    return <ChartFlow graphJson={s.graphJson} chartType={chartType} onChange={onChartChange} />
  }
  // 兜底：无 graphJson 时退而求其次用 mermaid
  if (s.mermaid) return <MermaidView source={s.mermaid} />
  return <div className="canvas-empty">暂无可用渲染数据</div>
}
