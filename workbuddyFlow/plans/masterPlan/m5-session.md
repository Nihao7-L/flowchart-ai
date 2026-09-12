# 模块计划 · 会话持久化（阶段5）

> 状态：规划中
> 关联架构：`architecture.md` 2.4 包表（`session`）、数据视图（IR 真相源在 session）
> 覆盖任务：v2-21 ~ v2-22

## 元信息
- 模块编号：M5
- 状态：规划中
- 创建：2026-09-11 + AI

## 目标
让 IR（图状态）在会话间持久化：Store 抽象 + Redis 实现 + 内存降级，并提供恢复 / 历史列表 API。

## 边界
- 允许新增：`session/` 包（`SessionStore` 抽象、Redis 实现、内存降级）
- 允许修改：`controller`（新增恢复 / 历史 API）
- 禁止改动：生成链路（M1）、Agent（M4）

## 分层边界
落在 `session/`；被 `controller` / `service` / `agent` 调用，不反向依赖业务包。原因：会话是横切基础设施，必须被任意层复用而不被业务污染。

## 前置
- M1 / M4 已把 IR 作为核心数据结构
- Redis 可选依赖可用（不可用时降级内存，见 `constraint.md` 资源约束）

## 步骤
1. v2-21 `SessionStore` 抽象 + Redis 实现 + 内存降级（探活自动切换）
2. v2-22 会话恢复 / 历史列表 API

## 验收口径
1. Redis 可用时写入并可读回；不可用时自动降级内存不报错
2. 重启后能从 session 恢复 model（IR）
3. 历史列表 API 返回该用户会话清单

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 写入 session → 重启读取 | model 完整恢复 |
| 异常 | 关停 Redis | 自动降级内存，写入/读取不抛错 |

## 状态跟踪
- [ ] v2-21 SessionStore + Redis + 内存降级
- [ ] v2-22 恢复 / 历史列表 API
