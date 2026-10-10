# 模块计划 · 上下文工程（S2 上下文）

> 状态：**已完成 · 2026-10-10**（v2-41 / v2-42 / v2-43 全部交付；v2-43 收尾做了版本号治理 + 账本对齐）
> 关联架构：`context/` 新包；ADR-3（LLM 网关收口）；不碰 `graph/`、`rag/`、`agent/`
> 所属阶段：**S2 上下文工程**（执行序见 `roadmap.md` 第二节）
> 覆盖任务：v2-41 ~ v2-43 —— **全部完成**
> 📦 活跃项已归档：`state/completed/masterPlan/m10-system-prompt.md`（含逐条落地记录）
> ✅ **编号冲突已解决（2026-09-24 用户拍板）**：规则 = **先执行的任务保留原号、后执行的改号**。M10（S2）先于 M4（S4）⇒ **M10 保留 v2-43**；**M4 的 v2-43 / v2-44 改为 v2-55 / v2-56**（已同步 `m4-loop-engineering.md`、`m4-agent.md`、`m5-session.md`、`docs/coreFun.md`）。
> ✅ **静态层口径已收口（2026-09-24 用户拍板「以 `system-prompt.md` 为准」）**：运行时**已改为直接读 md**；
> 5 条 md 规则由后端代码承担（自环 / 绑 frame / 两式二选一 / line 绑端点 / group 白名单），
> 坐标语义一条反过来改 md（后端 v2-41 起确实不重排）。详见 `docs/../src/main/resources/prompts/whiteboard/CHANGELOG.md` 的 5.9.0 条。
> 方向来源：用户 2026-09-20 要求把剩余路线主轴改为「上下文工程 → 循环工程(ReAct) → 提示词工程」，参考 javaguide《context-engineering》
> **决策落地（2026-09-20 用户拍板 G2 / Q3-A）**：
> - **G2 = 全量 JIT**（每轮把完整 `scene.schema.json` 随 prompt 注入，不降级、不摘要；保证 LLM 始终有完整字段可对照、零漂移）。`fit_token_budget()` 仍管历史 / RAG 背景 / 工具描述等其它分区，但**契约区固定全量高优先级**（不为了省 token 牺牲契约完整性）。
> - **Q3-A = Prompt 版本管理（嵌入 M10，近零成本）**：每次生成记录 `promptVersion`（随 prompt 内容变更递增的语义化版本号，**非 Git 式 diff 追踪**）；与 `context-replay` 一起落盘，回归时按版本对照。版本号由 M11（提示词工程）在改 prompt 内容时递增，M10 负责在每轮组装时带上并记入回放日志。

## 元信息
- 模块编号：M10
- 状态：**已完成**（2026-10-10）
- 创建：2026-09-20 + 架构师

## 目标
每次 LLM 调用前由 `ContextAssembler` 统一组装 `messages[]`（`system` 高优先级区 + `user`），
实现 `rank()`（关键约束固定首尾，治 Lost in the Middle）与 `fit_token_budget()`（低/中优先级压缩·引用·阶段性加载卸载，治 Context Rot），
每轮落**可回放基线**（含 `promptVersion`）；迁移不退化出图质量（只改"组装方式"，不改 prompt 文本语义）。

## 边界
- 允许新增：`context/`（ContextAssembler / ContextBudget / ContextReplay）
- 允许修改：`PromptService`（改为调 assembler，不再自己 `StringBuilder`）、`GenerationService`（每轮用 assembler）
- 禁止改动：`scene.schema.json` 字段语义、`graph/` 校验逻辑、`SessionStore` 接口（可改实现）

## 分层边界
`context/` 位于 `service` 与 `llm`（ADR-3 网关）之间。组装是"调模型前最后一公里"，
`PromptService` 退化为"内容提供者"（持有规则 / few-shot 文本，供 M10 装配、M11 丰富），不再自己拼字符串。
不碰 `graph/`（M8/M9）、`rag/`（M2 将来接）、`agent/`（M4 将来接）。
**理由**：`rank()` / `budget()` 是 M2(RAG)、M4(循环)、M11(prompt) 共同的基座，独立成包避免三处各写一份、彼此漂移。

## 前置
无（仅复用现有 `PromptService` / `SessionStore`）。

## 步骤
1. **v2-41 ContextAssembler**：抽 `PromptService.buildPrompt` 的拼接逻辑（该文件 ~40 处 `sb.append`），建立 `system`(角色/约束/工具清单) + `user`(目标/证据/记忆/历史) 双消息；`rank()` 把「元素≤40」「绑定箭头」等硬约束固定进 `system` 首尾；`fit_token_budget()` 四档优先级——低（早期历史压缩）/ 中（RAG 背景·旧工具结果二次裁剪留引用）/ 高（System 约束·当前目标·安全边界固定）/ 阶段（按阶段加载卸载工具描述与 Schema）。
2. **v2-42 全量 JIT 嵌入（G2）+ 编辑模式增量 + 每轮回放**：契约 schema **每轮全量嵌入**（G2：无论图大小都把完整 `scene.schema.json` 随 prompt 注入，JIT=Just-In-Time 注入，零漂移、最稳）；`fit_token_budget()` 仍管**历史 / RAG 背景 / 工具描述**等其它分区的压缩与引用，但**契约区固定全量高优先级、不降级**（呼应 G2：不为了省 token 牺牲契约完整性）。编辑模式场景 >30 元素改"结构摘要 + 被改元素全文 + 引用"（呼应 javaguide 上下文卸载，仅作用于历史/旧图，不作用于契约）；每轮 `ContextReplay` 落本地 json（角色/长度/token 数 + 可选脱敏全文 **+ `promptVersion`**）。
3. **v2-43 Prompt 版本号（Q3-A）**：`ContextAssembler` 在每轮组装时带上 `promptVersion`（由 M11 维护、内容变更即递增），并写入 `context-replay-<session>-<round>.json` 与生成日志；提供 `promptVersion` 读取入口供 M14（评估回归门禁）按版本对照。不做 Git 式 diff 追踪（近零成本，仅一个版本号字段 + 日志）。

## 验收口径
1. 每轮契约**全量嵌入**（G2），大图/小图契约字段零漂移；电池题 2 复跑「字段缺失率 = 0、≤40 生效」，与今日一致或更好（不追求 token 下降，追求字段完整）。
2. 关键约束（≤40、绑定箭头规则）固定在 `system` 首尾，电池题 2 复跑「≤40」生效（实产 ≤ 阈值）。
3. 每轮入窗消息可回放（`context-replay-<session>-<round>.json` 落盘，含 token 数与 `promptVersion`）。
4. 迁移后电池题 2 / 题 4 出图质量（图形重叠 / 文字互叠 / NaN）与今日一致（回归不退化）。
5. **Q3-A 验收（新增）**：`context-replay` 与生成日志含 `promptVersion`；M11 改 prompt 内容后版本号递增、回放可区分前后两版窗口。

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常·小图 | "画登录流程图" | 契约全量嵌入、出图与今日一致、≤40 生效、回放含 promptVersion |
| 正常·大图 | 电池题 2（历史 143 元素） | 契约全量嵌入、字段零漂移、LLM 正确改图（token 不降但契约完整） |
| 编辑模式 | 47 元素图"删掉节点 X" | prompt 不再全量塞 47 元素，仅摘要 + 被改元素，保真通过 |
| 异常 | 预算逼近上限 | `fit_token_budget` 触发中/低优先级压缩（历史/RAG/工具描述），契约区不降级、不丢 `type`/`required` |
| 版本对照（Q3-A） | M11 改 prompt 后重跑同一题 | 回放中 promptVersion 递增、两版窗口可区分 |

## 完成后
移入 `state/completed/masterPlan/`；`state/activeLog/<YYYY-MM-DD>.md` 追加一行带证据。

## 状态跟踪
- [x] **v2-41 ContextAssembler**（`system`/`user` 分区 + `rank` + `fit_token_budget`）—— **已交付 2026-09-24**，计划已归档 `state/completed/underway/v2-41-context-assembler.md`；实际落地形态见下方「落地记录」
- [x] **v2-42 全量 JIT 契约嵌入（G2）+ 编辑模式增量 + 每轮回放落盘** —— **已交付 2026-09-24**。契约全量嵌入与每轮回放随 v2-41 落地；**编辑模式 >30 元素增量**于当日下半场补齐（`PromptService.EDIT_INLINE_MAX_ELEMENTS=30` + `compactSceneBlock`，小图路径逐字节不变），新增 3 例单测
- [x] **v2-43 Prompt 版本号（Q3-A）** —— **已完成 2026-10-10**。`promptVersion` 已随 v2-41 进入 `AssembleInput` → `ContextReplay` 落盘；本轮收尾两件事：
  1. **硬编码常量退役**：`GenerationService.PROMPT_VERSION = "v8.0.1"` 删除，改为 `PromptService.promptVersion()` **从 md 治理头现读**（取不到回退 `unknown` + WARN，不抛异常）。起因是该常量在 09-28~10-10 期间 md 连改十余版（v8.0.1 → v10.2.0）而它一直不动，**不报错不告警，只是安静地记错** —— 而它唯一用途就是给 M14 按版本对照，喂假基线比没记更糟。
  2. **CHANGELOG 补记**：v9.0.0~9.0.4 / 10.0.0 / 10.1.0 / 10.2.0 全部漏记，已按当日工作日志与治理头如实补齐。

### v2-43 收尾的守卫测试（2026-10-10，防再犯）

`PromptServiceContractTest` 新增 3 例（10 → 13，全绿）。**先写测试、后补 CHANGELOG**，
实测确实先红后绿 ⇒ 守卫真的有效，不是摆设：

| 测试 | 卡住什么 |
|---|---|
| `promptVersionComesFromGovernanceHeader` | 版本号必须真从 md 读出、格式为 `vX.Y.Z`、且**等于治理头里的值** |
| `changelogCoversCurrentPromptVersion` | CHANGELOG 必须记到 md 当前版本那一版 |
| `changelogLatestEntryIsCurrentVersion` | CHANGELOG 最新条目必须**就是**当前版本（不许落后） |

门禁：`mvn -o test` = **266 通过 / 0 失败**（原 263 + 新增 3）；`mvn -o checkstyle:check` = **0 违规**。

### 仍未做（明确不属于 M10）

- **独立「生成日志」落点**：grep `generationLog` 零命中，`promptVersion` 只进回放日志
  （`context-replay-<session>-<round>.json`）。留到 M7 可观测性激活时一并做。
- **包级反向依赖**：`context.ContextAssembler` 注入 `service.PromptService`。原计划
  「M10 收尾时把 `PromptService` 迁入 `context/`」，**本轮判断不做** —— 纯结构调整，
  要动 import 全树，收益零而回归面大，留到有实际动因再动。

## 落地记录（2026-09-24）

v2-41 实际落地形态与本计划原描述的差异（都能对上，但有取舍 —— 以后读代码请以本表为准，不要照本节上文理解）：

| 项 | 计划原文 | 实际落地 |
|---|---|---|
| 静态层文案来源 | 读 `resources/prompts/whiteboard/system-prompt.md`（markdown） | ✅ **已按时落地（2026-09-24 下半场）**：`PromptService.buildSystemBlock()` 直接读该 md + 注入契约，**不再有 Java 硬编码第二份文案**。前置（口径冲突）已由用户拍板「以 md 为准」并按 5.9.0 CHANGELOG 收口 |
| `fit_token_budget()` | 四档优先级压缩 / 引用 / 阶段加载卸载 | **只留框架 + 「契约区永不降级」白名单**；MID / LOW / STAGE 三档当前**无内容可压**（M2 RAG、M4 历史未接），刻意不写没人走的死代码 |
| `rank()` | 硬约束固定 `system` 首尾 | 当前是**固定布局**（system 一块 + user 一块，顺序固定）；作用是把「哪块永远在前面」收在一处，将来接 RAG / 历史只改这里 |
| 回放落盘内容 | 角色 / 长度 / token 数（+ 可选脱敏全文） | 只存**元数据**（role / 字符数 / token 估算 / zone），**不存正文** |
| 包依赖 | `context/` 只依赖 `client/` | **`ContextAssembler` 注入 `service.PromptService`**（包级反向依赖换简洁；代码注释已标技术债，M10 收尾时应把 `PromptService` 迁入 `context/`） |
| `AssembleInput` 字段 | 主签名含 history / memory / toolResults | 预留字段收进 **`AssembleInput.Extras` 子记录** + `of()` 工厂，主签名保持 **7 参** |

### v2-42 落地记录（同日）

- 阈值 `PromptService.EDIT_INLINE_MAX_ELEMENTS = 30`（编辑模式元素数**超过** 30 才走紧凑块）。
- 紧凑块 = **结构摘要**（全量一行骨架：`id type (x,y,w×h) start->end "label" group=`）
  + **被改元素全文**（用户需求里点名到 id / label 的元素给整份 JSON）
  + **引用骨架**（`e1: n1 -> n2` / `t1: in n1`）。
- **为什么骨架必须带几何与 label**：输出侧 md 约束 5 仍要求"全量 elements + 未点名元素原样保留"，
  摘要若不携带几何，模型只能瞎猜 → 图会散。带上后无需后端做合并。
- **小图路径零漂移**：`count <= 30` 走原整份贴出分支，文案与 v2-41 逐字节一致。
- 测试：`PromptServiceTest` 新增 3 例（>阈值走摘要 + 骨架全量 / 恰好=阈值仍整份 / 点名给全文 + 引用骨架）；
  `mvn -o test` = **184 通过 / 0 失败**。

**验证（v2-41）**：`mvn -o test` = **180 通过 / 0 失败**（原 175 + 新增 5）；「只搬家不改字」有机械证据（新旧双引号字面量 **76 : 76，MISMATCH 0**）。

**门禁（2026-09-24 下半场已转绿）**：`run-verify.ps1` 第 3 步 checkstyle 原为红（4 条历史违规：`PromptService.java:121/126` 超长行 + `AbValidatorCheck.java:3,7` 无用导入）；本轮回合随 `PromptService` 重写与无用导入清理**已降至 0 违规**，`mvn -o checkstyle:check` BUILD SUCCESS。
