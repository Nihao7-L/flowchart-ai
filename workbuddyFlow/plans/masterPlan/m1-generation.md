# 模块计划 · 核心生成链路（阶段1）

> 状态：规划中
> 关联架构：`architecture.md` 2.4 包表（`controller` / `service` / `llm` / `graph`）、ADR-3 / ADR-4
> 覆盖任务：v2-8 ~ v2-11

## 元信息
- 模块编号：M1
- 状态：规划中
- 创建：2026-09-11 + AI

## 目标
把"自然语言 → 结构化图 IR"的主链路做扎实：契约固化、端到端校验、生成→校验→修正闭环、SSE 进度协议定型。

## 边界
- 允许新增：`schemas/` 目录、`GraphValidator`、SSE 事件类型定义
- 允许修改：`service` 生成编排、`controller` 接口、`llm` 调用
- 禁止改动：前端渲染层、会话层（属 M5 / M6）

## 分层边界
落在 `controller` / `service` / `llm` / `graph` 包；不触碰 `rag` / `tools` / `agent` / `session`（后续阶段）。原因：本模块只解决"单次出图正确"，不引入检索/工具/Agent。

## 前置
- M0 v2-3 run-verify 可用
- 后端基线可编译

## 步骤
1. v2-8 出图 JSON Schema 契约固化（`schemas/` 目录，prompt 组装与解析共用同一份契约）
2. v2-9 `GraphValidator`：端到端校验，issue 带 `field / reason / hint`
3. v2-10 生成 → 校验 → 修正循环（最多 3 轮，修正 prompt 携带 issues 重调）
4. v2-11 SSE 进度事件协议定型（事件类型文档化，前后端共同遵守）

## 验收口径
1. 任意合法 NL 输入产出 100% 符合 Schema 的 IR，否则带 issues 重试
2. 校验失败明确定位 `field` 并给 `hint`，不只报数量
3. 连续 3 轮不通过自动升级人工 / 记 tech-debt
4. 前端能按定型后的 SSE 事件类型解析进度

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | "画一个登录流程图" | 合法 IR，校验 0 issue |
| 异常 | 让 LLM 返回缺字段的 JSON | 校验报 field 级 issue，带 hint |
| 边界 | 返回自环边 / 孤立节点 | 校验识别并给 reason |

## 状态跟踪
- [ ] v2-8 JSON Schema 契约
- [ ] v2-9 GraphValidator
- [ ] v2-10 生成→校验→修正循环
- [ ] v2-11 SSE 事件协议
