# project.md — 项目元信息

> 只回答一个问题：**这是哪个项目、哪一版、谁负责、现在什么状态。**
> 架构边界看 `architecture.md`，功能进度看 `features.md`，操作红线看 `engineering.md`。

## 一、项目名 + 一句话定位

**ChartFlow**：基于 Spring Boot + 大语言模型（LLM，即 ChatGPT 这类模型）的 AI 图表生成器，把自然语言描述变成流程图 / 思维导图 / 架构图，并支持"人类手绘 + AI 聊天"双通道协作编辑。

## 二、文档元信息

| 项 | 值 |
|---|---|
| 文档版本 | 对齐代码分支 `reframing`@`70813bb`（第一阶段基线） |
| 文档状态 | 评审中（目标架构已定稿，真实代码待重建） |
| 作者 | 用户 22719（产品/架构决策）+ AI 协作撰写 |
| 评审人 | 待用户评审 |
| 最后更新 | 2026-09-11 |
| 仓库地址 | 本地 `F:\ProgramData\IDEA\flowchart`（git，`reframing` 分支；remote 待确认） |
| 设计稿 | `flowchart-frontend/` 设计稿（阶段 6，当前仅有 PoC HTML，见第七节） |
| 需求/路线图 | `../架构规划-v2.md`（任务编号 v2-N 唯一出处） |

> 版本号跟代码版本对齐；状态是活的——定稿后任何内容改动都要升版本并在第三节留一行。

## 三、变更记录

| 日期 | 版本/基点 | 改动 | 作者 |
|---|---|---|---|
| 2026-09-11 | reframing@70813bb | 文档树迁入 `workflow/`；五份知识文档按新标准（元信息/核心功能/工程约束/架构设计/自反馈机制）重构；`workflow.md` 的"四拍+状态机"归并入 `AGENTS.md` | 22719 + AI |

## 四、当前阶段与状态（2026-09-11）

| 项 | 值 |
|---|---|
| 工作分支 | `reframing`（第一阶段基线 `70813bb` + 密钥保护提交） |
| 旧版留档 | tag `archive/v0.3-full-tasks`（原任务 40-53 全量，**只作思路对照，不直接复用**） |
| 阶段目标 | 0 工程底座 → 1 核心链路 → 2 RAG → 3 Tool Calling → 4 ReAct Agent → 5 会话 → 6 前端改版 → 7 可观测治理 |
| 路线图出处 | `../架构规划-v2.md`（任务编号 v2-N 的唯一出处） |
| 进度查看 | `../exec-plans/active/`（在跑）与 `../exec-plans/activeLog.md`（已做） |

## 五、已定决策（2026-09-10 与用户确认，不得随意推翻）

| 决策项 | 结论 |
|---|---|
| 原任务 39-53 的旧代码 | 后端功能全部推倒重来；archive tag 仅作实现思路对照 |
| 前端技术栈 | 改为 Excalidraw 手绘白板（`@excalidraw/excalidraw` 组件嵌入）取代 React Flow；保留 React 18 + Vite + TS + Zustand 做外壳与状态 |
| 前端 UI | 双通道协作白板：前端持 IR **渲染镜像**，**人类拖拽/手绘 + AI 聊天命令都改后端持有的同一份 model（IR）** |
| 工程底座 | 先行搭建（Harness 四层），功能长在底座上 |
| 六层框架（L1-L6） | 用作讲解与分层检查的骨架；四层 Harness 用作施工顺序 |
| 模型归属 | IR 唯一真相源在后端 session（Redis 优先），前端只持渲染镜像 |

## 六、不做的事（Non-goals，本文件级）

- 不引入 `../架构规划-v2.md` 之外的框架或中间件（个人项目，宁少勿多）
- 不照搬公司级基建（如 Victoria / Vector 观测栈），只做最小等价实现

## 七、已验证原型（PoC，2026-09-11）

双通道白板方向已做出**实测通过**的原型（非 flowchart 仓库内，独立 proof-of-concept）：

- 路径：`F:\ProgramData\Workbuddy Data\Workbuddy\2026-09-09-14-47-53\excalidraw-prototype\`（4 文件同目录：`excalidraw_collab.html` + `react`/`react-dom`/`excalidraw` 三个 UMD）
- 验证：Playwright + 真实 Chromium 实测，初始 20 元素、零报错；选中节点→切矩形工具→空白处拖拽能画出矩形；AI 命令加节点后用户手绘保留；`reset` 稳定回到 20 不翻倍
- 角色：验证"Excalidraw 嵌入 + 双通道 + IR 渲染"可行，**待按本方向重建 `flowchart-frontend/` 脚手架落地**
