import { useEffect, useRef, useState } from 'react'
import SidePanel from './SidePanel'
import { useChartStore, type ActivityItem, type ToolCall } from '../store/chartStore'

const TOOL_META: Record<string, { icon: string; label: string; iconClass: string }> = {
  read_file:         { icon: '📄', label: '读取本地文件', iconClass: 'read' },
  web_search:        { icon: '🔍', label: '搜索网页',     iconClass: 'search' },
  code_execute:      { icon: '⚙️', label: '执行代码',     iconClass: 'code' },
  retrieve_document: { icon: '📚', label: '检索知识库',   iconClass: 'rag' }
}

type Group =
  | { kind: 'info'; key: string; items: ActivityItem[] }
  | { kind: 'tool'; key: string; item:  ActivityItem }

/** 全局归并：所有系统提示合成一个卡片、工具按出现顺序各一张。
 *  思考不生成卡片——统一由顶部「⌛ 实时思考」实时展示，生成结束也保留。 */
function buildGroups(items: ActivityItem[]): Group[] {
  const infos: ActivityItem[] = []
  const tools: Group[] = []
  for (const it of items) {
    if (it.kind === 'info') infos.push(it)
    else if (it.kind === 'tool') tools.push({ kind: 'tool', key: it.id, item: it })
    // thinking 项忽略，不生成卡片
  }
  const groups: Group[] = []
  if (infos.length) groups.push({ kind: 'info', key: 'info', items: infos })
  return groups.concat(tools)
}

/** 可复用的进度时间线内容（无抽屉外壳），用于 token 浮层 / 侧栏抽屉 */
export function ActivityContent() {
  const s = useChartStore()
  const items = s.activity
  const bodyRef = useRef<HTMLDivElement>(null)
  const groups = buildGroups(items)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  // 实时思考默认展开（生成中要看到流式输出），可手动折叠
  const [streamingExpanded, setStreamingExpanded] = useState(true)

  useEffect(() => {
    if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight
  }, [items.length, s.streamingThinking])

  // 进行中的工具自动展开
  useEffect(() => {
    const running = groups
      .filter(g => g.kind === 'tool' && g.item.tool?.status === 'running')
      .map(g => g.key)
    if (running.length) {
      setExpanded(prev => {
        const next = new Set(prev)
        running.forEach(k => next.add(k))
        return next
      })
    }
  }, [items])

  function toggle(key: string) {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key); else next.add(key)
      return next
    })
  }

  return (
    <div ref={bodyRef} className="token-activity-body">
      {items.length === 0 && !s.streamingThinking && !s.tokenUsage && (
        <div className="side-panel-empty">发送后，思考、工具调用会按时间顺序显示在这里</div>
      )}

      {s.streamingThinking && (
        <div className="activity-group is-streaming">
          <button className="activity-group-header" onClick={() => setStreamingExpanded(o => !o)}>
            <span className="activity-group-icon">⌛</span>
            <span className="activity-group-title">实时思考</span>
            <span className={'activity-group-caret' + (streamingExpanded ? ' open' : '')}>▸</span>
          </button>
          {streamingExpanded && (
            <div className="activity-group-body">
              <div className="activity-think-merged">{s.streamingThinking}</div>
              <span className="activity-cursor" />
            </div>
          )}
        </div>
      )}

      {groups.length > 0 && (
        <div className="activity-groups">
          {groups.map(g => (
            <ActivityGroup key={g.key} group={g} expanded={expanded.has(g.key)} onToggle={() => toggle(g.key)} />
          ))}
        </div>
      )}
    </div>
  )
}

export default function ActivityPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const s = useChartStore()
  const items = s.activity

  return (
    <SidePanel open={open} title="⚡ 进度时间线" badge={items.length || undefined} onClose={onClose}>
      <ActivityContent />
    </SidePanel>
  )
}

function ActivityGroup({ group, expanded, onToggle }: { group: Group; expanded: boolean; onToggle: () => void }) {
  if (group.kind === 'info') {
    return (
      <div className="activity-group">
        <button className="activity-group-header" onClick={onToggle}>
          <span className="activity-group-icon">ℹ️</span>
          <span className="activity-group-title">系统提示</span>
          <span className="activity-group-meta">{group.items.length} 条</span>
          <span className={'activity-group-caret' + (expanded ? ' open' : '')}>▸</span>
        </button>
        {expanded && (
          <div className="activity-group-body">
            {group.items.map(it => (
              <div key={it.id} className="activity-info-line">{it.text}</div>
            ))}
          </div>
        )}
      </div>
    )
  }

  const tc = group.item.tool as ToolCall
  const meta = TOOL_META[tc.toolKey] || { icon: '🔧', label: tc.toolKey, iconClass: '' }
  const inputShort = tc.input.length > 24 ? tc.input.slice(0, 24) + '…' : tc.input
  const done = tc.status === 'done'
  return (
    <div className={'activity-group tool ' + (tc.iconClass || '') + (done ? '' : ' running')}>
      <button className="activity-group-header" onClick={onToggle}>
        <span className="activity-group-icon">{meta.icon}</span>
        <span className="activity-group-title">{meta.label}</span>
        {inputShort && <span className="activity-group-subtitle">{inputShort}</span>}
        <span className={'activity-group-status ' + tc.status}>
          {done ? `✓ ${tc.resultCount} 结果` : '⏳ 执行中'}
        </span>
        <span className={'activity-group-caret' + (expanded ? ' open' : '')}>▸</span>
      </button>
      {expanded && (
        <div className="activity-group-body">
          <div className="activity-tool-detail">
            {tc.input && (
              <div className="activity-tool-detail-row">
                <span className="activity-tool-detail-label">输入</span>
                <code className="activity-tool-detail-value">{tc.input}</code>
              </div>
            )}
            {tc.items && tc.items.length > 0 && (
              <div className="activity-tool-section">
                <div className="activity-tool-section-label">结果 · {tc.items.length} 条</div>
                <ol className="activity-tool-results">
                  {tc.items.map((it, idx) => (
                    <li key={idx} className="activity-tool-result">
                      <div className="activity-tool-result-head">
                        <span className="activity-tool-result-idx">{idx + 1}</span>
                        {it.url ? (
                          <a className="activity-tool-result-title" href={it.url} target="_blank" rel="noreferrer">
                            {it.title || '(无标题)'}
                          </a>
                        ) : (
                          <span className="activity-tool-result-title">{it.title || '(无标题)'}</span>
                        )}
                        {it.site && <span className="activity-tool-result-site">{it.site}</span>}
                      </div>
                      {it.snippet && <div className="activity-tool-result-snippet">{it.snippet}</div>}
                    </li>
                  ))}
                </ol>
              </div>
            )}
            <div className="activity-tool-detail-row">
              <span className="activity-tool-detail-label">状态</span>
              <span className="activity-tool-detail-status">
                {done ? `✓ 已完成，收集到 ${tc.resultCount} 条结果` : '⏳ 正在执行，等待结果…'}
              </span>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
