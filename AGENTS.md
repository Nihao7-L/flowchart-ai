# AGENTS.md — ChartFlow 项目地图

> 这是一张地图，不是说明书。先读这里，再按索引去读对应文档。
> 任何开发（人 / AI Agent）动代码前，必须读完本文件 + 它指向的文档。

## 这是什么项目

基于 Spring Boot + 大语言模型（LLM，即 ChatGPT 这类模型）的 AI 图表生成器：自然语言描述需求 → 后端调 LLM 产出结构化图 JSON → 校验 → 渲染为流程图 / 思维导图 / 架构图。
当前在 `reframing` 分支做 v2 重构：后端推倒重来、React 前端保留但 UI 重做、工程底座先行。
路线图与已定决策的唯一出处：`docs/架构规划-v2.md`

## 去哪找什么（一次只读一个，别全读）

| 想知道 | 读 |
|---|---|
| 项目是什么、包边界、哪些不许动 | `docs/agents/project.md` |
| 怎么编译 / 启动 / 验证、环境坑、红线 | `docs/agents/engineering.md` |
| 一次任务怎么做（读→计划→执行→验证→归档） | `docs/agents/workflow.md` |
| 当前有哪些任务在跑 | `docs/exec-plans/active/` |
| 做过什么、证据是什么 | `docs/exec-plans/activeLog.md` |
| 已知欠债 | `docs/exec-plans/tech-debt-tracker.md` |

## 三条硬规矩

1. 动手前先读 `project.md`（边界与不变式）与 `engineering.md`（红线）；跨包调用方向不得违反 `project.md` 第三节
2. **没跑 `run-verify.ps1` 的代码，不得说"完成"**——交付必须附命令输出
3. 改完必须往 `docs/exec-plans/activeLog.md` 追加一行：时间 / 任务号 / 做了什么 / 证据

## 不改的东西

`application.yml`（含明文 key，永不入库，本地保留是用户明确决定）、`.workbuddy/`、`docs/阶段总结与规划.md`（本地台账，不入库）
