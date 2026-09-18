# 模块计划 · 前端渲染镜像层（M6a · S1 看得见）

> 状态：**完成**（2026-09-18 S1 闭合；"布局在后端"由 v2-36 兑现后本模块无剩余项）
> 归档：2026-09-18 由 `plans/masterPlan/` 移入 `state/completed/masterPlan/`（原始内容未回改）
> 完成结论：设计稿 → 画布嵌入 → 适配层 → SSE 全量 `result` 重建镜像全部落地，前端**不做任何布局**（决定 A2）；两条遗留（前端兜底布局、长流程竖长条）由 v2-36 关闭。终态证据：tsc / eslint / vite build 三绿、`scene-smoke.mjs` 10/10、真实大模型端到端截图见 `state/activeLog/2026-09-18.md`
> 关联架构：`architecture.md` 2.1 运行时视图、2.2 数据视图、ADR-1 / ADR-2 / **ADR-4（v2-8 起 IR=元素场景，见 `scene.schema.json`）**、七（PoC 原型路径）
> 所属阶段：**S1 看得见**（执行序见 `plans/masterPlan/roadmap.md`）
> 覆盖任务：v2-23、v2-24（只读渲染部分）

## 元信息
- 模块编号：M6a（由原 M6 拆分而来，只含"IR → 画布"的只读渲染）
- 状态：完成
- 创建：2026-09-14 + AI（拆分自 `m6-frontend.md`，原文件已删）
- 归档：2026-09-18（S1 闭合，模块整体完成）

## 目标
让"说一句话，图上屏"成立：设计稿确认 → 嵌入 `@excalidraw/excalidraw` → 建元素场景(含坐标)状态层 → `convert` 渲染（坐标由 IR 携带，**布局在后端** —— 自 S1 起由 v2-36 交付，前端不做任何布局） → 消费后端 SSE 的**全量** `result` 重建镜像。

## 边界
- 允许新增：`@excalidraw/excalidraw` 嵌入、model/IR 状态层（渲染镜像）、IR → 画布的 `convert` 适配管线、SSE 消费（**只读**）
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
2. v2-24a 画布嵌入 `@excalidraw/excalidraw` + model/IR 状态层 + `convert` 渲染 + 消费 SSE 的 `result`（全量）更新镜像

## 验收口径
1. 设计稿经用户确认后再实现（先设计后编码）
2. 初始渲染无 NaN 视口、选中不丢绘制能力（PoC 已验证的坑不再犯，见 `constraint.md` 技术约束）
3. 输入"画一个登录流程图"→ 画布出现正确的图；再说"再加一个审批节点"→ 图上多一个（**全量 `result` 替换生效**；增量 `ops` 归 S3 / v2-25）
4. 后端 `result`（元素场景，含坐标）全量替换时镜像正确重建；坐标**只用** IR 携带值 —— 前端不做布局（布局是后端 `graph/` 的职责，见 v2-36）

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 初始加载 | 渲染 N 元素、零 NaN、可继续绘制 |
| 二次对话 | 第二次对话"再加一个节点" | 收到全量 `result` 重建镜像；用户手绘保留（只替换带 AI 标记的元素） |
| 整图 | "换一个复杂的" | 全量 `result` 替换重绘，坐标由后端布局（v2-36）算出 |

## 状态跟踪
- [x] v2-23 设计稿确认（2026-09-16，三栏布局：左导航240px | 中画布flex:1 | 右聊天340px，白色背景）→ **归档待做**（S1 闭合时与 v2-24a 一起移入 `state/completed/underway/`；当前仍在 `plans/underway/`）
- [x] v2-24a 画布嵌入 + model/IR + convert 适配层 + 消费 SSE `result`（2026-09-16 交付；验收期缺陷 8~12 全修；用户端到端实测出图）
- [x] 补记 v2-34 / v2-35 前端侧（2026-09-16~17）：用户手绘上报（positions / removedIds / userElements / unboundArrows）、场景合并与视口取景修复、`onChange` 收敛为防抖 + 仅 AI 元素
- [x] **布局不归前端**（2026-09-17 决定 A2）：原交付项"前端 `layout` 仅兜底增量"**删除** —— 布局统一归后端 `graph/`（`architecture.md` 2.4 包表、ADR-4），**已由 v2-36 交付**（`graph/SceneLayout`）；前端只消费坐标
- [x] v2-24a 第 154 行遗留（长流程被排成竖长条、zoom 掉到 0.2）→ **已由 v2-36 的纵横比约束关闭**（`SceneLayout` 目标区间 0.5~2.0，仅当翻向确实更优才换主方向）
