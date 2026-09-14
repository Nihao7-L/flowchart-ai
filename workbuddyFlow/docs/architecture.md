# architecture.md — 架构设计

> 只回答一个问题：**系统由哪几块组成、怎么连、为什么这么做、哪里会坏。**
> 读它的时机：新增文件、新增依赖、改动调用关系之前。

## 一、架构总览（C4 上下文层：只画可独立部署单元）

```
[浏览器: Excalidraw 双通道白板]
      │  HTTPS + JSON  （同步 REST / 异步 SSE 流式）
      ▼
[Spring Boot 单进程: ChartFlow 后端]
      │  HTTPS + LLM API  （同步请求 / 流式补全）
      ▼
[外部 LLM 提供方 (云端 API)]
```

- 单元①浏览器白板：可独立加载，持 IR 渲染镜像。
- 单元②Spring Boot：可独立启动，持有 IR 唯一真相源（session）。
- 单元③外部 LLM：第三方，异步/流式，不可控。
- 连线：浏览器↔后端为同步 REST + 异步 SSE（聊天改图走 SSE 流式）；后端↔LLM 为同步调用 + 流式补全。

## 二、视图展开

### 2.1 运行时视图（一次"聊天改图"请求）
```
浏览器 onChange / 聊天
  → POST /api/chat (SSE)
  → controller 校验
  → service.agent 编排：从 session 取 model(IR)
  → 组装 system prompt + model + 意图
  → llm 推理；复杂走 ReAct：Thought → Action(tools) → Observation 回喂
  → tools (Diagram Tools=自研 MCP) 直改后端内存 model
  → graph 校验 (field/reason/hint)；不通过带 issues 再调 ≤3 轮
  → 落库 session (Redis 优先)
  → SSE 回传：思考/工具/校验事件 + 结果
       · 增量 → ops (add/connect/update/delete)
       · 整图 → spec (全量 IR 无坐标)
  → 前端 apply ops / 用 spec 替换镜像 → layout → convert → updateScene 重绘
人类拖拽：onChange → syncPositions → PATCH /api/model/positions 回写后端 model
```

### 2.2 数据视图（唯一真相源）
- IR（图状态：节点+边，无坐标）唯一真相源 = **后端 session**（Redis 优先，降级内存）。
- 前端只持**渲染镜像**；AI 改图与人手拖拽都汇到同一份后端 model，互不覆盖。
- 坐标与手绘样式由前端 `layout`/`convert` 在渲染时补全，不进入 IR。

### 2.3 部署视图
- 单进程 Spring Boot + 浏览器；Redis 可选（不可用时降级内存）。
- 当前单用户本地；无容器编排、无多实例。网络化/多租户为未来演进。

### 2.4 开发视图（包边界，跨包调用只允许箭头方向）
| 包 | 职责 | 允许调用 |
|---|---|---|
| `controller/` | REST+SSE，只做校验与编排 | `service/` |
| `service/` | 生成编排：Prompt→LLM→解析→校验→修正 | `llm/ graph/ rag/ tools/ session/` |
| `llm/` | Provider 抽象 + 网关 | — |
| `graph/` | 图模型、校验、布局、SVG/Mermaid 导出 | — |
| `rag/` | 检索增强（阶段2） | `llm/` |
| `tools/` | 工具注册与执行（阶段3） | `rag/ llm/` |
| `agent/` | ReAct 规划与执行（阶段4） | `tools/ llm/ graph/` |
| `session/` | 会话与记忆（阶段5） | — |
| `infra/` | Trace/指标/审计（阶段7） | — |

规矩：同层不互调；下层不知上层；新增跨包依赖先改此表。

## 三、关键决策与取舍（ADR）

- **ADR-1 前端画布选 Excalidraw 而非 React Flow**
  - 背景：需要"手绘白板 + AI 改图"双通道，React Flow 偏结构化流程图。
  - 选项：React Flow（结构化强、手绘弱）/ Excalidraw（手绘自然、可嵌组件）/ 自绘 Canvas。
  - 决定：Excalidraw 嵌入 + 双通道。理由：贴合人类手绘直觉，且 `convertToExcalidrawElements` 能把 IR 渲染出来。
  - 代价：需处理 `convert` 重生成 id、NaN 视口等坑（见 `constraint.md` 技术约束）；结构化编辑弱于 React Flow。

- **ADR-2 模型（IR）后端持有，而非前端持有**
  - 背景：双通道下"谁是唯一真相源"直接决定数据流。
  - 选项：前端持有 / 后端持有 / LLM 直连前端。
  - 决定：后端 session 为唯一真相源，前端只持渲染镜像。理由：利于多端/协作/审计，消除"生成段"与"双通道段"抢模型归属的矛盾。
  - 代价：每次人类拖拽要 `PATCH /api/model/positions` 回写；实时性靠 SSE 增量。

- **ADR-3 LLM 调用收口到 llm/ 网关，禁止业务直连**
  - 背景：多 Provider、需审计与降级。决定：全部 LLM 调用经 `llm/` 网关。代价：业务层失去灵活性，换模型需走网关。

- **ADR-4 LLM 只出 IR 语义（无坐标），不出 Excalidraw 元素 JSON**
  - 背景：让 LLM 直接出像素级元素 JSON 易错且不可控。
  - 决定：LLM/Agent 只出节点+边（IR）；坐标/样式由前端 `layout`/`convert` 兜底。代价：需独立布局层，复杂图布局质量依赖 elkjs/dagre。

## 四、横切关注点

- **安全**：明文 LLM key 留 `application.yml` 不入库（gitignore 兜底）；Agent 禁改 env/系统设置。当前无鉴权（单用户本地）。
- **性能**：目标并发会话数待压测；单次出图延迟 ≈ LLM 推理 + ≤3 轮校验；**当前无量化 P99/QPS 数据（待 infra 阶段埋点）**。
- **可观测性**：阶段 7 建 Trace/metrics/审计；当前靠 `state/activeLog/` 做人工可观测。
- **成本**：token 月额度受外部 API 限制；长会话/大图需预算与降级（小模型兜底）。

## 五、风险与演进

- **代码仍停留在旧架构**：真实 `DiagramController` 仍是 `/api/generate` → LLM → PlantUML → SVG，与本文目标架构（`/api/chat` + agent/tools/session/IR）脱节，待按本文重建（路线图 v2-8 起）。
- **单进程扩展上限**：当前无水平扩展，多用户需重做部署视图。
- **无压测/无评测集**：性能与"自反馈"闭环缺量化基线（见 `workflow.md` 自反馈机制）。
- **下一步**：按 `plans/masterPlan/` 下模块计划推进 M0（底座）→ M1（核心链路）→ … → M7（可观测）。

## 六、架构不变式（改代码不得破坏）

1. 所有 LLM 调用经 `llm/` 网关收口。
2. 出图走结构化 JSON 契约 + 校验循环，禁裸文本直出。
3. 校验问题带 field/reason/hint，不只报数量。
4. Agent 每步 Observation 必须回喂 LLM 再决策。
5. 新增跨包依赖先更新 2.4 包表；文档与代码不一致先改文档。
6. LLM/Agent 只出 IR 语义，坐标/样式前端兜底，禁 LLM 直出 Excalidraw 元素 JSON。
7. IR 唯一真相源在后端 session，前端只持渲染镜像；AI 改图与人类拖拽汇同一份 model，禁前端自认权威。

## 七、开发视图附录：本机编译/验证

- 后端编译用 **java 直启 Maven**（Maven 3.9.5 本身完好，不用 `mvn` 的原因见下条）：
  `& "C:\Users\22719\.jdks\ms-17.0.17\bin\java.exe" -classpath "D:\maven-home\apache-maven-3.9.5-bin\apache-maven-3.9.5\boot\plexus-classworlds-2.7.0.jar" "-Dclassworlds.conf=D:\maven-home\apache-maven-3.9.5-bin\apache-maven-3.9.5\bin\m2.conf" "-Dmaven.home=D:\maven-home\apache-maven-3.9.5-bin\apache-maven-3.9.5" "-Dmaven.multiModuleProjectDirectory=F:\ProgramData\IDEA\flowchart" org.codehaus.plexus.classworlds.launcher.Launcher -B -f "F:\ProgramData\IDEA\flowchart\pom.xml" compile`
  （退出码 0 = 编译通过；换 `test` 即跑单测）
- 为什么不用 `mvn`：**不是 Maven 装坏了，是 Git Bash 会话禁用了 MSYS 路径转换**。环境中 `MSYS_NO_PATHCONV=1` 与 `MSYS2_ARG_CONV_EXCL=*` 会阻止 Git Bash 把 `/d/...` 翻译为 `D:\...`；而 Maven 的 Unix 启动脚本 `bin/mvn`（第 109、199 行）算出的 classpath 正是 `/d/...` 形式，喂给 Windows 的 java 后报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`。验证：`unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL` 后 `mvn -v` 立刻正常（Maven 3.9.5 / Java 17.0.17 / 退出码 0）。这两个变量**仅存在于 Agent 沙箱终端进程**，用户级与机器级均未设置，用户本机终端不受影响。
- 第二个原因：PowerShell 调用 `mvn.cmd` 取不到退出码，门禁无法据此判断成败。
- 根治方案（未做）：引入 Maven Wrapper（`mvnw` / `mvnw.cmd`）——项目自带启动器，不依赖本机 Maven 与 shell 路径翻译，团队与 CI 行为一致。
- 一键验证：`powershell -File run-verify.ps1`（末行 `VERIFY PASS`、退出码 0）。
- `run-verify.ps1` 的两个 PowerShell 注意事项：① 脚本**必须带 UTF-8 BOM**，否则 PowerShell 5.1 按本地编码解析，中文注释乱码会报"字符串缺少终止符"；② `Invoke-Maven` 内须先用 `Out-Host` 消费 java 输出再 `return $LASTEXITCODE`，否则函数把 stdout 一并当返回值，退出码判断恒为真（BUILD SUCCESS 也会误判 FAIL）。
- 前端门禁：`run-verify.ps1` 用 `-FrontendInstallMode ci|install` 选安装方式——**本地默认 `install`**（增量、不清空 `node_modules`、秒级），CI 传 `ci`（严格按 lockfile）。选 `ci` 时注意：它会先清空 `node_modules`，若残留 vite / esbuild 进程（开发服务器未彻底退出）会握着文件锁导致 `ENOTEMPTY`（-4048），跑之前先确认无 `esbuild.exe` 残留。

### 7.1 云端执行器：`.github/workflows/ci.yml`

- 定位：与 `run-verify.ps1` 是**同一套三步门禁的两个执行器**（本地 PowerShell / 云端 Ubuntu）。**改任一处必须同步另一处**，否则会出现"本地绿、云端红"。

| `ci.yml` 的 job / 步骤 | 对应 `run-verify.ps1` |
|---|---|
| job `backend` → `mvn -B clean test` | [1/3] `mvn clean test` |
| job `backend` → `mvn -B checkstyle:check` | [3/3] `mvn checkstyle:check` |
| job `frontend` → `npm ci` + `npm run build` + `npm run lint` | [2/3] npm 安装 + `build` + `lint` |

- 两个 job **并行**（后端 / 前端互不依赖）；触发：`push`（所有分支）+ `pull_request`（指向 `main`）；配 `concurrency.cancel-in-progress`，同一分支连续提交时自动取消上一次未跑完的运行。
- 唯一有意差异：CI 用 `npm ci`（严格按 `package-lock.json` 复现），本地默认 `npm install`（增量、秒级）——这正是 `-FrontendInstallMode` 开关存在的理由。
- action 版本取 2026 年现行主版本：`actions/checkout@v7`、`actions/setup-java@v6`、`actions/setup-node@v7`（**`setup-java` 的 v1~v4 已被官方弃用**，旧配置里的 `@v4` 不能照抄）；`setup-java` / `setup-node` 均开启依赖缓存（缓存 key 由 `pom.xml` / `package-lock.json` 哈希决定）。
- ⚠️ 与上一条相反：CI 跑在**原生 Ubuntu bash**，不存在 MSYS 路径转换问题，`mvn` 可直接调用。
- 实测（2026-09-12，run #2，`reframing`@`67cacc9`）：总 43s；后端 job 39s（安装 JDK 0s → `clean test` 25s → `checkstyle` 8s）、前端 job 17s（`npm ci` 5s → `build` 2s → `lint` 1s），两 job step 级全 success，`Tests run: 58` / `You have 0 violations.`。
- 排障可达性：运行的**状态与耗时**可匿名读（`api.github.com/repos/{owner}/{repo}/actions/runs`、`/runs/{id}/jobs`，含 step 级状态与时间戳）；但**日志正文需登录**（`/actions/jobs/{id}/logs` 匿名返回 `403 Must have admin rights`），查失败原因须在登录态浏览器里看。
- 已知缺口（未处理）：`src/main/resources/application.yml` 被 `.gitignore` 排除（含明文 key），**云端仓库没有这个文件**。当前 58 个测试全是纯逻辑测试 + `standaloneSetup`，不起 Spring 容器，故无碍；将来引入 `@SpringBootTest` 一类需要启动上下文的测试时，CI 会因读不到配置而失败——届时需注入 dummy 环境变量或补一份 test 专用配置。

