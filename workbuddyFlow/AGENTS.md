# AGENTS.md — ChartFlow 项目地图

> 只做索引，不做百科全书：先读这里，再按下面的表去读对应文档。

## 项目一句话

ChartFlow：Spring Boot + 大模型（LLM）的 AI 图表生成器（流程图/思维导图/架构图），支持"人类手绘 + AI 聊天"双通道协作编辑。

## 索引（每份只回答一类问题，按需读一份，不要全读）

| 想知道 | 读 |
|---|---|
| 项目是什么、哪一版、谁负责、什么状态 | `docs/project.md` |
| 核心功能：能做什么、不能做什么、量级 | `docs/features.md` |
| 工程约束：红线与它们导致的后果 | `docs/engineering.md` |
| 架构设计：组成、连接、决策、风险 | `docs/architecture.md` |
| 自反馈机制：怎么知道做得好并修正 | `docs/workflow.md` |
| 路线图与任务编号 | `../架构规划-v2.md` |
| 当前在跑的任务 | `../exec-plans/active/` |
| 做过的与证据 | `exec-plans/activeLog.md` |
| 已知欠债 | `exec-plans/tech-debt-tracker.md` |

## 怎么用这份知识层（四拍 + 状态机，必读）

### 四拍

1. **开场**：人发"先读 AGENTS.md，按索引读完 docs，复述约束再动手"；Agent 输出约束复述 + 等任务。这一步替代"每次口头交代规矩"，是跨会话不失忆的唯一手段。
2. **开工前**：在 `../exec-plans/active/vX-N.md` 写六项（目标/边界/前置/步骤/验收口径/覆盖测试数据），人确认后才动码。
3. **执行中**：改码 → 跑 run-verify → 往 `exec-plans/activeLog.md` 追加一行（时间/任务/动作/证据）；不过就自改，不問人。
4. **收尾**：计划移 `exec-plans/completed/` → 勾 `架构规划-v2.md` → 改动交用户提交（git 写操作由人执行）。

### 状态机（active / completed / activeLog）

- 每个计划文件顶部状态行：`待审 | 进行中 | 阻塞 | 完成`。
- `active/` 放进行中任务（一个任务一个文件 `vX-N-短名.md`）；完成移 `completed/`（原计划不改，顶部加结论）。
- `activeLog.md` **只追加不修改**，格式 `MM-DD HH:mm vX-N 做了什么 → 证据`；多 Agent 并行天然不冲突。
- 会话被压缩/换新会话：读 `activeLog.md` 尾部几行 + `active/` 状态行即可续上。

## 唯一硬要求

交付前必须跑 `run-verify.ps1` 并附上输出。
