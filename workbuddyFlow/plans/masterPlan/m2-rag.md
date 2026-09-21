# 模块计划 · RAG 检索增强（S6 更准·RAG）

> 状态：规划中
> 关联架构：`architecture.md` 2.4 包表（`rag` 允许调 `llm`）；向量存储由 **pgvector** 提供（基础设施见 M12，G6 分层存储的向量层）
> 所属阶段：**S6 更准·RAG**（执行序见 `plans/masterPlan/roadmap.md`）
> 覆盖任务：v2-12 ~ v2-14
> **决策落地（2026-09-20 G6）**：向量库 = **pgvector**（PostgreSQL 扩展）。`embedding` 表（vector 列 + 原文快照）由 **M5 在 diagram 落库时同步写入**（写入侧）；本模块（M2）负责**召回侧**（相似度检索 + 注入 prompt）。历史 IR + 用户提示词的语义向量即 RAG 知识源。

## 元信息
- 模块编号：M2
- 状态：规划中
- 创建：2026-09-11 + AI

## 目标
让生成能"带知识"，把结构化文档切分、向量召回、片段注入 prompt，提升出图准确率与一致性。

## 边界
- 允许新增：`rag/` 包（切分 / 向量化 / 召回）、`EmbeddingClient`
- 允许修改：`service` 的 prompt 组装（注入召回片段）
- 禁止改动：校验链路（M1）、前端（M6）

## 分层边界
落在 `rag/`；只向下调 `llm/`（EmbeddingClient 走网关）。不触碰 `graph` / `agent`。原因：RAG 是生成前的"素材准备"，与校验/Agent 解耦。向量**写入侧**归 M5（diagram 落库时触发），本模块只做召回与注入，避免 RAG 与持久化互相阻塞。

## 前置
- S1 已闭合（M1 出图链路 + M6a 渲染镜像层），出图效果可肉眼验证
- M1 v2-8 / v2-9 契约与校验可用
- `llm/` 网关已收口（ADR-3）
- **M12 容器底座就绪**（G6 硬前置）：pgvector 扩展由 `docker compose` 的 `pgvector/pgvector:pg16` 首启自动 `CREATE EXTENSION vector` 提供（见 `m12-docker-infra.md`）

## 步骤
1. v2-12 结构感知切分（标题路径 + 位置信息，保留文档层级）
2. v2-13 召回（`EmbeddingClient` + pgvector 相似度检索）：向量**写入侧**由 M5 在 diagram 快照时触发（写 `embedding` 表）；本步负责按查询语义从 pgvector 拉 top-k 相关片段（历史 IR / 提示词）
3. v2-14 命中片段注入 prompt（含来源标注，避免幻觉无据）

## 验收口径
1. 切分后片段保留层级上下文
2. 给定查询能经 pgvector 召回 top-k 相关片段（历史 IR / 提示词向量由 M5 写入）
3. 注入片段后出图引用可溯源（来源标注出现在 prompt / 日志）

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 一份含"登录流程"的文档 + 查询"登录" | 召回片段含登录相关章节 |
| 正常 | "参考我之前画的订单系统" | 经 pgvector 召回历史 diagram 向量，注入本轮 |
| 异常 | 空文档库查询 | 优雅降级（不注入 / 提示无知识） |

## 状态跟踪
- [ ] v2-12 结构感知切分
- [ ] v2-13 召回（pgvector 相似度检索，写入侧归 M5）
- [ ] v2-14 命中片段注入 prompt
