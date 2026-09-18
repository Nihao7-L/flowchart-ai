# 模块计划 · 前端双通道交互层（M6b · S3 / S4）

> 状态：进行中（v2-24b 的画布现状回写已在 S1 提前交付；余 v2-25 / v2-26）
> 关联架构：`architecture.md` 2.1 运行时视图、2.2 数据视图、ADR-1 / ADR-2 / ADR-4
> 所属阶段：**S3 能改**（v2-24b / v2-25）→ **S4 会规划**（v2-26）（执行序见 `plans/masterPlan/roadmap.md`）
> 覆盖任务：v2-24（`onChange` 回写部分）、v2-25、v2-26

## 元信息
- 模块编号：M6b（由原 M6 拆分；含人与 AI 双向改图 + 过程可视化）
- 状态：进行中
- 创建：2026-09-14 + AI（拆分自 `m6-frontend.md`，原文件已删）

## 目标
让"人能改、AI 也能改、两条路不互相覆盖"成立，并把 Agent 的思考过程可视化。

## 边界
- 允许新增：`onChange` 画布现状回写（`PUT /api/model/canvas`）、双通道 `renderScene` 重绘、SSE 事件驱动的进度 / 统计 UI
- 允许修改：前端调用 `POST /api/chat`（SSE）、`PUT /api/model/canvas`
- 禁止改动：后端（M1~M5 已定），只消费其接口

## 分层边界
落在 `frontend/`；与后端 `controller` / `session` 通过 REST + SSE 交互。原因：前端是渲染镜像持有者，真相源仍在后端（ADR-2）。

## 前置
- M6a 渲染镜像层可用（S1）
- v2-24b / v2-25 需 M1 的 `POST /api/chat` + **内存版 session**（M1 交付），`PUT /api/model/canvas` 亦由 M1 的内存 session 提供（M5 补 Redis 持久化）
- v2-25 另需 **M3 Diagram Tools**（S3）
- v2-26 需 **M4 Agent 事件流**（S4）

## 步骤
1. v2-24b 人类拖拽 `onChange` → 画布现状同步 → `PUT /api/model/canvas` 回写后端 model（**S3**；回写部分已于 S1 随 v2-24a 缺陷 #4 / #8-C 与 v2-34 提前交付）
2. v2-25 双通道：AI 聊天命令 → 后端 Diagram Tools（自研 MCP）→ **增量 `ops`**（`ops` 协议归这里；S1 只走全量 `result`）→ `renderScene` 重绘（**S3**）
3. v2-26 SSE 过程可视化 + Token 统计 + 错误明细展示（**S4**）

## 验收口径
1. AI 改图与人类拖拽汇同一份后端 model，**互不覆盖**
2. 用户手绘 / 拖拽后刷新，坐标已回写后端（非仅前端内存）
3. 增量 `ops`（v2-25 交付）与全量 `result`（S1 已有）都经 `renderScene` 正确落地
4. SSE 事件能实时驱动进度条 / token 统计

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 双通道 | 用户拖拽 + AI 加节点 | 两者都落在同一 model，用户手绘不丢 |
| 回写 | 拖动节点后刷新 | 后端坐标已更新 |
| 过程 | "画一个电商系统架构" | 界面看到思考 / 工具 / 校验事件逐步推进 |

## 状态跟踪
- [x] v2-24b 画布现状回写（坐标 / 删除 / 手绘 / 解绑）—— **已于 S1 提前交付**（v2-24a 缺陷 #4 就地收敛、#8 修复 C 关掉 `onChange` 刷屏；v2-34 把它从只回写坐标升级为 `PUT /api/model/canvas` 现状同步），无需再等到 S3
- [ ] v2-25 双通道改图（S3）
- [ ] v2-26 SSE 可视化（S4）
