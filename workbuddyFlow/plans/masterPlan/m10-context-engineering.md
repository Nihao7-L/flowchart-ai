# 模块计划 · 上下文工程（S2 上下文）

> 状态：规划中
> 关联架构：`context/` 新包；ADR-3（LLM 网关收口）；不碰 `graph/`、`rag/`、`agent/`
> 所属阶段：**S2 上下文工程**（执行序见 `roadmap.md` 第二节）
> 覆盖任务：v2-41 ~ v2-43
> 方向来源：用户 2026-09-20 要求把剩余路线主轴改为「上下文工程 → 循环工程(ReAct) → 提示词工程」，参考 javaguide《context-engineering》
> **决策落地（2026-09-20 用户拍板 G2 / Q3-A）**：
> - **G2 = 全量 JIT**（每轮把完整 `scene.schema.json` 随 prompt 注入，不降级、不摘要；保证 LLM 始终有完整字段可对照、零漂移）。`fit_token_budget()` 仍管历史 / RAG 背景 / 工具描述等其它分区，但**契约区固定全量高优先级**（不为了省 token 牺牲契约完整性）。
> - **Q3-A = Prompt 版本管理（嵌入 M10，近零成本）**：每次生成记录 `promptVersion`（随 prompt 内容变更递增的语义化版本号，**非 Git 式 diff 追踪**）；与 `context-replay` 一起落盘，回归时按版本对照。版本号由 M11（提示词工程）在改 prompt 内容时递增，M10 负责在每轮组装时带上并记入回放日志。

## 元信息
- 模块编号：M10
- 状态：规划中
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
- [ ] v2-41 ContextAssembler（`system`/`user` 分区 + `rank` + `fit_token_budget`）
- [ ] v2-42 全量 JIT 契约嵌入（G2）+ 编辑模式增量 + 每轮回放落盘
- [ ] v2-43 Prompt 版本号（Q3-A）：`promptVersion` 字段 + 回放/日志落盘
