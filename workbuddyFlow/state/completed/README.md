# 已完成计划归档

任务收尾时，把计划文件从 `plans/` 移到这里，**保留"它原本来自哪一层"**：

| 原位置 | 归档到 |
|---|---|
| `plans/masterPlan/mN-<模块>.md`（模块总计划） | `state/completed/masterPlan/` |
| `plans/underway/v2-N-<短名>.md`（v2-N 子计划工作台副本） | `state/completed/underway/` |

- **来源层不能丢**：直接扔进 `completed/` 根目录，就看不出这份计划从哪来了。
- 归档时保留原始内容，只在顶部加一行完成结论（完成日期 + 证据指向 `state/activeLog/`）。
- **归档件不再回改**——它是"当时发生的事实"。其中的路径引用以现行规则文档为准（`AGENTS.md` 状态机）。
