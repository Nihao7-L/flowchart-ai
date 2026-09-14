# 模块计划 · 前端渲染镜像层（M6a · S1 看得见）

> 状态：规划中
> 关联架构：`architecture.md` 2.1 运行时视图、2.2 数据视图、ADR-1 / ADR-2、七（PoC 原型路径）
> 所属阶段：**S1 看得见**（执行序见 `plans/masterPlan/roadmap.md`）
> 覆盖任务：v2-23、v2-24（只读渲染部分）

## 元信息
- 模块编号：M6a（由原 M6 拆分而来，只含"IR → 画布"的只读渲染）
- 状态：规划中
- 创建：2026-09-14 + AI（拆分自 `m6-frontend.md`，原文件已删）

## 目标
让"说一句话，图上屏"成立：设计稿确认 → 嵌入 `@excalidraw/excalidraw` → 建 model/IR 状态层 → `layout` 算坐标 → `convert` 渲染 → 消费后端 SSE 的 `ops` / `spec` 更新镜像。

## 边界
- 允许新增：`@excalidraw/excalidraw` 嵌入、model/IR 状态层（渲染镜像）、`layout` / `convert` 渲染管线、SSE 消费（**只读**）
- 允许修改：前端调用 `POST /api/chat`（SSE）
- 禁止改动：后端（M1 已定），只消费其接口；**不含** `onChange` 坐标回写（归 M6b①）

## 分层边界
落在 `frontend/`；只与后端 `controller` 通过 SSE 交互。原因：前端是渲染镜像持有者，真相源仍在后端（ADR-2）。

## 前置
- [x] M0 v2-6 前端脚手架空壳可跑（09-12 已完成：`frontend/` Vite + React 18 + TS strict + ESLint 空壳，npm ci / build / lint / dev 均通过）
- M1 v2-11 的 SSE 事件协议定型（M6a 消费它；协议未定型前 M6a 不动工）
- PoC 原型路径：`excalidraw-prototype/excalidraw_collab.html`（已实测，作参考）

## 步骤
1. v2-23 Excalidraw 嵌入设计稿（色板 / 布局 / 组件规范）→ 用户确认
2. v2-24a 画布嵌入 `@excalidraw/excalidraw` + model/IR 状态层 + `layout` 布局 + `convert` 渲染 + 消费 SSE 的 `ops` / `spec` 更新镜像

## 验收口径
1. 设计稿经用户确认后再实现（先设计后编码）
2. 初始渲染无 NaN 视口、选中不丢绘制能力（PoC 已验证的坑不再犯，见 `constraint.md` 技术约束）
3. 输入"画一个登录流程图"→ 画布出现正确的图；再说"再加一个审批节点"→ 图上多一个（SSE 增量生效）
4. 后端 `spec` 全量替换时镜像正确重建，坐标由 `layout` 算出

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 初始加载 | 渲染 N 元素、零 NaN、可继续绘制 |
| 增量 | 第二次对话"再加一个节点" | `ops` 增量应用，已有元素不重排 |
| 整图 | "换一个复杂的" | 全量 `spec` 替换重绘，坐标由 layout 算 |

## 状态跟踪
- [ ] v2-23 设计稿确认
- [ ] v2-24a 画布嵌入 + model/IR + layout/convert + 消费 SSE
