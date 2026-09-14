# Metadata.md — 项目元信息

> 只回答一个问题：**这是哪个项目、哪一版、谁负责、现在什么状态。**
> 架构边界看 `architecture.md`，功能进度看 `coreFun.md`，操作红线看 `constraint.md`。

## 一、项目名 + 一句话定位

**ChartFlow**：基于 Spring Boot + 大语言模型（LLM，即 ChatGPT 这类模型）的 AI 图表生成器，把自然语言描述变成流程图 / 思维导图 / 架构图，并支持"人类手绘 + AI 聊天"双通道协作编辑。

## 二、文档元信息

| 项 | 值 |
|---|---|
| 文档版本 | 对齐代码分支 `reframing`@`4d724f4`（M0 工程底座闭合；M1 前置清算已落盘；路线图重排为 S0~S6 执行序） |
| 文档状态 | 评审中（M0 已闭合；M1 核心链路重建中，旧链路已于 2026-09-14 清算删除；执行序已重排为 S0~S6） |
| 作者 | 用户 22719（产品/架构决策）+ AI 协作撰写 |
| 评审人 | 待用户评审 |
| 最后更新 | 2026-09-14 |
| 仓库地址 | 本地 `F:\ProgramData\IDEA\flowchart`（git，`reframing` 分支）；远端 `github.com/Nihao7-L/flowchart-ai`（public，已接入 GitHub Actions CI） |
| 设计稿 | `frontend/` 脚手架（**M6a**，09-12 落地空壳；渲染原型仍为 PoC HTML，见第七节） |
| 需求/路线图 | `plans/masterPlan/`（按核心模块拆分的模块计划，v2-N 出处）；**执行顺序**见 `plans/masterPlan/roadmap.md`（S0~S6） |

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
| 2026-09-12 | reframing@67cacc9 | **M0 工程底座全闭合（v2-5 完成）**：重建 GitHub Actions 云端门禁——`reframing`/`main` 两分支原本都没有 `.github` 目录，属**重建**而非修正；`ci.yml` 两个并行 job（backend / frontend）三步与 `run-verify.ps1` 一一对应，唯一有意差异是 CI 用 `npm ci` 严格按 lockfile，`setup-node` / `setup-java` 开依赖缓存；同时给 `run-verify.ps1` 补前端 `lint` 使两边对齐。push 后 run #2 全绿（43s：后端 39s / 前端 17s，两 job step 级全 success，58 测试 + 0 违规）。另发现并闭合一处「首跑必红」阻塞：`frontend/`、`checkstyle.xml`、`src/test` 等 36 个文件从未入库，已随 `67cacc9` 补入 | 22719 + AI |
| 2026-09-14 | reframing@4d724f4 | **路线图重排：引入 S0~S6 执行序**（用户决策：把 M6 提前的诉求改为整体重设计）。诊断：原 M0~M7 按技术层切，6 个待做模块中 5 个是纯后端，前端压到倒数第二，M1 之后要盲写 4 个模块看不见图；且 M1 的 `/api/chat` 天生多轮，本就需要最小会话上下文，而 M5 被推后。处置：① 新增 `plans/masterPlan/roadmap.md`（阶段 × 模块映射 + 每阶段"你能亲手验什么"）；② **M6 拆两文件**——`m6a-render.md`（v2-23 + v2-24 只读渲染，归 S1）与 `m6b-interaction.md`（v2-24 回写 + v2-25 归 S3、v2-26 归 S4），原 `m6-frontend.md` 删除；③ **M5 内存版下沉 M1**：v2-11 附带交付 `SessionStore` 接口 + 内存实现 + 坐标回写入口，M5 v2-21 收窄为"Redis 实现 + 探活降级"；④ `v2-30`（Agent 可读探针）无归属模块，明确归入 M7；⑤ `coreFun.md` 功能表加"所属阶段"列并更正 v2-3 状态漂移（"待做"→"已有"）；⑥ `architecture.md` 包表阶段标注改 S、ADR-2 补"内存优先、Redis 后置"落地顺序；`m2/m3/m4/m7` 补阶段标注。**M / v2-N 编号一个未动**（归档件不可回改） | 22719 + AI |
| 2026-09-14 | reframing@4d724f4 | **M1 前置代码清算**（用户决策：不保留导出下载 / 旧 UI 立即删 / 落清算清单）：删除 `static/` 旧 UI（736 行）、`DiagramService` + 其测试（512 行）、`plantuml` 依赖、`/api/generate`、`/api/download`、`GenerateRequest`/`DownloadRequest`；`DiagramController` 收敛为仅 `GET /api/health`，并附两条「旧端点不再被映射 404」防回归测试；顺带修掉 `PromptService` 模板缓存用 `HashMap` 的真实并发缺陷（改 `ConcurrentHashMap`，原注释以「个人项目」合理化该缺陷）；清除 3 处「面试」框架化注释；测试 58 → 36，`mvn clean test` 全绿且 `checkstyle:check` 0 违规；`architecture.md` 与 `README.md` 的旧链路表述同步更正（14 处）；决策与清单见 `plans/underway/m1-pre-cleanup.md` | 22719 + AI |

## 四、当前阶段与状态（2026-09-14）

| 项 | 值 |
|---|---|
| 工作分支 | `reframing`（第一阶段基线 `70813bb` + 密钥保护提交） |
| 旧版留档 | tag `archive/v0.3-full-tasks`（原任务 40-53 全量，**只作思路对照，不直接复用**） |
| 阶段目标（执行序 S0~S6） | S0 底座 → **S1 看得见（M1 + M6a）** → S2 更准（M2）→ S3 能改（M3 + M6b①）→ S4 会规划（M4 + M6b②）→ S5 不丢（M5）→ S6 可追踪（M7）。模块编号 `M0~M7` 是身份、`S` 是时间，见 `plans/masterPlan/roadmap.md` |
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
