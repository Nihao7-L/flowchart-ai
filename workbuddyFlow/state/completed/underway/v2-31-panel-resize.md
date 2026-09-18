# v2-31 三栏可拖拽分割线（面板宽度可调）

> 状态：**完成**（已落盘，前端 tsc/lint/build 三绿；门禁证据见 activeLog 2026-09-16）。
> 本计划对应 AGENTS.md 第三拍「用户提的三项界面问题」之③「三大区域不能用分割线拖拽来改变大小」——
> 用户 09-16 明确选「要能拖拽调整宽度」，故立此新功能计划。
> 命名说明：masterPlan 的 `v2-24b` 已被「拖拽画布元素回写后端坐标（M6b① / S3）」占用，本面板缩放功能改用 v2-31 以免编号冲突。

## 元信息
- 任务编号：v2-31
- 状态：完成（2026-09-16 落盘，门禁全绿）
- 来源模块：M6a（render 渲染层）；纯前端 UI，不跨模块
- 创建：2026-09-16 + AI（按用户决定起草并实施）

## 目标
让「左导航栏 / 中间画布 / 右聊天栏」之间的两道分割线可拖拽，改变左右两栏宽度；中栏 `flex:1` 自动填充剩余空间。

## 边界
- 新增：分割线逻辑、宽度 state（leftWidth / rightWidth）、上下限 clamp 约束。
- 修改：`App.tsx` 布局（加分割线 + 受控宽度）、`App.css`（分割线样式）、`Sidebar.tsx` / `ChatPanel.tsx` 接收 `style` 透传宽度。
- 禁止改动：后端 IR / 真相源（session / graph / service 任何包）；Scene 数据契约；Excalidraw 画布内部。

## 分层边界
纯前端 UI 层（`frontend/src/`），不触碰任何后端包。宽度仅存在于浏览器内存，不进后端、不进 SessionStore、不进 SSE ops/spec（若未来做记忆也只落 localStorage，不回传后端）。

## 实现记录（2026-09-16）
- `App.tsx`：加 `leftWidth`(默认 240) / `rightWidth`(默认 340) state；`dragRef` + `onSplitterDown / onSplitterMove / onSplitterUp` 三个 handler，用 **pointer 事件 + `setPointerCapture`**（拖出窗口也不丢 move/up）；`DRAG_LIMITS` 左 [200,420] / 右 [280,600]；`Sidebar` 与 `ChatPanel` 之间各插一道 `.splitter` div，`onPointerDown` 时给 `document.body` 加 `user-select:none`，`onPointerUp` 后移除。
- `App.css`：新增 `.splitter`（宽 6px、`cursor:col-resize`、`::after` 1px 分隔线、hover/active 高亮蓝、`touch-action:none` 防触摸滚动）；移除 `.sidebar` 的 `border-right` 与 `.chat-panel` 的 `border-left`（分隔视觉改由分割线承担）。
- `Sidebar.tsx` / `ChatPanel.tsx`：接口加 `style?: React.CSSProperties`，根部 `div` 接收 `style`，宽度由父级 `App` 受控传入。
- 门禁：前端 `tsc -b` / `npm run lint` / `npm run build` 三者 exit 0（build 14.38s，仅 Excalidraw 大包体积提示，非错误）；后端 `clean test` 32 测试 0 失败 + `checkstyle:check` 0 违规。

## 验收口径（待用户 `npm run dev` 视觉验收）
1. 悬停分割线显示 `col-resize` 光标。
2. 拖左分割线仅改左栏宽度；拖右分割线仅改右栏；中栏自适应、互不影响。
3. 左栏 clamp [200,420]、右栏 [280,600]，拖出范围不再变化。
4. 拖拽中鼠标移出窗口仍持续更新，`pointerup`（含窗口外）才结束。
5. 拖拽全程画布（Excalidraw）交互、画图、对话正常，无 console error。

## 待拍板（已按建议实施）
- 上下限：左 200–420 / 右 280–600（默认 240/340 落在范围内）。
- 是否 localStorage 记忆宽度：先不做。
- 是否双击分割线复位到默认宽：先不做。

## 完成后
- 状态行：完成。
- 归档：已移入 `state/completed/underway/`。
- activeLog：已追加 2026-09-16 实现记录。
