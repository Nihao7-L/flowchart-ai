# AGENTS.md — ChartFlow 项目地图

> 只做索引，不做百科全书：先读这里，再按下面的表去读对应文档。

## 项目一句话

ChartFlow：Spring Boot + 大语言模型（LLM，即 ChatGPT 这类模型）的 AI 图表生成器（流程图 / 思维导图 / 架构图）。

## 索引（每份只回答一类问题，按需读一份，不要全读）

| 想知道 | 读 |
|---|---|
| 项目是什么、什么阶段、已定决策 | `docs/agents/project.md` |
| 核心架构：包边界与调用方向 | `docs/agents/architecture.md` |
| 有哪些功能、各自做到哪一步 | `docs/agents/features.md` |
| 怎么编译 / 验证、环境坑、操作红线 | `docs/agents/engineering.md` |
| 一次任务怎么做（四拍流程） | `docs/agents/workflow.md` |
| 路线图与任务编号 | `docs/架构规划-v2.md` |
| 当前有哪些任务在跑 | `docs/exec-plans/active/` |
| 做过什么、证据是什么 | `docs/exec-plans/activeLog.md` |
| 已知欠债 | `docs/exec-plans/tech-debt-tracker.md` |

## 唯一硬要求

交付前必须跑 `run-verify.ps1` 并附上输出（用法见 `engineering.md` 第四节）。
