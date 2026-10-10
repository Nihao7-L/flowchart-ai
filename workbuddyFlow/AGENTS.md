# AGENTS.md — ChartFlow 项目地图

> 只做索引，不做百科全书：先读这里，再按下面的表去读对应文档。
> 最后更新：2026-10-10（M10 上下文工程收尾归档：版本号治理 v2-43 完成、账本对齐、现状快照刷新）。

## 项目一句话

ChartFlow：Spring Boot + 大模型（LLM）的 **AI 白板（Whiteboard）**，支持「人类手绘 + AI 聊天」双通道协作编辑；白板可承载图表 / 图形图案 / 标注笔记 / 图文混排等任意 Excalidraw 能画能写的形式，不限于图表。

## 现状快照（2026-10-10 核对）

| 项 | 值 |
|---|---|
| 分支 / 远端 | `reframing` → `github.com/Nihao7-L/flowchart-ai`（public） |
| 门禁（2026-10-10 实跑） | 后端 **266 测试 / 0 失败**（`mvn -o test`）· **checkstyle 0 违规** · 前端 `tsc --noEmit` 退出码 0（10-10 当日口径）· 冒烟 `scene-smoke.mjs` **22/22** |
| 提示词唯一来源 | **`src/main/resources/prompts/whiteboard/system-prompt.md`（v10.2.0 / 7462 B）**（运行时 `PromptService.buildSystemBlock()` 直读 + 注入契约）。⚠️ **改完必跑 `scene-smoke.mjs`**（契约字段↔前端白名单双向守卫） |
| 提示词版本号 | ✅ **不再硬编码**：`PromptService.promptVersion()` 从 md 治理头现读。`GenerationService.PROMPT_VERSION` 那个常量已退役——它在 09-28~10-10 期间 md 连改十余版而它一直停在 v8.0.1，**不报错不告警只是安静地记错**，会喂假基线给 M14 回归。**CHANGELOG 同期漏记 v9/v10 整段，已补齐**；`PromptServiceContractTest` 3 条守卫锁死（改 md 忘了改账本 → 测试红） |
| 刁难电池（2026-09-24 实测） | 20 组 → 后端校验器 **13 PASS / 7 FAIL**；7 FAIL 全是模型照做的违规、**7/7 被拦**；修复「强迫违规返回空场景」后复测 3/3 转合法非空 |
| 端到端电池（2026-10-10 实测） | 白话 9 例 **9/9 PASS** · 难度 12 例 **12/12 PASS**（均经修正轮修好） |
| 活跃项 | **空**（`state/active/` 只剩 README）—— M10 已完成归档至 `state/completed/masterPlan/m10-system-prompt.md` |
| 执行序 | S1（M1+M6a）、S1a（M9）、S1b（M8）均已归档 → **S2 上下文工程（M10）已完成** → 下一站见 `plans/masterPlan/roadmap.md` |

> ⚠️ **别信状态文件**：`state/active/` 与 `plans/masterPlan/*.md` 靠人手动搬移，
> 连续干活时不更新 —— M10 就曾"看着卡在进行中"，实际早已跑远 16 天。
> 判断真实进度请交叉核 **md 头 + CHANGELOG + Java 常量 + `mvn` 末行**。

## 索引（每份只回答一类问题，按需读一份，不要全读）

| 想知道 | 读 |
|---|---|
| 项目是什么、哪一版、谁负责、什么状态 | `docs/Metadata.md` |
| 核心功能：能做什么、不能做什么、量级 | `docs/coreFun.md` |
| 工程约束（含日志规范）：红线与后果 | `docs/constraint.md` |
| 架构设计：组成、连接、决策、风险 | `docs/architecture.md` |
| 自反馈机制：怎么知道做得好并修正 | `docs/workflow.md` |
| 白板能力：哪些元素 / 原语能画能写、渲染器实际支持什么 | `docs/whiteboard-grammar.md` |
| 复杂提示词测试集（9 条，512~2570 字，电池题源） | `docs/复杂提示词测试集.md` |
| 前端 / IR / SSE 机制的纵深长文（梳理性质，非索引类） | `docs/FlowAI架构与交互机制.md` |
| 模块总计划（按核心模块拆分，v2-N 出处） | `plans/masterPlan/` |
| 执行顺序：先做哪个阶段、做到哪步算"看得见" | `plans/masterPlan/roadmap.md` |
| 进行中计划（来自模块总计划里的 v2-N，可多个） | `plans/underway/` |
| 一键验证脚本（提交前必跑） | `run-verify.ps1`（与本文件同目录） |
| 当前唯一活跃项（来自 underway，同一时刻仅 1 个） | `state/active/` |
| 做过的与证据（追加式） | `state/activeLog/<YYYY-MM-DD>.md` |
| 已完成的计划（模块总计划 / v2-N 子计划） | `state/completed/masterPlan/` · `state/completed/underway/` |
| 怎么起服务、怎么端到端验、探针怎么用 | `tools/`（见下「验证工具链」） |

## 验证工具链（四层 + 探针，从快到慢）

| 层 | 命令 | 前置 | 单次耗时 |
|---|---|---|---|
| ① 门禁（提交前必跑） | `powershell -File workbuddyFlow/run-verify.ps1` | 无（不启容器、不发 HTTP） | ~1 分钟 |
| ② 离线 E2E（打桩 `/api/chat`，只验前端逻辑） | `bash .playwright/run-e2e.sh [用例名]` | 5173 dev server | 秒级 / 条 |
| ③ 真实 LLM E2E（不打桩） | `python tools/e2e/e2e_real_llm_layout.py` | 8080 后端 + 5173 | 34~40 秒 / 轮 |
| ④ 复杂提示词电池（压力 / 探索，不做 PASS/FAIL） | `bash .playwright/run-battery.sh [--list\|--pick N\|--all\|--seed S]` | 8080 + 5173 | 98~283 秒 / 题 |
| ⑤ 刁难电池（**直连 LLM、不经后端**，20 组：5 类强迫违规 + 6 张超大复杂图） | `D:\Python-Home\python.exe .workbuddy\ab\adv_battery.py` | 只读 `.env`（无需起服务） | 12~105 秒 / 题 |
| ⑤′ 把 ⑤ 的真实输出喂给**真实 `GraphValidator`**（考验后端护栏拦不拦得住） | `mvn -o test -Dtest=AdvValidatorCheck` | 先跑 ⑤ 生成 `raw_adv/` | 秒级 |

探针（零 LLM 调用、秒级，用来"不改代码先问事实"）：

| 探针 | 回答什么问题 |
|---|---|
| `tools/e2e/probe_contract_capacity.py` | 渲染器（Excalidraw）实际支持哪些元素 / 字段（对齐契约容量） |
| `tools/e2e/probe_text_metrics.py` | 中文 / 拉丁字符宽度标定（布局算节点宽的依据） |
| `tools/e2e/probe_frame_membership.py` | `frame.children` 到底有没有语义（给 / 不给 A/B 对照） |
| `tools/e2e/probe_frame_audit.py` | 图形有没有跑出所属 frame（离线确定性闸门：escaped 必须 0） |
| `tools/e2e/probe_elbow_arrow.py` | 折线箭头可行性（结论：绑定箭头 ⇔ 直线是 0.18.1 硬约束） |
| `tools/e2e/probe_frontend_geom.py` · `frontend/tools/probe_scene_fit.mjs` | 前端几何 / 取景行为 |

其他：`tools/layout-bench/`（布局基准 + 三方渲染对比，`-Dtest=LayoutBench test` 手动跑）· `tools/run-backend.sh` / `run-frontend.sh` / `run-tests.sh`（起服务）· `tools/sse-probe.py`。

> ⚠️ `tools/**` 下的 `battery-out/` 快照、`probe-*.json`、`layout-bench/out/`、`compare.png|html`、`elbow-probe.png` 都是**跑出来的产物（是证据，不是源码）**，已在 `.gitignore` 排除。文档引用它们 = 引用"本地产物"，新克隆里不存在。

## 怎么用这份知识层（四拍 + 状态机，必读）

### 四拍

1. **开场**：人发"先读 AGENTS.md，按索引读完 docs，复述约束再动手"；Agent 输出约束复述 + 等任务。这一步替代"每次口头交代规矩"，是跨会话不失忆的唯一手段。
2. **开工前**：`plans/masterPlan/mN-<模块>.md` 是已写好的**总计划**（一个模块内含多个 v2-N 子计划，六段结构见 `plans/masterPlan/TEMP.md`），不要重写它。要推进某个 v2-N 子计划时，把它作为**进行中计划**放进 `plans/underway/`（`v2-N-<短名>.md`，可多个并行），写明这一步具体做什么、验收口径是什么，人确认后才动码。
3. **执行中**：改码 → 跑 `run-verify.ps1` → 往 `state/activeLog/<YYYY-MM-DD>.md` 追加一行（时间 / 任务 / 动作 / 证据）；不过就自改，不問人。
   - 门禁（第 ① 层）**不启 Spring 容器、不发真实 HTTP、不验 npm run dev**：凡改的是出图质量（布局 / 排版 / 提示词 / 契约字段 / 前端适配），必须再按需跑 ②③④ 中的一层，否则"绿"只代表编译与单测过。
4. **收尾**：活跃项从 `state/active/` 移出 → 计划按来源移 `state/completed/masterPlan/`（模块总计划）或 `state/completed/underway/`（v2-N 子计划）→ 更新 `plans/masterPlan/` 对应模块计划状态行 → 改动交用户提交（git 写操作由人执行）。

### 状态机（plans/masterPlan · plans/underway · state/active · state/completed · state/activeLog）

- 每个计划文件顶部状态行：`规划中 | 进行中 | 阻塞 | 完成`。
- `plans/masterPlan/` 放**模块总计划**：一个核心模块一个文件 `mN-<模块>.md`，**一个模块内含多个 v2-N 子计划**（各自带状态行）；模块**整体完成**后移 `state/completed/masterPlan/`（`m0-harness.md` 2026-09-14、`m1-generation.md`、`m6a-render.md` 已归档）。
- `plans/underway/` 放**进行中计划**：来自 masterPlan 里某个 v2-N 子计划的工作台副本，**可多个并行**；不限定数量；完成后移 `state/completed/underway/`（已有 `m1-pre-cleanup.md`、`v2-36-layout.md`、`v2-37-edit-fidelity.md`、`v2-39-layout-planning.md` 等十余份归档于此）。
- `state/active/` 放**最活跃的那一个**（从 underway 提升而来），同一时刻**只保留 1 个文件**（进入活跃态移入、离开移出）；空目录表示当前无活跃项。
- `state/activeLog/` 按日期分文件、**只追加不修改**，格式 `MM-DD HH:mm 做了什么 → 证据`；多 Agent 并行天然不冲突。
- 会话被压缩 / 换新会话：读 `state/activeLog/` 尾部几行 + `plans/masterPlan/` 状态行即可续上。
- ⚠️ **归档判据**：子计划一旦写"已交付 / 已完成"，就应移入 `state/completed/underway/`，**别让它滞留 `plans/underway/`**（v2-38 曾滞留一天，容易被误读成"还没做完"）。

## 环境与已知坑（新会话最容易踩的，展开见 `docs/constraint.md`）

- 沙箱里 Bash 工具**不带 PATH** → 每条命令前 `export PATH="/usr/bin:/bin:/c/Windows/System32:$PATH"`。
- Git Bash 会把 `X:\...` 形态的盘符路径转成 `X:/...` → **路径一律写正斜杠**；非 ASCII 字符（中文、全角引号）在 Bash 工具里**不会被破坏**。
- 在 Python / JS 字符串字面量里写 Windows 路径要小心语言自身的转义（`\f` 会变换页符）→ 同样建议一律正斜杠。
- 沙箱注入了 `SERVER__PORT=61150`（已被宿主占用）→ 起后端前必须 `unset SERVER__PORT SERVER_PORT`；**不改项目配置**。
- 前端构建必须带 `--emptyOutDir false`（否则沙箱的 safe-delete 会拦掉清空 `dist/` 的动作）。
- `.playwright/`（约 817MB）是 E2E 工具链，**是工具不是垃圾，勿删**。
- **`scene.schema.json` 的完整性不能只靠后端测试守**：后端把它当**纯文本**嵌进 prompt（`PromptService.loadSchema()`），而 `GraphValidator` 是**手写**校验器、**不解析**它 ⇒ 文件被改成坏 JSON 时 `mvn test` **照样全绿**。唯一会 `JSON.parse` 它的是前端冒烟 `frontend/tools/scene-smoke.mjs`（同时负责「契约字段 ↔ 前端白名单」双向差集守卫）。**改完契约务必跑一次冒烟**。2026-09-24 实测：`arrow` 定义里删 `oneOf` 时**连带删掉了该对象的收尾大括号** → 冒烟崩在第 16 例；而那段坏 JSON 此间一直被当契约喂给 LLM。

## 提交与分支约定

- `main` = 稳定可运行版；开发在 feature 分支（或继续用 `reframing`），完成后 merge 回 main。
- commit message：`feat|fix|chore|docs: 动词开头的中文描述`。
- **提交前必须 run-verify 绿**——脚本 `run-verify.ps1` 与本文件同目录（`workbuddyFlow/run-verify.ps1`），本地跑 `powershell -File run-verify.ps1`（`-SkipFrontend` 跳过前端检查；`-FrontendInstallMode ci|install` 选前端安装方式，**本地默认 `install`** 增量不删目录，CI 传 `ci` 严格按 lockfile）；这条同时约束人和 Agent。
- **云端门禁**：`.github/workflows/ci.yml`（GitHub Actions）与 `run-verify.ps1` 是**同一套三步门禁的两个执行器**——push 任意分支即自动跑（并行的 backend / frontend 两个 job），**改任一处必须同步另一处**，否则出现「本地绿、云端红」；日志正文需登录 GitHub 才能看，运行状态与耗时可匿名读 API（详见 `docs/architecture.md` 7.1）。
- `application.yml`（含明文 key）/ 阶段总结与规划类文档永远本地保留不入库（`.gitignore` 已固化）；`.workbuddy/`（项目记忆）、`.playwright/`（工具链）、`tools/**` 的运行产物同样不入库。
