# Metadata.md — 项目元信息

> 只回答一个问题：**这是哪个项目、哪一版、谁负责、现在什么状态。**
> 架构边界看 `architecture.md`，功能进度看 `coreFun.md`，操作红线看 `constraint.md`。

## 一、项目名 + 一句话定位

**ChartFlow**：基于 Spring Boot + 大语言模型（LLM，即 ChatGPT 这类模型）的 AI 图表生成器，把自然语言描述变成流程图 / 思维导图 / 架构图，并支持"人类手绘 + AI 聊天"双通道协作编辑。

## 二、文档元信息

| 项 | 值 |
|---|---|
| 文档版本 | 对齐代码分支 `reframing`@`70813bb`（第一阶段基线） |
| 文档状态 | 评审中（目标架构已定稿，真实代码待重建） |
| 作者 | 用户 22719（产品/架构决策）+ AI 协作撰写 |
| 评审人 | 待用户评审 |
| 最后更新 | 2026-09-12 |
| 仓库地址 | 本地 `F:\ProgramData\IDEA\flowchart`（git，`reframing` 分支；remote 待确认） |
| 设计稿 | `frontend/` 脚手架（阶段 6，09-12 落地空壳；渲染原型仍为 PoC HTML，见第七节） |
| 需求/路线图 | `plans/masterPlan/`（按核心模块拆分的模块计划，v2-N 出处） |

> 版本号跟代码版本对齐；状态是活的——定稿后任何内容改动都要升版本并在第三节留一行。

## 三、变更记录

| 日期 | 版本/基点 | 改动 | 作者 |
|---|---|---|---|
| 2026-09-11 | reframing@70813bb | 文档树迁入 `workflow/`；五份知识文档按新标准（元信息/核心功能/工程约束/架构设计/自反馈机制）重构；`workflow.md` 的"四拍+状态机"归并入 `AGENTS.md` | 22719 + AI |
| 2026-09-12 | reframing@70813bb | 文档体系：`plans/` 拆分为 `masterPlan/`（模块总计划，含多个 v2-N）与 `underway/`（进行中计划，可多个）；`state/active/` 立“同刻仅 1 个”规则；`state/activeLog/` 改按日期平铺、只追加；新增 `plans/underway/TEMP.md`（与 masterPlan/TEMP.md 同构）；新增质量门禁脚本并写入 `AGENTS.md` 索引与提交约定 | 22719 + AI |
| 2026-09-12 | reframing@70813bb | 代码与验证：新增 `frontend/` 脚手架（Vite + React 18 + TS strict + ESLint 空壳，跑通 install/build/lint/dev，v2-6）；质量门禁本地验证绿（后端 24 测试 BUILD SUCCESS、末行 VERIFY PASS、退出码 0，v2-3），修脚本两处（补 UTF-8 BOM、Maven 输出改 Out-Host 消费以修复退出码污染） | 22719 + AI |
| 2026-09-12 | reframing@70813bb | 附录更正：`architecture.md` 第七节把“Git Bash 的 mvn 坏”更正为“MSYS 路径转换被禁（仅存在于 Agent 沙箱终端进程，用户级/机器级未设置）导致 Maven 的 Unix 启动脚本失效”，并补 PowerShell 两坑（脚本须带 UTF-8 BOM、函数返回值污染退出码）与 npm ci 的 vite/esbuild 文件锁坑 | 22719 + AI |
| 2026-09-12 | reframing@70813bb | 收尾：v2-6 前端脚手架计划移入 `state/completed/`；`m6-frontend.md` 前置项 v2-6 标完成并把旧目录名 `flowchart-frontend/` 更正为 `frontend/`；`Metadata.md` 设计稿行同步更正为 `frontend/` | 22719 + AI |
| 2026-09-12 | reframing@70813bb | 质量门禁脚本前端安装方式改为可配：新增 `-FrontendInstallMode ci|install`（本地默认 `install` 增量不删目录，CI 传 `ci` 严格按 lockfile），`AGENTS.md` 与 `architecture.md` 同步说明 | 22719 + AI |
| 2026-09-12 | reframing@70813bb | 后端单测扩面（v2-4 完成）：新增 `controller` / `client` / `model` 三个测试类（MockMvc 契约 15 + LLM 客户端本地桩 10 + 数据模型 9），全量 24 → **58** 个测试全绿、BUILD SUCCESS、退出码 0；零新依赖（未改 `pom.xml`）、未动业务包主源码；另做变异验证（错误码 400→401 测试变红、还原后 sha256 一致）证明测试真在守契约 | 22719 + AI |
| 2026-09-12 | reframing@70813bb | 接入 checkstyle（v2-7，进行中）：新增项目根 `checkstyle.xml`（最小规则集 39 条，末尾附「未启用清单」逐条写理由）；`pom.xml` 挂 `maven-checkstyle-plugin 3.3.1`（显式指定 checkstyle 10.12.4 以支持 Java 17 语法，**不绑生命周期**），使门禁第 3 步从"永远跳过"变为真生效；`NeedBraces` / `AvoidStarImport` 因现有 4 处命中落在 `controller`/`service`、受 M0 边界「禁止改动业务包功能实现」约束而暂缓（理由与启用条件写入规则集注释）；离线 `clean test` 回归 58 测试全绿且无下载动作 | 22719 + AI |
| 2026-09-12 | reframing@70813bb | checkstyle 真跑验证与闭环（**v2-7 完成**）：门禁第 3 步首次真跑报 25 条、临时全开后实测 **29 条**违规（24 `LeftCurly` 单行 getter/setter + 2 `NeedBraces` + 1 `MissingSwitchDefault` + 2 `AvoidStarImport`；**测试源码零违规**）；按「`LeftCurly` 放宽（tokens 剔除 `METHOD_DEF`/`CTOR_DEF`，允许单行访问器）+ 以上三条暂缓（启用条件：M1 重建 service/controller 后）」处置为 0 违规，**全程零源码改动**，规则集实际启用 **38 条**；复跑 `You have 0 Checkstyle violations.` + `BUILD SUCCESS` + 退出码 0；变异验证（临时加 187 列行 → `LineLength` FAIL、退出码 1，删除后恢复绿无残留）；`clean test` 仍 58 测试全绿；另修两处配置错误（`<encoding>` → `<inputEncoding>`、tokens 去掉 10.12.4 尚不存在的 `SWITCH_RULE`）。规则集头部原"接上是干净的"断言已证伪并改为"以真跑 `checkstyle:check` 为准" | 22719 + AI |

## 四、当前阶段与状态（2026-09-11）

| 项 | 值 |
|---|---|
| 工作分支 | `reframing`（第一阶段基线 `70813bb` + 密钥保护提交） |
| 旧版留档 | tag `archive/v0.3-full-tasks`（原任务 40-53 全量，**只作思路对照，不直接复用**） |
| 阶段目标 | 0 工程底座 → 1 核心链路 → 2 RAG → 3 Tool Calling → 4 ReAct Agent → 5 会话 → 6 前端改版 → 7 可观测治理 |
| 路线图出处 | `plans/masterPlan/`（模块计划，v2-N 出处） |
| 进度查看 | `plans/masterPlan/`（规划中）与 `plans/underway/`（在跑）及 `state/activeLog/`（已做） |

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

- 不引入目标架构（`architecture.md` / 各 `plans/masterPlan/` 模块计划）之外的框架或中间件（个人项目，宁少勿多）
- 不照搬公司级基建（如 Victoria / Vector 观测栈），只做最小等价实现

## 七、已验证原型（PoC，2026-09-11）

双通道白板方向已做出**实测通过**的原型（非 flowchart 仓库内，独立 proof-of-concept）：

- 路径：`F:\ProgramData\Workbuddy Data\Workbuddy\2026-09-09-14-47-53\excalidraw-prototype\`（4 文件同目录：`excalidraw_collab.html` + `react`/`react-dom`/`excalidraw` 三个 UMD）
- 验证：Playwright + 真实 Chromium 实测，初始 20 元素、零报错；选中节点→切矩形工具→空白处拖拽能画出矩形；AI 命令加节点后用户手绘保留；`reset` 稳定回到 20 不翻倍
- 角色：验证"Excalidraw 嵌入 + 双通道 + IR 渲染"可行；**`frontend/` 脚手架空壳已于 09-12 落地（v2-6），待在其上按本方向嵌入 Excalidraw 双通道**
