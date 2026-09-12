# 模块计划 · 可观测与治理（阶段7）

> 状态：规划中
> 关联架构：`architecture.md` 2.4 包表（`infra`）、四（横切关注点）、`constraint.md` 六、日志规范
> 覆盖任务：v2-27 ~ v2-29

## 元信息
- 模块编号：M7
- 状态：规划中
- 创建：2026-09-11 + AI

## 目标
补齐可观测与治理：调用链 Trace、LLM 网关审计与 token 预算、阶段收尾全库扫描，让"自反馈闭环"从人工外置记忆升级为系统级可观测。

## 边界
- 允许新增：`infra/` 包（TraceService、metrics、`/api/metrics`）、审计日志（按 `constraint.md` 六 日志规范）
- 允许修改：`llm/` 网关（加审计与 token 计量）
- 禁止改动：业务功能（M1~M6 已定）

## 分层边界
落在 `infra/`；被全栈调用，不反向依赖业务包。原因：可观测是横切关注点，必须零业务耦合。

## 前置
- M1~M6 各模块落地
- 日志规范已生效（见 `constraint.md` 六）

## 步骤
1. v2-27 `TraceService` 调用链 + `/api/metrics`
2. v2-28 LLM 网关审计与 token 预算（按日志规范打点，超阈值降级）
3. v2-29 阶段收尾扫描（对照 `constraint.md` 全库清理违规）

## 验收口径
1. 一次请求能串联 traceId 全链路日志（按 `constraint.md` 六 字段/事件命名）
2. token 消耗可计量、超预算触发降级
3. v2-29 扫描能自动找出并报告违规代码

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 一次出图请求 | 日志含 ts/level/service/module/traceId/event，可被 grep 聚合 |
| 异常 | token 超阈值 | 降级小模型，审计日志记录 event=llm.timeout / budget.exceeded |

## 状态跟踪
- [ ] v2-27 TraceService + metrics
- [ ] v2-28 LLM 网关审计 + token 预算
- [ ] v2-29 阶段收尾扫描
