import { useEffect, useRef, useState } from 'react'
import mermaid from 'mermaid'

/**
 * mermaid.initialize 每次按需调用一次即可（保证 theme 等变更能生效）。
 * 用模块级 lastTheme 缓存，避免 unmount 后重复 init 引起的抖动。
 */
let lastMermaidTheme: 'light' | 'dark' | null = null
function ensureMermaid(theme: 'light' | 'dark') {
  if (lastMermaidTheme === theme) return
  mermaid.initialize({
    startOnLoad: false,
    theme: theme === 'dark' ? 'dark' : 'default',
    securityLevel: 'loose',
    flowchart: { useMaxWidth: true, htmlLabels: true, curve: 'basis' },
    themeVariables: theme === 'dark'
      ? { background: '#0f1115', primaryColor: '#1a1e2a', primaryTextColor: '#eef1f6', primaryBorderColor: '#2b303c', lineColor: '#94a3b8', fontFamily: 'inherit' }
      : { background: '#ffffff', fontFamily: 'inherit' }
  })
  lastMermaidTheme = theme
}

/** 拿到 view 容器内已渲染的 svg 字符串（供下载按钮复用）。 */
export function readMermaidSvg(): string {
  const el = document.querySelector('.mermaid-view svg') as SVGElement | null
  return el?.outerHTML || ''
}

export default function MermaidView({ source }: { source: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [error, setError] = useState('')
  const theme = document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light'
  /** 用于丢弃过期渲染结果，避免切 source 时慢的覆盖快的 */
  const tokenRef = useRef(0)

  useEffect(() => {
    if (!ref.current || !source) return
    ensureMermaid(theme)
    const myToken = ++tokenRef.current
    const id = 'mermaid-' + myToken + '-' + Math.random().toString(36).slice(2)
    mermaid.render(id, source)
      .then(({ svg }) => {
        if (tokenRef.current !== myToken) return // 过期
        if (ref.current) ref.current.innerHTML = svg
        setError('')
      })
      .catch((err) => {
        if (tokenRef.current !== myToken) return
        setError('Mermaid 渲染失败：' + (err?.message || String(err)))
      })
  }, [source, theme])

  if (error) return <div className="canvas-empty" style={{ color: 'var(--error-text)' }}>{error}</div>
  return <div ref={ref} className="mermaid-view" />
}
