import { useEffect, type ReactNode } from 'react'

/**
 * 通用右侧抽屉：顶栏有标题 + 关闭按钮，主体滚动。
 * 点遮罩或按 Esc 关闭（点关闭按钮也关）。
 */
export default function SidePanel({
  open,
  title,
  onClose,
  children,
  badge
}: {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  badge?: string | number
}) {
  useEffect(() => {
    if (!open) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  return (
    <>
      {open && <div className="side-panel-mask" onClick={onClose} />}
      <aside className={'side-panel' + (open ? ' open' : '')} aria-hidden={!open}>
        <header className="side-panel-header">
          <span className="side-panel-title">{title}</span>
          {badge !== undefined && <span className="side-panel-badge">{badge}</span>}
          <button className="side-panel-close" onClick={onClose} title="关闭（Esc）">×</button>
        </header>
        <div className="side-panel-body">{children}</div>
      </aside>
    </>
  )
}
