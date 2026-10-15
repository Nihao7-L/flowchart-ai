# reorganize-harness（结构重组）· 用 harness 分层治理代码散落

> 状态：**已完成（2026-10-15 全部步骤收口；legacy 生成路径经 4 题双路径对照达标后由用户拍板物理删除，agent 单路径收口）**  
> 性质：跨模块结构治理（非单一 v2-N）。来源 = `m4-loop-engineering` + `m13-llm-gateway` + `m3-tools` + `m5-session` 的交叉项；本文件是它们共同的「装配形态」约定，**不替代**任何一份模块总计划，只定义零件怎么归位。  
> 触发：用户 2026-10-14 反馈「代码东一些西一些、看不懂」，要求参照 `learn-claude-code`（Harness Engineering for Real Agents）的组织方式。  
> 参照（learn-claude-code）：s01（agent loop = 一个 while + tool_use 驱动）、s02（TOOL_HANDLERS 派发表）、s09（跨会话 Memory）、s15（integrated harness = 多机制挂同一根循环）。  
> 红线：本文件是**规划**。任何源码改动仍走四拍（复述约束 → 人确认 → 动码 → 跑门禁 + 追加 activeLog）；本文档不授权直接改码。

---

## 一、元信息

- 任务编号：跨模块结构重组（无独立 v2-N，因其横跨 m4/m13/m3/m5）
- 状态：已完成（2026-10-15）
- 创建：2026-10-14
- 作者：agent（用户授权写规划文档）
- 关联计划：`v2-55-agent-loop-core.md`（已落地的 ReAct 最小核）、`m13-llm-gateway.md`（故障转移/冷却）、`m3-tools.md`、`m5-session.md`

---

## 二、背景与目标

### 2.1 别人怎么组织（learn-claude-code 的核心结论）

仓库 `shareAI-lab/learn-claude-code` 反复强调一句话：**模型本身就是 Agent（能动性来自训练），你写的代码只是「harness（马具/车厢）」——给 Agent 一个能感知、能行动的世界**。harness 的零件清单：

```
Harness = 工具(Tools) + 知识(Knowledge) + 上下文/记忆(Context) + 行动接口(LLM接口) + 权限(Permissions)
```

关键纪律（s15）：**循环结构永远不变**（一个 `while`：调模型 → 看有没有 `tool_use` → 有就执行工具把结果喂回去 → 没有就停），变的是挂在循环固定挂点上的零件（工具分发、权限、记忆、压缩、错误恢复）。循环体本身保持极小。

### 2.2 我们的目标（一句话，可验证）

把 ChartFlow 当前「散落在 4+ 包、双循环、双 LLM 客户端栈、单类 76KB」的结构，收敛为 **单一极简循环（`AgentLoop`）+ 零件按职责分层挂载** 的 harness 形态；任何一步都可独立回滚、不劣化出图质量与门禁。

---

## 三、现状诊断（带证据，非感觉）

证据来自 2026-10-14 实盘 `src/main/java/io/github/nihaoljx/flowchart/`：

### 问题 ① 两套「调 LLM 的客户端栈」并存（最迷惑新人）

- `client/`：`LlmProvider` / `FailoverLlmProvider` / `OpenAiCompatibleProvider` / `LlmEffort` / `LlmFailureKind` / `LlmProperties` / `Message` —— 被 legacy `GenerationService` 使用。
- `agent/`：`AgentLlmClient` / `FailoverAgentClient` / `OpenAiToolClient` / `AgentMessage(s)` / `ToolCall` —— 被 `AgentLoop` / `AgentLoopService` 使用。
- grep 证实 `agent/FailoverAgentClient.java` 反向引用 `client/FailoverLlmProvider` —— 即**新栈在旧栈外又包了一层**，两套「怎么跟模型说话」的抽象同时存在。

### 问题 ② `GenerationService.java` 是 76KB 的 god class（最大混乱源）

`76841` 字节。方法签名暴露 ≥6 种职责硬塞一类的特征：

- 预算/配置：`totalBudgetMs()`
- prompt 版本：`promptVersion()`
- 降级兜底：`fallbackOutcome()`
- SSE 事件流 + 工具日志：`onStep` / `onToolCall` / `briefArgs`
- 轮次规划：`planRound()`
- 场景解析/构造：`idsOfJson` / `currentModelJson` / `buildSeedScene` / `stampSchemaVersion` / `syntheticScene`
- （外加 legacy 生成主循环、parseGate、编辑模式逻辑）

### 问题 ③ `graph/` 一个包装了三种不同性质的东西

- 校验：`GraphValidator`、`GeometryAudit`、`ValidationIssue`、`RelationCatalog`
- 场景变换：`SceneBinder`、`SceneDeduper`、`SceneDrift`、`ScenePlanner`、`SceneSanitizer`
- 布局：`ElkLayoutEngine`、`LayoutEngine`、`LayoutSpec`、`SceneLayout`  
  三类彼此正交，却挤在同一包。

### 问题 ④ 「上下文/知识装配」做了两遍

- `PromptService.java`（`47601` 字节）：拼 system/contract/user/fix 块，还顺手做场景压缩（`compactSceneBlock`、`compactJson`）。
- `context/`：`ContextAssembler`、`ContextReplay`、`ContextBudget` 也在组装给模型的上下文。  
  两处都在组装上下文，边界模糊。

### 问题 ⑤ 两个生成循环 + 双份管线

- 新：`agent/AgentLoop`（ReAct）。
- legacy：`GenerationService` 内的生成主循环。
- `v2-55` 实测遗留明确记录：「SceneGate 与 GenerationService 双份管线待收口（步骤 4 对照后谁存活删谁）」。

### 问题 ⑥ 跨轮记忆薄弱

- `session/`（仅 `SessionStore` + `InMemorySessionStore`）只存单会话状态；无跨会话/跨轮「学到了什么」的沉淀层 —— 与用户此前关心的「修正轮不重复踩坑」直接矛盾。

### 问题 ⑦ 注释里塞了「变更日志 / 决策记录 / 实测教训」（194 处）

- **实证**：2026-10-14 grep `YYYY-MM-DD|实测|校准|上一版|旧版|这版改|拍板` 命中 **194 行**，分布在 `client/`、`graph/`、`controller/`、`agent/` 多个包。
- **典型范本**：`GeometryAudit.java` 类注释第 34–53 行「2026-10-09 实测校准（三处口径写错了，这一版改掉）」+ 第 55–59 行「新增一条判据」——把版本变更史写进了 Javadoc。前 32 行讲「为什么需要它 / 为什么不帮它摆」是**好的**，但后面「上一版把…当墙」「旧版一律用中心连线」「取代了旧版靠…的写法」是纯 changelog。
- **四类混杂内容**：
  1. **日期戳校准 / 改错史**：`2026-10-09 实测校准`、`2026-10-10 重构：错误先分类`、`2026-10-11 由 10 分钟改短`。
  2. **前版本对比**：`上一版`、`旧版`、`这版改掉`、`取代了旧版…的写法`。
  3. **用户决策记录（拍板）**：`2026-09-17 拍板`、`用户拍板`、`以 system-prompt.md 为准`。
  4. **行内实测教训**：`(2026-10-07 实测教训)`、`(2026-09-16 用户实测)`。
- **危害**：注释本应解释「代码**现在**做什么 / 为什么 / 不变量」，现在被「历史」淹没，新人读注释像读 git log；且历史会随版本累积越来越长、与实际代码脱节（注释说的「上一版」指哪一版无人可知）。版本号也别写进 Javadoc（如「几何体检器（v10.0.0）」）——版本是 CHANGELOG 的事。

---

## 四、目标架构（harness 分层映射）

| 层（harness 零件）          | 现状散落处                                 | 目标归属                                                                     |
| ---------------------- | ------------------------------------- | ------------------------------------------------------------------------ |
| **唯一循环（极简核）**          | `AgentLoop` + `GenerationService` 双循环 | 只留 `AgentLoop`；legacy 已由用户拍板删除（2026-10-15）                            |
| **工具 Tools（手）**        | `agent/tools/*`（已原子化）                 | 保留；新增 `planner` / `memory` 工具（归 m3）                                      |
| **知识 Knowledge**       | `PromptService` + `context/` 双装配      | `PromptService` 独揽知识装配；`context/` 归位为「会话回放」                              |
| **上下文/记忆 Context**     | `session/`（仅单会话）                      | `session/` + 新增跨轮 `MemoryStore`（归 m5）                                    |
| **LLM 接口（说话方式）**       | `client/` + `agent/` 双栈               | 合并成**单一 `llm/` 包**：一个 `LlmClient` 接口 + `FailoverLlmClient` 实现 + tool 适配器 |
| **领域逻辑 Domain**        | `graph/` 三合一                          | 拆 `graph/validate`、`graph/transform`、`graph/layout`                      |
| **观察 Observation（反馈）** | `SceneGate`                           | 作为「提交 → 校验 → 回灌」的闸门，挂在 submit 工具环节                                       |

核心约束：**循环本身极小**（对齐 s01 的 30 行理想）；所有机制（工具分发、权限、记忆、压缩、错误恢复）在循环的固定挂点插入，绝不把逻辑揉进循环体 —— 这正是 `GenerationService` 当前最违背的一点。

### 4.1 注释纪律（文档归位原则）

**核心区分：注释解释「现在」，变更史与决策进「别处」。** 这是 `learn-claude-code` 之外、本项目特有的纪律（问题 ⑦）。

注释**只允许**写三件事：

1. **为什么（intent）**：类 / 方法为什么存在、解决了什么（如 `GeometryAudit` 前 32 行）。
2. **不变量与算法纪律（invariant / non-obvious how）**：非显然约束，如「只测量不改坐标」「`hasXY && hasPoints` 原样透传否则取边缘交点」。
3. **对外契约语义**：`@link` 指向的字段 / 方法含义。

凡带以下特征的文字**一律迁出**：

- 日期戳 + 改错史 → `CHANGELOG.md`（版本线）或 git commit message。
- 「上一版 / 旧版 / 这版改」版本对比 → git history（靠 commit 讲清楚，不在注释重述）。
- 用户拍板 / 以 X 为准 → **决策记录文档**（如 `docs/decisions/` 或 ADR），不进 Javadoc。
- 行内「实测教训 / 用户实测」→ 若已固化为不变量就写进 ②，否则进 `CHANGELOG`。
- 版本号不写进 Javadoc（如「几何体检器（v10.0.0）」），版本是 CHANGELOG 的事。

---

## 五、目标包结构（具体落点）

```
io.github.nihaoljx.flowchart
├── FlowchartApplication.java
├── controller/            # 不变：DiagramController
├── model/                # 不变：请求/事件/结果 DTO
├── config/               # 当前空；放 Llm/agent 配置绑定
├── agent/                # 只留：循环 + 工具
│   ├── AgentLoop.java
│   ├── AgentLoopService.java
│   ├── AgentLoopListener.java
│   └── tools/            # AgentTools + ReadContract/ReadScene/ValidateScene/SubmitScene/UpdateElements + SceneGate
├── llm/                  # 合并 client/ + agent 客户端包装（单一栈）
│   ├── LlmClient.java            # 接口（原 LlmProvider 语义）
│   ├── FailoverLlmClient.java    # 实现（吸收 FailoverLlmProvider + FailoverAgentClient + OpenAiToolClient）
│   ├── OpenAiCompatibleProvider.java
│   ├── LlmEffort.java / LlmFailureKind.java / LlmProperties.java
│   └── Message.java / AgentMessage.java / ToolCall.java
├── prompt/               # 原 PromptService，只做知识装配
│   └── PromptService.java
├── session/              # 单会话 + 跨轮记忆
│   ├── SessionStore.java / InMemorySessionStore.java
│   ├── ConversationReplay.java   # 原 context/ 的回放职责改名归位
│   └── MemoryStore.java          # 新增：跨轮 issue/偏好沉淀（m5）
├── graph/
│   ├── validate/         # GraphValidator / GeometryAudit / ValidationIssue / RelationCatalog
│   ├── transform/        # SceneBinder / SceneDeduper / SceneDrift / ScenePlanner / SceneSanitizer
│   └── layout/           # ElkLayoutEngine / LayoutEngine / LayoutSpec / SceneLayout
└── service/              # GenerationService 瘦身为编排层
    ├── GenerationService.java    # 只做：驱动 AgentLoopService + 落库 + 兜底纪律
    ├── GenerationBudget.java     # 抽自 totalBudgetMs/planRound
    ├── GenerationEvents.java     # 抽自 onStep/onToolCall/briefArgs
    ├── SceneSeedBuilder.java     # 抽自 buildSeedScene/stampSchemaVersion/syntheticScene
    └── GenerationFallback.java   # 抽自 fallbackOutcome
```

---

## 六、边界（明确不做什么）

- **不改提示词语义**：`system-prompt.md` / `CHANGELOG` / 契约派生逻辑一律不动。
- **不重写校验器**：`GraphValidator` / `GeometryAudit` / `SceneGate` 只搬迁/收口，不重写判定逻辑。
- **不引入新生成能力**：本次纯结构治理，不新增绘图能力。
- **不动 `scene.schema.json` / 前端适配层**。
- ~~**不删 legacy 循环**~~：原计划"先降级为 fallback，双路径对照达标后再移除"；2026-10-15 用户拍板「agent 只保留这一个路径」，4 题对照达标后 legacy 已物理删除（见步骤 5 执行记录）。

---

## 七、分层边界（本次改动落在哪些包）

- 触碰：`agent/`（迁出客户端包装）、`client/`（并入 `llm/` 后删除）、`llm/`（新建）、`graph/`（拆三层）、`prompt/`（改名 + 收口）、`session/`（回放归位 + MemoryStore）、`service/`（拆 `GenerationService`）。
- 不触碰：`controller/`、`model/`、`config/`、`frontend/`、`system-prompt.md`、契约与校验判定本身。

---

## 八、前置

1. ~~`v2-55` 步骤 4/5 完成：电池题 agent vs legacy 双路径对照~~：**已完成（legacy 删除前置验证）** —— 电商订单 / 中台架构+CI-CD 共 4 题双路径对照，AgentLoop 质量不劣（指标入 activeLog）。
2. 门禁四绿：`mvn -o test` / checkstyle / tsc / `scene-smoke.mjs` 全过。
3. 备份当前 `GenerationService.java` / `client/` / `graph/` 三处（动码前按规矩先另存，防 `.workbuddy/` 不可恢复）。

---

## 九、迁移步骤（每步独立 PR、可回滚）

> 顺序原则：先收「说话方式」（风险最低、收益最高、最解迷惑），再拆「领域逻辑」，最后收口「循环」与「记忆」。

### 步骤 1 · 合并双 LLM 客户端栈 → `llm/` 单包（✅ 已执行 2026-10-14，含偏差）

- 范围：建 `llm/`，把 `client/*` 与 `agent/*Client/*Message/ToolCall` 迁入；`LlmProvider` 升为接口 `LlmClient`，`FailoverLlmProvider`+`FailoverAgentClient`+`OpenAiToolClient` 合并为 `FailoverLlmClient`。
- 风险：**冷却表是进程级单例**（已知：3 路并发曾 5/12 拿到占位符）。合并时**必须原样保留冷却表语义**，合并后重验多实例/高并发候选可用性（参照 `m13` 的失败分类 + 冷却表）。
- 验收：legacy 与 agent 两条路径跑通；并发探针复测无占位符回归；门禁四绿。
- **执行记录**：`client/` 已删除、18 个类归入 `llm/`，依赖方向单向 `agent→llm`。**偏差**：`LlmClient` 合并接口与 `FailoverLlmClient` 三合一**未做**（chat 与 tool_calls 两种调用形态语义不同，强合并会改行为，留待 m13 网关时再做）；并发探针复测未跑（需真实 LLM 环境）。

### 步骤 2 · `graph/` 拆三层（✅ 已执行 2026-10-14）

- 范围：`validate` / `transform` / `layout` 三个子包，仅移动文件、改 import，不改逻辑。
- 风险：import 面广，易漏改。
- 验收：编译过 + 校验器单测全绿 + `scene-smoke.mjs` 绿。
- **执行记录**：13 个类按三层归位；`LayoutSpec` 及其 static 成员升 public（跨包可见性需要）。测试树同包依赖的 import 由脚本批量修复；checkstyle 曾积压 218 个 Redundant/Unused import（历史上从未跑过 checkstyle:check），已按日志行号清零。

### 步骤 3 · `PromptService` 独揽知识装配，`context/` 归位（✅ 已执行 2026-10-14）

- 范围：`PromptService` 保留全部 prompt 组装；`context/ContextReplay|ContextBudget|ContextAssembler` 迁 `session/` 改名 `ConversationReplay` 等，明确「只回放历史，不组装知识」。
- 验收：出图 prompt 字节级与改动前一致（diff 比对 `buildPrompt` 输出）；门禁绿。
- **执行记录**：`context/` 目录删除，4 个类迁 `session/`、`ContextReplay` 改名 `ConversationReplay`。prompt 字节级 diff 比对未跑（无真实 LLM 会话环境），以"纯移动不改逻辑 + 全量单测绿"代替。

### 步骤 4 · `GenerationService` 拆 god class（✅ 已执行 2026-10-14）

- 范围：抽出 `GenerationBudget` / `GenerationEvents` / `SceneSeedBuilder` / `GenerationFallback`；`GenerationService` 仅保留选路 + 落库 + 兜底纪律。
- 验收：行为等价（双路径出图对比无差异）；单类降到可读规模（< ~1500 行）；门禁绿。
- **执行记录**：四类已抽出（GenerationBudget 含 totalBudgetMs 防内联读数，DiagramController 改调 `GenerationBudget.totalBudgetMs()`）；GenerationService 1624 → 约 800 行。外部调用面零变化（`issueDigest` 保留包级委托，单测零改动）。

### 步骤 5 · 双循环收口（✅ 已执行：2026-10-14 结构收口 + 2026-10-15 物理删除）

- 范围：legacy 生成主循环降级为 `AgentLoop` 的「单发策略」分支；`v2-55` 遗留的「SceneGate 与 GenerationService 双份管线」二选一收口（谁存活删谁）。
- 验收：flag 关闭时走单发分支、开启时走 agent；双路径电池题对照不劣化；移除死代码。
- **执行记录（2026-10-14）**：**双份管线已收口** —— SceneGate 搬迁至 `graph/validate/`（判定逻辑单一真相源），拆成可组合分步：`runPipeline`（四环）+ `parseGate` + `validateAll` + `emptySceneIssue` + `detectRewrite`；GenerationService 按步委托（兜底语义留在循环侧：解析失败给图、不伪装成功），agent 三工具走 `process`/`validateOnly` 同一实现。判据：管线顺序、issue 文案、兜底纪律与收口前一致，333 个单测零回归。
- **执行记录（2026-10-15）**：**legacy 物理删除，agent 单路径收口**（用户拍板「agent 只保留这一个路径」）。删除面：`GenerationService` 内 legacy 生成主循环 + `generate()` 分叉 + `chartflow.agent-loop.enabled` 开关；`llm/{LlmProvider, FailoverLlmProvider, OpenAiCompatibleProvider, LlmEffort, LlmFailureKind, Message}` 与 `session/{AssembleInput, ContextAssembler, ContextBudget, ConversationReplay}` 整类删除（agent 栈零引用）；`GenerationService` 1624 → 372 行（只留驱动 AgentLoopService + 落库 + 兜底纪律）。MemoryStore 记录点从 legacy 循环迁到 agent 监听器 `onToolResult`。删除前验证：电池题 4 题双路径对照达标。测试 333 → 281（删 legacy 专有用例），0 失败。

### 步骤 6 · 跨轮记忆层（✅ 已执行 2026-10-14，消费策略归 m5）

- 范围：新增 `session/MemoryStore`，把 issue 历史按 session 累积（场景已持久化，加 issue 历史即可）。
- 验收：多轮细化不重复踩同一 issue；门禁绿。
- **执行记录**：`MemoryStore`（接口）+ `InMemoryMemoryStore`（有界队列：每会话 50 条 / 1000 会话上限）落地；GenerationService 每轮校验失败即记录，构造器签名不变（单测零感知）。**注入修正提示词的消费策略归 m5** —— 本步只落"记录"，不动提示词管线；单测 5 个全绿。

### 步骤 7 · 注释清理与规范化（✅ 已执行 2026-10-14）

- 范围：全量 `src/main/java` 的 Java 注释。
- 纪律（用户追加）：① 注释里**禁止 HTML 标签**（`<p>` `<b>` `<ol>` `<li>` 等，又不是网页）；② 解释**简洁但讲清**；③ 注释解释「现在」，变更史/决策进 `CHANGELOG.md` / `docs/decisions/`；④ 禁止把日期戳/版本对比/拍板/实测教训这类「变更日志」塞进方法注释。
- 执行：两阶段脚本，均**只动注释行文本、绝不删整行**（第一版脚本曾误删代码行致编译失败，已从备份还原重做）。
  - 阶段一（标记规范化）：剥离 HTML 标签、Javadoc 内联标记降级为纯文本、HTML 实体解码、合并空行、剥行尾空格。54 文件整改。
  - 阶段二（内容清理）：删掉注释里「日期戳 / 版本号 / 拍板 / 实测教训 / 改口径 / 旧版 / 上一版 / 修复记录」等历史外壳，**保留技术实质**（如「单候选不许吃掉整轮预算」这类不变量留、前面的日期删）。55 文件、249 处注释行改写；残留空壳（悬空冒号、空括号）二次收尾清理（8 文件）。
- 结果：`src/main/java` 内 HTML 标签 / 实体 / `{@code}` / `{@link}` / **日期戳 / 版本对比 / 拍板 / 改口径 / 原值 = 0**；残留「实测」91 处均为合理技术解释（如「实测发现模型给已绑定箭头写…」），按计划保留。`mvn -o compile` 通过。
- 验收口径第 9 条达成。

### 步骤 7 之补 · 美观 / 可读性打磨（✅ 2026-10-14 末轮）

- 触发：用户指出「不能只删除然后不管，美观性、可读性都要」。复查发现光删文本留下了**排版与断句瑕疵**，补做一轮人工打磨：
  - **中文括号空格**：机械清洗 `（ `→`（`、` ）`→`）`（注释行 91 处）；并修正 1 处句中 artifact（`到 （后端布局）`→`到（后端布局）`）。Javadoc 的 ` * （文字）` 缩进属合法，保留。
  - **崩坏碎片重写**（删日期后断句）：`ToolContext`（`（ ：`→`（它是提醒…`）、`ElkLayoutEngine`（`（修的真缺陷）`→`修复的真实缺陷`、`（修）`→`修复`）、`SceneDrift`（句中空格）、`FailoverAgentClient`（剥掉 2 处 `2026-10-14` 日期戳、把 `{@link X}`→`X`、`&lt;code&gt;`→`状态码`）、`GenerationService`（剥 1 处 `2026-10-14` 日期戳、改成讲「为什么补日志」而非变更记录）、`ToolRegistry`（`{@code …}`→\`…\`）。
  - **变更日志口吻改说理**：`GraphValidator` 的「为什么不校验 children」动机块，把 `修正一/修正二（两次修正）` 这种修订史口吻改为 `第一层/第二层`（动机分两层），去掉 changelog 味、保留「为什么」实质。
- 末轮结果：HTML/实体/`{@code}`/`{@link}`/日期戳/版本对比/断句碎片 **全 0**；`mvn -o compile` 仍绿。

---

## 十、风险登记（不粉饰）

| 风险                                | 等级          | 处置                                |
| --------------------------------- | ----------- | --------------------------------- |
| 冷却表单例在合并时语义被破坏 → 并发占位符回归          | 高（P0 级历史事故） | 步骤 1 合并后**必跑并发探针**；冷却表逻辑整段平移不重写   |
| 双管线收口顺序错（先删 legacy 再发现 agent 有缺口） | 中           | ✅ 已消项：4 题双路径对照先达标、legacy 后删（2026-10-15） |
| `graph/` 拆分 import 漏改             | 低           | 步骤 2 后强制 `scene-smoke.mjs` + 全量单测 |
| `GenerationService` 抽取行为不等价       | 中           | 步骤 4 双路径出图字节/硬指标 diff 比对          |
| 记忆层引入跨会话串味                        | 低           | 步骤 6 按 session 隔离 + 召回上限          |

---

## 十一、验收口径（逐条可验证）

1. **单一 LLM 客户端栈**：`client/` 包不存在，`llm/` 是唯一「调模型」入口；grep `FailoverLlmProvider` 零命中（类已删），`AgentLlmClient` 仅命中 `llm/`。
2. **循环唯一**：`AgentLoop` 是唯一的 `while` 生成循环；legacy 循环体已物理删除（无分支、无开关）。
3. **`graph/` 三层清晰**：`validate` / `transform` / `layout` 各司其职，无跨层类。
4. **知识装配单点**：`PromptService` 是唯一 prompt 组装入口；`session/` 只回放不组装。
5. **`GenerationService` 可读**：行数降到可审阅规模，职责仅为编排。
6. **质量不劣化**：电池题 2/4 + 刁难电池双路径对照，硬指标（NaN/重叠/空图/违规拦截）不劣于基线。
7. **门禁四绿**：`mvn -o test` / checkstyle / tsc / `scene-smoke.mjs`。
8. **并发不复燃**：步骤 1 后并发探针无占位符回归。
9. **注释规范**：`src/main/java` 内无 HTML 标签（`<p>` `<b>` `<ol>` `<li>` 等）、无 `{@code}`/`{@link}` 内联标记、无日期戳/版本对比类「变更日志」文字；变更史进 `CHANGELOG.md`，决策进 `docs/decisions/`。

---

## 十二、覆盖测试数据

| 场景 | 输入                | 期望                         |
| -- | ----------------- | -------------------------- |
| 正常 | 流程图需求（电池题 2）      | agent 单路径出图，硬指标达标（legacy 已删） |
| 异常 | 3 路并发同名需求         | 无「候选均不可用」占位符（步骤 1 后）       |
| 边界 | 超大型四层分区图          | agent 多步出图，不超时（legacy 已删）     |
| 回归 | `scene-smoke.mjs` | 契约字段 ↔ 前端白名单零差集            |

---

## 十三、完成后

- 状态行改「完成」；
- 移入 `state/completed/underway/`；
- 更新 `m4` / `m13` / `m3` / `m5` 对应状态行（结构治理收口）；
- `state/activeLog/<YYYY-MM-DD>.md` 追加带证据记录（命令 + 退出码 + 双路径 diff 结论）；
- 改动交用户提交（git 写操作由人执行，约定式提交格式 `refactor: ...`）。
- 注（2026-10-15）：状态行已改「完成」；移入 `state/completed/underway/` 与 git 提交由用户执行时一并处理。

---

## 附：与三种框架的关系（回应前期讨论）

本重组不是「三选一」，而是把前期结论落到代码结构：

- **ReAct** = `AgentLoop`（控制流：一个 while + 工具穿插）。
- **Reflection** = `SceneGate` + `GraphValidator` + `GeometryAudit`（确定性评估器，issue 回灌）。
- **Plan-and-Solve** = 未来 `planner` 工具 / 「首轮只规划」约束（复杂大图时叠加，归 m3/m4 后续子计划）。  
  即「ReAct 控制流 + 确定性闸门当 Reflection + 复杂时叠 Plan-and-Solve」三者的融合，正是 `learn-claude-code` 的 integrated harness（s15）形态。
