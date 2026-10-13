# 模块计划 · 循环工程（S4 循环 / 真 ReAct）

> 状态：规划中
> 关联架构：`agent/` 包（ADR-4：允许调 `tools` / `llm` / `graph` / `rag`）；不碰 `GraphValidator` 判定逻辑本身、`scene.schema.json` 字段语义
> 所属阶段：**S4 循环工程**（与 M6b② 同阶段交付；执行序见 `roadmap.md` 第二节）
> 覆盖任务：v2-55 ~ v2-56
> 方向来源：用户 2026-09-20 要求主轴「上下文工程 → 循环工程(ReAct) → 提示词工程」，参考 javaguide《loop-engineering》《workflow-graph-loop》《agent-basis》。
> **决策落地（2026-09-20 用户拍板）**：
> - **G1 = 真 ReAct**：本模块落地"LLM 持工具、Thought→Action(`tool_calls`)→Observation 循环"的真 Agent 范式（**不是**上轮草稿里的"状态化 Graph Loop"）。理由：用户选择让 LLM 保有工具调度权、循环本体按 Agent 范式实现。
> - **G4 = 连美观一起查**：`ReadabilityGate` 的继续/退出条件**既管硬指标（契约合法 / NaN / 重叠 / 空图）也管美观指标（标签压叠 / 文字互叠，复用 M8/M9 口径）**——不再"校验通过即停"**。

## 元信息
- 模块编号：M4（原 `m4-agent.md` 重定位，旧文件已指向本计划）
- 状态：规划中
- 创建：2026-09-20 + 架构师；2026-09-20 决策落地（G1 / G4）

## 目标
把今天 `GenerationService.generateInternal()` 里「隐式 for 循环 + 只看 issue 数量」的伪循环，换成**真 ReAct（LLM 持工具、Thought→Action→Observation 循环）**：每轮 LLM 以 `tool_calls` 提议动作（生成 / 检索 / 读 / 校验 / 修正），`service/` 执行后把 Observation 回喂 LLM 再决策；循环继续/退出由 **`ReadabilityGate`** 决定——**G4 已拍板连美观一起查**（硬指标 + 美观指标全过才停），并加**无进展检测**（卡住就停、不白烧轮次）+ **issue 明细外露**（修 `roadmap.md` 发现 5）+ **checkpoint**（中断/恢复，挂钩 M5 分层持久化与 M7 Trace）。

## 边界
- 允许新增：`agent/`（ReAct 循环骨架 / `LoopState` / `ReadabilityGate` / `NoProgressDetector` / `LoopCheckpoint`）
- 允许修改：`GenerationService`（把隐式 `round` 循环替换为真 ReAct 驱动）+ `buildFixPrompt`（累积历史 + 折叠 readability / 美观 issue）
- 禁止改动：`GraphValidator` 判定逻辑本身、`scene.schema.json` 字段语义；**工具只改 model（编辑模式经 M3），不引入外部非确定副作用**
- **G4 落地后果**：美观指标（标签压叠 / 文字互叠）**进入本模块的质量门**（原归 M8/M9 的口径，这里作为循环退出条件复用，不再"只归排版层"）

## 分层边界
落在 `agent/`；编排 `llm`（ADR-3 网关）/ `graph`（校验）/ `tools`（M3，编辑模式回写）/ `rag`（M2，检索增强）。不碰 `session`（M5 接管持久化，但本模块 checkpoint 的序列化格式是 M5 的输入契约）。**理由**：循环是「生成期的控制流」，与上下文（M10）、提示词（M11）正交；三者经 `ContextAssembler` 的输入/输出解耦——M10 给每轮装窗口，M11 给内容，M4 决定「再跑一轮还是停」。

## 现状债务（来自代码核实）
- `GenerationService.generateInternal()`：`for (round=1; round<=MAX_ROUNDS(3); round++)` → `prompt→llmProvider.chat→extractJson→sceneBinder.bind→sceneDeduper.dedup→scenePlanner.arrange→graphValidator.validate`；`issues` 空即 save+send+return，否则 `buildFixPrompt`。
- 安全边界：`MAX_ROUNDS=3`、`TOTAL_BUDGET_MS=420_000`、`MAX_ROUND_SECONDS=300`、`MIN_ROUND_SECONDS=10`。**无 LoopState、无无进展检测、issue 只报数量不报内容**（`roadmap.md` 发现 5）。
- **八段⑦「校验只查契约」**：今天钩子只判契约合法即停，标签压叠 / 文字互叠这类美观问题不归循环管 —— G4 拍板后纳入 `ReadabilityGate`。

> **⚠️ 本节数字已过时（2026-10-13 对账，保留作 2026-09-20 快照）**：`MAX_ROUNDS` 已改 `BACKSTOP_MAX_ROUNDS=5`（达标即停 + 硬上限兜底）、`TOTAL_BUDGET_MS=3600_000`、`MAX_ROUND_SECONDS=900`（提交 `2feca53`/`8d661b9`）；链路现为 `bind→dedup→sanitize→claimFramesOnly→validate`（**不再调 `scenePlanner.arrange` / 布局引擎**，坐标由 LLM 原生给出）；issue 已**点名化**（`GeometryAudit` NAMED_CAP=8，含局部改法）；**修正轮机制已存在**（issue 结构化三元组回喂 + issue 摘要压缩）。本模块开工时按新现状重估差距，其中「无独立需求评估器 / 无 checkpoint / 无 run journal」仍是真实缺口。

## 调研输入（2026-10-13，learn-claude-code 课程 + 24 篇 agent 工程文献）

> 来源：《learn-claude-code 对照进度表》《Agent 工程研读报告》（`.workbuddy/reports/2026-10-13-*`）。本节是**设计输入**，不改上面已拍板的 G1/G4 决策。

1. **ReadabilityGate 可叠加「独立需求评估器」（s17 Goal Loop，杠杆最大）**：G4 的闸门是**确定性指标复算**（NaN/重叠/互叠），s17 补的是另一维——**另起一次小模型调用**（候选表单列便宜模型），只读「用户需求 + 渲染层元素摘要 + GeometryAudit 结果」，判 `{ok, issues[], impossible}`：需求是否被满足、还有没有点名问题。`ok=false` → issues 转点名化修正 issue 进下一轮（复用现有修正轮通道，新增代码仅一个评估器类 + 一处接线）。配套两道出口（s17 原则）：连续拦截 N 轮强制放行（防打转）、评估器调用失败=放行（不伪装成功也不卡死）。治「第 1 轮 schema 通过即落库、第 2 轮与换模型永不发生」——校验器只查合法性不查观感/需求满足度。
2. **checkpoint 具体化为 run journal JSONL（s16 journal）**：每轮追加 `{round, promptHash, candidate, response, verdict, tokens, durationMs}`；中断可恢复、且直接作为 M7 观测与 M14 `--baseline` 的数据源（一个落点喂三处）。
3. **修正轮 previousJson 分级压缩（s08 四级管线）**：治「模型每轮从零重画 + 一份判决书、看不见自己上一轮画了什么」（违背 Manus"保留错误"/12-factor#9）。做法：**被本轮 issue 点名的元素保留全量**，其余元素替换为一行摘要（类型+label+坐标桶），完整 JSON 落盘留恢复路径——确定性字符串操作，零 API 成本。与 `ContextBudget` 的刻意留白对接。
4. **NDJSON 流式输出建议挂本阶段**（S4 过程可视化同族）：模型一行一个完整元素 JSON，后端逐行轻校验 + 逐元素 SSE 推送 + 前端增量追加，终局全量校验；截断时保留已上屏部分走修正轮补齐。⚠️ 与 json_schema 结构化输出互斥，按候选模型分流配置。rough.js seed 确定性已具备（追加不闪烁）。
5. **文献背书（本计划方向正确）**：无进展检测≈s17"连续拦截上限"；issue 点名化 = 文献标准结论「non-actionable feedback → 装饰性变化不收敛」（已落地 ✓）；单线程循环不拆子代理 = Cognition/MAST 结论（写一致性产物）。

## 前置
- M3 双通道工具可用（v2-15~17，S3）——循环在编辑模式要能回写被改元素
- M10 上下文 assembler 可用（S2）——每轮窗口由 assembler 装
- M2 RAG 向量检索可用（S6，G6 ③）——ReAct 的"检索"动作经 `rag/` 走 pgvector
- （M11 提示词内容可并行；本模块不依赖其落地才能跑，但 few-shot / 路由成熟后循环稳定性更好）

## 步骤
1. **v2-55 真 ReAct 循环骨架 + ReadabilityGate（连美观一起查）**：
   - **循环骨架**：`agent/` 跑 Thought→Action(`tool_calls`)→Observation；LLM 提议（生成 / 检索 / 读 / 校验 / 修正），`service/` 编排执行（调 `tools` / `llm` / `graph`，检索经 `rag` M2），Observation 回喂 LLM 再决策。替换今天 `GenerationService` 的隐式 for 循环。
   - `ReadabilityGate`：**G4 已拍板连美观一起查**——退出条件 = `GraphValidator` 0 issue **且** 硬指标（NaN=0、图形重叠=0、非空图）**且** 美观指标（标签压叠=0、文字互叠 ≤ 阈值，复用 M8/M9 口径）全过。任一不过 → 把对应 issue（含美观项）折进下一轮 Observation，继续循环（治八段⑦「校验只查契约」：循环现在门可读性 + 美观，不只是契约）。
   - `LoopState`：持有 `{round, sceneIR(working), lastIssues, history[]}`；方法 `append(observation)` / `replace(scene)` / `checkpoint()`（序列化到磁盘，供 M5 分层持久化 / M7）。替换今天 `round` 计数器与隐式状态。
   - **不破坏"生成期确定性"**：工具只改 model，不引入外部非确定副作用；每次 Observation 回喂都要可重放（挂钩 M10 每轮回放）。
2. **v2-56 无进展检测 + issue 明细外露 + checkpoint**：
   - `NoProgressDetector`：连续 2 轮 issue 数不降，或 dedup 哈希与上一轮相同（产出未变）→ 提前 break，emit partial + tech-debt 备注 + 暴露原因。治「白烧 3 轮修不动」的隐性成本。
   - issue 明细外露（修发现 5）：`validation` SSE 事件与日志带 issue 明细（字段 / 位置 / 类型，**含美观项**），不再只报数量。
   - `checkpoint`：每轮 `LoopState.checkpoint()` 落盘 → 支持中断/恢复（刷新后续跑从 checkpoint 接上，不从头）；checkpoint 格式即 M5 分层持久化（Redis 热层 + PostgreSQL 冷层）与 M7 Trace 的输入契约。

## 验收口径
1. 故意制造不收敛输入 → 无进展检测在 < `MAX_ROUNDS` 时触发，输出 partial + 原因备注，不白烧满 3 轮。
2. issue 明细进 `validation` 事件与日志（可复盘，`roadmap.md` 发现 5 关闭）。
3. 契约合法但硬可读性 / 美观不过（如 NaN / 重叠 / 标签压叠 / 文字互叠）→ 继续循环并折叠 issue，直到通过或达上限。
4. 每轮 `LoopState.checkpoint()` 落盘；模拟中断后续跑能从 checkpoint 恢复（挂钩 M5 / M7）。
5. 迁移后电池题 2 / 题 4 出图硬指标（NaN / 图形重叠 / 空图）与今日一致，回归不退化。
6. **G4 验收（新增）**：电池题 4（72 对文字互叠）在 `ReadabilityGate` 下被识别并进入修正轮，不再「校验通过即停」；修正后文字互叠 ≤ 阈值。

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | "画登录流程图" | 1~2 轮过 ReadabilityGate，出图与今日一致 |
| 不收敛 | 故意矛盾需求（如"画 200 个不重叠的节点"） | 无进展检测提前 break，partial + 原因 |
| issue 外露 | 第 1 轮校验失败 | SSE 带 issue 明细，日志可查 |
| 硬指标不过 | 触发 NaN / 重叠 | 折进 fix prompt 继续，直到过或达上限 |
| 美观不过（G4） | 题 4 时序图（文字互叠） | ReadabilityGate 识别互叠、进修正轮，达标才停 |
| 检索动作 | "参考我之前画的订单系统" | ReAct 经 `rag/` 走 pgvector 召回历史 IR，注入本轮 |
| 中断恢复 | 长图生成中断 | 续跑从 checkpoint 接上 |

## 完成后
移入 `state/completed/masterPlan/`；`state/activeLog/<YYYY-MM-DD>.md` 追加一行带证据。

## 状态跟踪
- [ ] v2-55 真 ReAct 循环骨架（Thought→Action→Observation，LLM 持工具）+ `LoopState` + `ReadabilityGate`（**G4 连美观一起查**）
- [ ] v2-56 无进展检测 + issue 明细外露（含美观）+ checkpoint（中断 / 恢复，挂钩 M5 分层持久化）
