# 模块计划 · 前端双通道白板（阶段6）

> 状态：规划中
> 关联架构：`architecture.md` 2.1 运行时视图、2.2 数据视图、ADR-1 / ADR-2、七（PoC 原型路径）
> 覆盖任务：v2-23 ~ v2-26

## 元信息
- 模块编号：M6
- 状态：规划中
- 创建：2026-09-11 + AI

## 目标
把 PoC 实测通过的"Excalidraw 双通道"方向落进 `frontend/` 脚手架：设计稿确认 → 画布嵌入 → 双通道改图 → SSE 可视化。

## 边界
- 允许新增：`frontend/` 脚手架、`@excalidraw/excalidraw` 嵌入、model/IR 状态层、`layout` / `convert` 渲染、`onChange` 回写
- 允许修改：前端调用 `POST /api/chat`（SSE）
- 禁止改动：后端（M1~M5 已定），只消费其接口

## 分层边界
落在 `frontend/`；只与后端 `controller` / `session` 通过 REST + SSE 交互。原因：前端是渲染镜像持有者，真相源仍在后端（ADR-2）。

## 前置
- [x] M0 v2-6 前端脚手架空壳可跑（09-12 已完成：`frontend/` Vite + React 18 + TS strict + ESLint 空壳，npm ci / build / lint / dev 均通过）
- 后端 `POST /api/chat` + `PATCH /api/model/positions` 接口（M1/M5 提供）
- PoC 原型路径：`excalidraw-prototype/excalidraw_collab.html`（已实测，作参考）

## 步骤
1. v2-23 Excalidraw 嵌入设计稿（色板 / 布局 / 组件规范，含双通道交互）→ 用户确认
2. v2-24 画布嵌入 `@excalidraw/excalidraw` + model/IR 状态层 + `layout` 布局 + `convert` 渲染 + `onChange` 回写坐标
3. v2-25 双通道：AI 聊天命令 → 后端 Diagram Tools（自研 MCP）→ 增量 `ops` / 全量 `spec` → `renderScene` 重绘
4. v2-26 SSE 过程可视化 + Token 统计 + 错误明细展示

## 验收口径
1. 设计稿经用户确认后再实现（先设计后编码）
2. 初始渲染无 NaN 视口、选中不丢绘制能力（PoC 已验证的坑不再犯，见 `constraint.md` 技术约束）
3. AI 改图与人类拖拽汇同一份后端 model，互不覆盖
4. SSE 事件能实时驱动进度条 / token 统计

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 初始加载 | 渲染 N 元素、零 NaN、可继续绘制 |
| 双通道 | 用户拖拽 + AI 加节点 | 两者都落在同一 model，用户手绘不丢 |
| 整图 | "换一个复杂的" | 全量 spec 替换重绘，坐标由 layout 算 |

## 状态跟踪
- [ ] v2-23 设计稿确认
- [ ] v2-24 画布嵌入 + model/IR + 渲染 + 回写
- [ ] v2-25 双通道改图
- [ ] v2-26 SSE 可视化
