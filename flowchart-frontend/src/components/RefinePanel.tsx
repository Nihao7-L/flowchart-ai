import { useState } from 'react'

/**
 * 任务45：画布编辑回流面板
 * 显示在画布底部（生成结果之上），让用户在"已有图"基础上输入修改指令。
 * 按钮改成圆形 icon 按钮，与主输入框发送按钮风格统一。
 */
export default function RefinePanel({
  instruction,
  setInstruction,
  onRefine,
  onStop,
  loading,
  disabled
}: {
  instruction: string
  setInstruction: (v: string) => void
  onRefine: () => void
  onStop: () => void
  loading: boolean
  disabled: boolean
}) {
  const [focused, setFocused] = useState(false)
  return (
    <div className={'refine-panel' + (focused ? ' focused' : '')}>
      <div className="refine-panel-row">
        <span className="refine-panel-icon" title="在现有图上修改">✨</span>
        <textarea
          className="refine-panel-input"
          placeholder="让 AI 继续优化，如：加一个验证码步骤 / 把支付改成异步"
          value={instruction}
          disabled={loading}
          onChange={e => setInstruction(e.target.value)}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); if (!disabled && instruction.trim()) onRefine() } }}
          rows={1}
        />
        {loading ? (
          <button className="refine-panel-btn stop" onClick={onStop} title="停止">■</button>
        ) : (
          <button
            className="refine-panel-btn"
            onClick={onRefine}
            disabled={disabled || !instruction.trim()}
            title={disabled ? '请先生成一张图' : '在现有图基础上让 AI 修改'}
          >➤</button>
        )}
      </div>
    </div>
  )
}
