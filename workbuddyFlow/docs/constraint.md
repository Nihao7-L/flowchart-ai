# constraint.md — 工程约束

> 只回答一个问题：**有哪些不能碰的红线，它们把设计逼成了什么样。**
> 每条约束后必带"它导致了什么后果"——没有后果的约束，读者无法判断优先级，也不会真正遵守。

## 一、技术约束（必须跑在哪、不能引入什么）

- **必须跑在本地单进程 Spring Boot 内，不起独立后端/微服务。** 后果：部署极简、无网络分区，但水平扩展受限（当前单用户场景可接受；多租户需重做部署视图）。
- **禁止改动环境变量与系统设置**（含增删用户级 env var）。后果：密钥只能留 `application.yml` 明文、不能走环境变量读取，靠 `.gitignore` 兜底防入库。
- **前端依赖用本地 UMD 三件套**（`react` / `react-dom` / `excalidraw` 与 HTML 同目录 `./` 引用），不引 CDN。后果：离线可用、无供应链风险，但需同目录部署、升级需手动换包。
- **在 Agent 沙箱里跑 `vite build` 必须带 `--emptyOutDir false`。** 原因：沙箱给 node 注入 safe-delete 拦截 shim，`vite build` 清空已有 `dist/` 时会撞批量删除拦截、报 `checkBulkDeleteGuard` 而失败（2026-09-18 实测：`dist/` 有上次产物时裸跑 `npx vite build` 失败，加 `--emptyOutDir false` 后 `✓ built in 11.37s`、exit 0）。**注意**：项目 `package.json` 的 `build` 脚本（`tsc -b && vite build`）与门禁 `run-verify.ps1` / `ci.yml` **都不带**此参数 —— CI 是干净检出、`dist/` 不存在，压根没有删除动作。后果：沙箱里复跑前端构建必须**显式加参数**，不要误判成项目配置缺陷去"修"它。
- **`convertToExcalidrawElements` 会重生成元素 id，认领元素只能靠 `customData`。** 后果：任何"按 id 前缀认领模型/用户元素"的写法都会元素翻倍，前端必须用 `customData.m` 区分归属。
- **箭头 skeleton 不放 `label`，边标签须独立 `text` 元素；不调用 `api.scrollToContent()`。** 后果：二者都会产 NaN 坐标污染包围盒 → 画布错位画不出图；前端渲染必须手动算包围盒。
- **`frame` 的 `children` 不由 LLM 填（2026-09-17 决定不变），但「适配层补空数组」的落法已被实测推翻（2026-09-18）。** 背景：Excalidraw 的 `convertToExcalidrawElements` 会强制访问 `frame.children.forEach`，而 `scene.schema.json` 的 frame 分支是 `additionalProperties: false` 且不含 `children` —— 契约与转换器天生不兼容，不兜则 frame 永远渲染不出来。后果（2026-09-18 对照实测，证据见 `docs/diagram-grammar.md` 第 4.1 节）：`children` 恰恰是建立「框 ↔ 内容」归属的**唯一途径** —— 输入 `children: ['n1','n2']` 时成员的 `frameId` 被反填为 frame 的 id；输入 `children: []`（**当前行为**）时成员 `frameId` 恒为 `null`，框与内容在数据模型里毫无关系（拖框不带走内容、删框不带走内容、框也不随内容调整）。所以 LLM 依旧不得输出 `children`（schema `description` 已写明），但缺口**不能靠补空数组收口** —— 改由**后端按分组/几何算出成员列表**、适配层填进去（计划见 `state/active/v2-40-contract-capacity.md` 的 P1）；改动该补丁等于改"frame 能不能画出来"，故由 `frontend/tools/scene-smoke.mjs` 的 frame 用例锁住（该用例锁的是「frame 能渲染出来」，**不锁「children 必须为空」**，可随本次修正一起更新）。（2026-09-17 拍板"方案 a"：契约不加字段／不砍 frame 类型／适配层收口。另两条被否方案：加进契约 → LLM 会乱填；砍掉 frame 类型 → 白丢一种元素。）
- **编辑模式下"没有要求改动的元素必须原样保留 id / type / 几何"是已对 LLM 的承诺**（`PromptService`），必须由校验层守住。后果：编辑语境首轮必须同时跑 `detectRewrite`（整图被换掉）与 `SceneDrift`（保留 id 却整图重排）两道检查（v2-37）；这也**反过来约束布局** —— 布局在编辑模式只能给新元素定位，不得全量重排（v2-36 的"新建/编辑"两种模式）。

- **契约的新增字段必须同步前端白名单 `SKELETON_KEYS`，否则该字段被静默丢弃。** 背景：`frontend/src/lib/sceneAdapter.ts` 的 `toSkeleton()` 按白名单搬运字段，契约里有、白名单里没有的键**不报错、只是不生效**。后果：任何"只改 schema 就以为扩了能力"的改动都会表现为「改了没反应」，且这种症状最难定位。制度对策：契约扩展把「`scene.schema.json` + `SKELETON_KEYS`」列为**同一步交付**；2026-09-18 起另有**自动守卫** —— `frontend/tools/scene-smoke.mjs` 的最后一条用例读 schema 与 `sceneAdapter.ts` 源文件做**差集**，漏同步当场变红（探针 `tools/e2e/probe_contract_capacity.py` 继续负责渲染器侧的能力回归）。
- **契约是当前表达力的唯一瓶颈：不要用「渲染器不支持」解释画不出复杂图。** 背景：2026-09-18 实测（探针 `workbuddyFlow/tools/e2e/probe_contract_capacity.py` + 截图 `probe-contract-capacity.png`），Excalidraw 0.18.1 支持 **12 种箭头端点**（含数据库鸦脚 crowfoot）、**多层 `groupIds`**、**可嵌套 `frame`**、`roundness`、图形的 `strokeStyle` 虚线、`verticalAlign`、`link`、多段 `points` 折线 —— **契约一项都没传过去**（契约 6 类可用元素、约 30 个字段）。后果：任何「某种图型画不出来」的问题，先查能力矩阵 `docs/diagram-grammar.md` 第二节，再怀疑渲染器；契约扩展计划见 `state/active/v2-40-contract-capacity.md`。
- **扩字段是「四处同改」，少一处等于白改：契约 + 前端白名单 + 校验器枚举 + 提示词。** 背景（2026-09-18 P0 实测新增第 3 道闸门）：`graph/GraphValidator.java` 里的 `FILL_STYLES` / `ARROWHEADS` / `VERTICAL_ALIGNS` / `TEXT_ALIGNS` / `FONT_FAMILIES` 都是**硬编码集合** —— 契约放了 `zigzag`、校验器没放，LLM 一用就判非法 → 整个场景被拒 → 白耗一整轮重试（实测一轮复杂图 100~150 秒）。注意它与白名单的**症状不同**：白名单是**静默丢弃**（改了没反应），校验器是**报错重试**（容易被误判成"LLM 不听话"，其实是我们的枚举没跟上）。制度对策：新增枚举值时必须与渲染器枚举**逐字对齐**（`ARROWHEADS` 就是照 Excalidraw 的 `Arrowhead` 联合类型 12 个字面量抄全的），并由 `frontend/tools/scene-smoke.mjs` 的差集守卫兜住前两道。
- **新增字段一律可选，且旧场景必须零回归。** 背景：契约是 LLM 与后端共用的唯一真相源，任何"把已有字段改成必填 / 改语义"的动作都会让历史场景 JSON 失效。制度对策：扩展只增不改（v2-40 的 P0 即"无损扩字段"），回归由冒烟用例中"旧场景渲染结果逐元素一致"保证。

## 二、组织约束（几个人维护、代码归属）

- **单人维护（用户 22719），禁止引入需专人运维的组件。** 后果：只做最小等价实现（可观测用最简 Trace，不引 Victoria/Vector），牺牲部分企业级能力换可维护性。
- **git 写操作由人执行，Agent 只读不写**（`add/commit/push/merge/reset/clean/tag/checkout -b/branch -d/git mv` 全禁；只读查询允许）。后果：提交节奏慢、需人工把关，但零越界风险、审计清晰。

## 三、资源约束（预算、机器规格、外部额度）

- **本机硬件 HP OMEN 暗影精灵8：i7-12700H / 16GB / RTX 3050 Ti 4GB。** 后果：本地无法跑大模型推理，依赖云端 LLM API；前端构建在本机，图布局改由**后端 `graph/` 手写分层布局**在服务端算（v2-36），**不引 elkjs/dagre** —— 与 `GraphValidator` 手写实现的风格一致，且避免新依赖所需的网络下载（按协作红线，任何下载须另行征得同意）。
- **模型 token 月额度受外部 API 限制。** 后果：需 token 预算与审计（infra 阶段 v2-27+），长会话/大图需控成本，必要时降级到小模型。
- **Redis 为可选依赖**（未注册为服务，需手动启动）。后果：session 持久化在 Redis 不可用时降级内存，重启即丢；生产化前需把 Redis 纳入部署。

## 四、合规约束（数据流向、审计要求）

- **操作全程留痕**：每次执行必写 `state/activeLog/<YYYY-MM-DD>.md`。后果：审计靠 `state/` 外置记忆（人读），非系统级审计栈；长任务可回溯但非自动化合规。
- **`application.yml` 含明文 LLM key 永不入库**（用户明确接受明文本地保留）。后果：泄露风险靠"本地 + `.gitignore`"控制，不进版本库；换机器需手动迁移配置。
- **数据流向：用户输入文本 → 云端 LLM API → 返回结构化图。** 后果：用户输入会出内网到第三方模型；涉及敏感图内容时需用户自判，当前无本地化模型兜底。

## 六、日志规范

### 6.1 字段命名约定

- MM-DD HH:mm 动作摘要（一行） → 细节 / 证据（命令 + 结果） → 状态：已提交 / 待审 / 等用户 commit

### 6.2 日志文件命名

- 按自然日滚动，一天一个日志文件（示例：`2026-09-11.log`），统一存放于 `state/activeLog/` 目录。

## 七、红线生长机制

任何人或 Agent 犯错并被验证抓到，就在对应小节追加一行"禁止/必须 X：原因（哪次事故）"。这条机制比文档本身重要——让约束来自真实事故，而不是想象。
