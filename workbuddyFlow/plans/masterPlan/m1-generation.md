# 模块计划 · 核心生成链路（S1 看得见）

> 状态：进行中
> 关联架构：`architecture.md` 2.4 包表（`controller` / `service` / `llm` / `graph`）、ADR-3 / ADR-4
> 所属阶段：**S1 看得见**（与 M6a 同阶段交付；执行序见 `plans/masterPlan/roadmap.md`）
> 关联前置：`plans/underway/m1-pre-cleanup.md`（旧链路代码清算，已完成）
> 覆盖任务：v2-8 ~ v2-11

## 元信息
- 模块编号：M1
- 状态：进行中
- 创建：2026-09-11 + AI

## 目标
把"自然语言 → 结构化图 IR"的主链路做扎实：契约固化、端到端校验、生成→校验→修正闭环、SSE 进度协议定型；并交付**最小会话上下文（内存版）**，使 `/api/chat` 的多轮对话成立（Redis 持久化归 M5）。

## 边界
- 允许新增：`schemas/` 目录、`GraphValidator`、SSE 事件类型定义、`session/` 包（**仅内存实现**：`SessionStore` 接口 + 内存实现 + `PATCH /api/model/positions` 坐标回写入口；Redis 版归 M5）
- 允许修改：`service` 生成编排、`controller` 接口、`llm` 调用
- 允许删除：与目标架构无关的旧链路代码（前置清算已完成，见关联前置文件）
- 禁止改动：前端渲染层（属 M6a / M6b）；`session/` 只允许新增内存实现，**不得引入 Redis 或持久化**（归 M5）

## 分层边界
落在 `controller` / `service` / `llm` / `graph` 包，并新增 `session/` 的**内存实现**；不触碰 `rag` / `tools` / `agent`（后续阶段）。原因：本模块只解决"单次出图正确 + 多轮上下文"，不引入检索 / 工具 / Agent，也不做持久化。

## 前置
- M0 v2-3 run-verify 可用
- 后端基线可编译
- 旧链路已清算（2026-09-14）：`static/`、`DiagramService`、`plantuml` 依赖、`/api/generate`、`/api/download`、两个旧请求 record 均已删除

## 步骤
0. 前置 · 旧链路代码清算（**已完成** 2026-09-14，详见 `plans/underway/m1-pre-cleanup.md`）
1. v2-8 出图 JSON Schema 契约固化（`schemas/` 目录，prompt 组装与解析共用同一份契约）
2. v2-9 `GraphValidator`：端到端校验，issue 带 `field / reason / hint`
3. v2-10 生成 → 校验 → 修正循环（最多 3 轮，修正 prompt 携带 issues 重调）
4. v2-11 SSE 进度事件协议定型（事件类型文档化，前后端共同遵守）
5. v2-11 附带交付：**最小会话上下文**（`SessionStore` 接口 + 内存实现 + 坐标回写入口），支撑多轮对话（Redis 持久化归 M5）

## 验收口径
1. 任意合法 NL 输入产出 100% 符合 Schema 的 IR，否则带 issues 重试
2. 校验失败明确定位 `field` 并给 `hint`，不只报数量
3. 连续 3 轮不通过自动升级人工 / 记 tech-debt
4. 前端能按定型后的 SSE 事件类型解析进度
5. 第二轮对话（"再加一个节点"）后端能取到当前图上下文；`PATCH /api/model/positions` 可回写内存 model

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | "画一个登录流程图" | 合法 IR，校验 0 issue |
| 异常 | 让 LLM 返回缺字段的 JSON | 校验报 field 级 issue，带 hint |
| 边界 | 返回自环边 / 孤立节点 | 校验识别并给 reason |
| 多轮 | 第二轮说"再加一个节点" | 后端从内存 session 取到当前 model，返回增量 `ops` |

## 状态跟踪
- [x] 前置 · 旧链路代码清算（2026-09-14）
- [ ] v2-8 JSON Schema 契约
- [ ] v2-9 GraphValidator
- [ ] v2-10 生成→校验→修正循环
- [ ] v2-11 SSE 事件协议 + 内存会话上下文
