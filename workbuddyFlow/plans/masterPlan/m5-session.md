# 模块计划 · 会话持久化（S7 不丢）

> 状态：规划中
> 关联架构：`architecture.md` 2.4 包表（`session`）、数据视图（IR 真相源在 session）、约束 `constraint.md` §三
> 所属阶段：**S7 不丢**（执行序见 `plans/masterPlan/roadmap.md`）；内存版已在 **S1（M1）** 交付
> 覆盖任务：v2-21 ~ v2-23
> **决策落地（2026-09-20 用户拍板 G6 · 分层存储）**：Redis 热层 + PostgreSQL 冷层 + pgvector 向量层，见下方「数据结构」。

## 元信息
- 模块编号：M5
- 状态：规划中
- 创建：2026-09-11 + AI；2026-09-20 G6 分层存储重写

## 目标
把 M1（S1）交付的**内存版 session** 升级为**三层持久化**（G6）：
- **Redis 热层**：当前会话的实时 Model（快、TTL、重启不丢）。
- **PostgreSQL 冷层**：历史会话 / 消息 / IR 快照（长期留、重启还在）。
- **pgvector 向量层**：历史 IR + 用户提示词的语义向量（供 M2 RAG 召回）。
保留"连不上即降级"：Redis 挂 → 降级内存（热层丢、冷层不受影响）；PG 挂 → 冷层暂不可用、热层照常；pgvector 不可用 → RAG 召回降级、生成照常。**不再包含从零做抽象**——`SessionStore` 接口与内存实现已在 M1 落地。

## 边界
- 允许新增：`session/` 的 **Redis 热层实现** + **PostgreSQL 冷层实现**（三表 DAO）+ **pgvector 写入侧**（diagram 落库同步写 `embedding`）+ 探活自动降级切换；恢复 / 历史 API
- 允许复用：M1 已落地的 `SessionStore` 接口与内存实现（本模块只加实现，不改接口语义）
- 允许修改：`controller`（新增恢复 / 历史 API）
- 禁止改动：生成链路（M1）、Agent（M4）的循环判定逻辑
- **G6 写入侧归属**：`embedding` 表的写入（向量入库）由本模块在 diagram 快照时触发；**召回侧（相似度检索 + 注入 prompt）归 M2**（S6），二者解耦，避免 RAG 与持久化互相阻塞。

## 分层边界
落在 `session/`；被 `controller` / `service` / `agent`（M4 checkpoint 序列化格式即本模块输入契约）调用，不反向依赖业务包。原因：会话是横切基础设施，必须被任意层复用而不被业务污染。

## 前置
- **M1 v2-11 已交付内存版 session**（`SessionStore` 接口 + 内存实现 + 坐标回写入口），且自 S1 起已在真实链路里跑（多轮对话 + 人拖拽回写）
- M4 已把 IR 作为核心数据结构，且 checkpoint 格式（v2-56）即本模块分层持久化的输入契约
- **M12 容器底座就绪**（S6/S7 硬前置）：`docker compose` 起 `redis:7.4-alpine` + `pgvector/pgvector:pg16`，替代本机 Windows Redis 3.0；应用连接信息走提交版 `application-docker.yml`，密钥走 `.env`（见 `plans/masterPlan/m12-docker-infra.md`）
- Redis / PostgreSQL / pgvector 均为可选依赖，连不上降级（见 `constraint.md` §三）

## 数据结构（G6 三层）
### ① Redis 热层（当前会话）
- key：`flowai:session:<sessionId>` → 整个 Model（elements + userElements）
- 存：当前 IR、拖拽坐标、绑定 / 解绑几何、校验问题
- 特点：毫秒级读写、设 TTL（过期自动消失）、重启不丢（不像内存版）、有容量上限会淘汰（淘汰时冷层 PG 仍在）
- 探活：连不上 → 降级内存（热层丢，但冷层 PG 不受影响）

### ② PostgreSQL 冷层（历史会话，长期留）
- `session` 表：会话元信息（id / 用户 / 创建时间 / 更新时间）
- `message` 表：对话记录（session_id / role=user|assistant / content / created_at）
- `diagram` 表：IR 快照（session_id / version / json / created_at）—— 写一次、长期留、关掉浏览器 / 重启项目都还在

### ③ pgvector 向量层（RAG 知识源）
- `embedding` 表：`id` + `vector` 列（那串数字，pgvector 类型）+ `source_type`（diagram|prompt）+ `source_ref`（关联 diagram/message）+ `raw_text`（原文快照）
- 写：本模块在 `diagram` 落库时同步写该 diagram IR 的语义向量 + 关联用户提示词的语义向量
- 用：M2（S6）下次生成时按相似度搜出"以前画过类似的图"，塞给 LLM 做参考（RAG，检索增强生成）

## 步骤
1. **v2-21 Redis 热层实现 + 探活降级切换**：`SessionStore` 的 Redis 实现（接口与内存实现在 M1 已就位）；热层 key = `flowai:session:<sessionId>`，写当前 Model（IR + 坐标 + 校验问题），带 TTL；连不上自动降级内存。
2. **v2-22 PostgreSQL 冷层（三表 DAO）+ 恢复 / 历史 API**：`session`/`message`/`diagram` 三表；热层写入同时异步落冷层（写一次、长期留）；新增会话恢复 / 历史列表 API。
3. **v2-23 pgvector 写入侧**：diagram 落库（v2-22）时同步计算 IR 语义向量 + 关联提示词语义向量，写 `embedding` 表（召回侧归 M2）。

## 验收口径
1. Redis 可用时写入热层并可读回（ms 级）；不可用时自动降级内存不报错（热层丢、冷层不受影响）。
2. PostgreSQL 冷层：diagram 快照长期留，重启后能从 `diagram` 表恢复历史 IR；历史列表 API 返回该用户会话清单。
3. pgvector：diagram 落库时 `embedding` 表同步写入向量；`\dx` 见 `vector` 扩展（由 M12 初始化）。
4. 三层降级互不影响：Redis 挂→热层降级内存；PG 挂→冷层暂不可用、热层照常；pgvector 挂→RAG 召回降级、生成照常。
5. 降级切换对上层透明：同一套 `SessionStore` 接口在 Redis / PG / 内存多后端下行为一致。

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 写入 session → 重启读取 | 热层 Redis 命中 + 冷层 PG 兜底，model 完整恢复 |
| 异常 | 关停 Redis | 自动降级内存，写入/读取不抛错；冷层 PG 仍在 |
| 异常 | 关停 PostgreSQL | 热层照常、历史/恢复 API 暂不可用、生成不崩 |
| 向量 | diagram 落库 | `embedding` 表同步写入 IR + 提示词向量 |
| 召回 | "参考我之前画的订单系统" | M2（S6）经 pgvector top-k 召回，注入本轮（验收归 M2） |

## 状态跟踪
- [ ] v2-21 Redis 热层实现 + 探活降级切换（接口 / 内存实现已由 M1 交付）
- [ ] v2-22 PostgreSQL 冷层（session/message/diagram 三表）+ 恢复 / 历史列表 API
- [ ] v2-23 pgvector 写入侧（diagram 落库同步写 `embedding`）

## 完成后
移入 `state/completed/masterPlan/`；`state/activeLog/<YYYY-MM-DD>.md` 追加一行带证据。
