# AGENTS.md — ChartFlow 项目地图

> 只做索引，不做百科全书：先读这里，再按下面的表去读对应文档。

## 项目一句话

ChartFlow：Spring Boot + 大模型（LLM）的 AI 图表生成器（流程图/思维导图/架构图），支持"人类手绘 + AI 聊天"双通道协作编辑。

## 索引（每份只回答一类问题，按需读一份，不要全读）

| 想知道 | 读 |
|---|---|
| 项目是什么、哪一版、谁负责、什么状态 | `docs/Metadata.md` |
| 核心功能：能做什么、不能做什么、量级 | `docs/coreFun.md` |
| 工程约束（含日志规范）：红线与后果 | `docs/constraint.md` |
| 架构设计：组成、连接、决策、风险 | `docs/architecture.md` |
| 自反馈机制：怎么知道做得好并修正 | `docs/workflow.md` |
| 模块总计划（按核心模块拆分，v2-N 出处） | `plans/masterPlan/` |
| 进行中计划（来自模块总计划里的 v2-N，可多个） | `plans/underway/` |
| 一键验证脚本（提交前必跑） | `run-verify.ps1`（与本文件同目录） |
| 当前唯一活跃项（来自 underway，同一时刻仅 1 个） | `state/active/` |
| 做过的与证据（追加式） | `state/activeLog/<YYYY-MM-DD>.md` |
| 已完成的计划（模块总计划 / v2-N 子计划） | `state/completed/masterPlan/` · `state/completed/underway/` |

## 怎么用这份知识层（四拍 + 状态机，必读）

### 四拍

1. **开场**：人发"先读 AGENTS.md，按索引读完 docs，复述约束再动手"；Agent 输出约束复述 + 等任务。这一步替代"每次口头交代规矩"，是跨会话不失忆的唯一手段。
2. **开工前**：`plans/masterPlan/mN-<模块>.md` 是已写好的**总计划**（一个模块内含多个 v2-N 子计划，六段结构见 `plans/masterPlan/TEMP.md`），不要重写它。要推进某个 v2-N 子计划时，把它作为**进行中计划**放进 `plans/underway/`（`v2-N-<短名>.md`，可多个并行），写明这一步具体做什么、验收口径是什么，人确认后才动码。
3. **执行中**：改码 → 跑 `run-verify.ps1` → 往 `state/activeLog/<YYYY-MM-DD>.md` 追加一行（时间 / 任务 / 动作 / 证据）；不过就自改，不問人。
4. **收尾**：活跃项从 `state/active/` 移出 → 计划按来源移 `state/completed/masterPlan/`（模块总计划）或 `state/completed/underway/`（v2-N 子计划）→ 更新 `plans/masterPlan/` 对应模块计划状态行 → 改动交用户提交（git 写操作由人执行）。

### 状态机（plans/masterPlan · plans/underway · state/active · state/completed · state/activeLog）

- 每个计划文件顶部状态行：`规划中 | 进行中 | 阻塞 | 完成`。
- `plans/masterPlan/` 放**模块总计划**：一个核心模块一个文件 `mN-<模块>.md`，**一个模块内含多个 v2-N 子计划**（各自带状态行）；模块**整体完成**后移 `state/completed/masterPlan/`（`m0-harness.md` 已于 2026-09-14 归档）。
- `plans/underway/` 放**进行中计划**：来自 masterPlan 里某个 v2-N 子计划的工作台副本，**可多个并行**；不限定数量；完成后移 `state/completed/underway/`。
- `state/active/` 放**最活跃的那一个**（从 underway 提升而来），同一时刻**只保留 1 个文件**（进入活跃态移入、离开移出）；空目录表示当前无活跃项。
- `state/activeLog/` 按日期分文件、**只追加不修改**，格式 `MM-DD HH:mm 做了什么 → 证据`；多 Agent 并行天然不冲突。
- 会话被压缩 / 换新会话：读 `state/activeLog/` 尾部几行 + `plans/masterPlan/` 状态行即可续上。

## 提交与分支约定

- `main` = 稳定可运行版；开发在 feature 分支（或继续用 `reframing`），完成后 merge 回 main。
- commit message：`feat|fix|chore|docs: 动词开头的中文描述`。
- **提交前必须 run-verify 绿**——脚本 `run-verify.ps1` 与本文件同目录（`workbuddyFlow/run-verify.ps1`），本地跑 `powershell -File run-verify.ps1`（`-SkipFrontend` 跳过前端检查；`-FrontendInstallMode ci|install` 选前端安装方式，**本地默认 `install`** 增量不删目录，CI 传 `ci` 严格按 lockfile）；这条同时约束人和 Agent。
- **云端门禁**：`.github/workflows/ci.yml`（GitHub Actions）与 `run-verify.ps1` 是**同一套三步门禁的两个执行器**——push 任意分支即自动跑（并行的 backend / frontend 两个 job），**改任一处必须同步另一处**，否则出现「本地绿、云端红」；日志正文需登录 GitHub 才能看，运行状态与耗时可匿名读 API（详见 `docs/architecture.md` 7.1）。
- `application.yml`（含明文 key）/ 阶段总结与规划类文档永远本地保留不入库（`.gitignore` 已固化）。
