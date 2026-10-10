# 进行中计划 · 上下文组装（v2-41 ContextAssembler）

> 状态：**已完成**（2026-09-24 交付，已归档 `state/completed/underway/`）
> 所属模块：**M10 上下文工程（S2）** 的第一个子计划
> 关联总计划：`plans/masterPlan/m10-context-engineering.md`（覆盖 v2-41~v2-43）
> 关联架构：`docs/architecture.md` §2.4 包表、`ADR-3`（LLM 网关收口）
> 关联决策：G2=全量 JIT、Q3-A=promptVersion（见 m10 总计划头部）
> 创建：2026-09-21 + 架构师
> 依赖前置：v2-40 契约容量（S1a）已交付；本计划**连同** `LlmProvider` 接口扩展一起做，但与 M13 解耦（见风险登记 R1）

---

## 术语对照（白话，给评审者与未来的自己）

- **上下文工程（Context Engineering）**：每次调模型前，把"角色 / 契约 / 规则 / 用户需求 / 历史"按**正确顺序**和**预算上限**拼成一包发给模型。本计划只做"组装这一包"，不改模型、不改出图质量。
- **Lost in the Middle（中间遗忘）**：prompt 越长，模型越容易忽略**中段**内容（像人读长文忘了中间几段）。治法：把硬约束固定放在 `system` 消息的**头尾**，不让它被挤到中段。
- **契约（Contract / `scene.schema.json`）**：出图字段的"合同"，模型必须严格照此输出 JSON。它是后端解析的唯一真相源，漂移 = 后端解析失败。
- **JIT（Just-In-Time，即时注入）**：每轮都把**完整契约**塞进 prompt，而不是只首轮塞一次或做摘要。最稳、零漂移，代价是 token 略多（G2 拍板：宁可多花 token，不许契约不完整）。
- **Token 预算（Token Budget）**：一轮能发多少字（token）的上限；逼近上限时**压缩低优先级内容**，但**契约区不降级**。
- **回放（Replay）**：把每轮实际发给模型的消息存成 json 文件，便于回归时逐版本对照"当时到底发了什么"。
- **门禁（Gate）**：提交前必跑的自动化校验 = 后端 180 测试 + checkstyle + 前端 tsc/eslint/vite 三绿 + 冒烟/离线 E2E（`run-verify.ps1`）。
- **走查（Walkthrough）**：本计划里的"代码事实"均带 `文件:行号`，可对照源码逐条核对，不是凭空描述。

---

## 元信息

- 任务编号：v2-41
- 状态：已完成（2026-09-24）
- 关联模块：M10（S2 上下文工程）· 见 `plans/masterPlan/m10-context-engineering.md`
- 创建：2026-09-21 + 架构师

## 目标

把"每次 LLM 调用前拼 prompt"这件事从 `PromptService` 的 `StringBuilder` 抽出来，统一由新建的 `context/` 包按 **`system` + `user` 双消息** 结构组装，并具备：
1. **`rank()`** —— 把「元素 ≤40」「绑定箭头规则」等硬约束固定进 `system` 消息头尾，治 **Lost in the Middle**；
2. **`fit_token_budget()`** —— 四档优先级压缩/引用/阶段加载卸载，治 **Context Rot（上下文腐烂：长上下文质量下降）**；
3. **每轮回放落盘** —— 含 `promptVersion`，治"无法对照到底发了什么"。

**一句话可验证目标**：出图质量（图形重叠 / 文字互叠 / NaN / ≤40 生效 / 契约字段完整）与今日一致或更好，**只改"组装方式"，不改 prompt 文本语义、不改任何出图逻辑**。

> ⚠️ **范围澄清（避免误解）**：M10 **不负责把图画得好看**。用户 2026-09-20 拍板"画得不好看"的根因是**该用的组件没用上**（绑定箭头的折点、纯 `line` 无箭头线），属于 **M9 契约 + M11 提示词 + 前端适配** 三家的事，与 M10 无关。M10 只做"上下文卫生"——让模型**每轮都稳定拿到完整、顺序正确的指令**。本计划**不引入任何拒绝层 / 越界判断**（用户明确"什么都能画，没有越界一说"），导出也不在本计划范围。

## 边界

- **允许新增**：
  - `context/` 包：`ContextAssembler` / `ContextBudget` / `ContextReplay` / `AssembleInput`（DTO）；
  - `client` 包：`Message`（消息记录类型，作为 LLM 网关契约的一部分）；
  - `LlmProvider` 新增 `chat(List<Message>, int)` 重载。
- **允许修改**：
  - `PromptService`：退化为"内容提供者"（仍持有 schema 加载器 + 规则文本 + few-shot，但**不再自己用 `StringBuilder` 拼整段 prompt**）；
  - `GenerationService`：每轮改用 `ContextAssembler` 组装 `List<Message>` 再调 `llmProvider.chat(messages, timeout)`；
  - `OpenAiCompatibleProvider`：实现 `chat(List<Message>, int)`，把 `Message` 序列化成 API 的 `messages` 数组；
  - `m8-layout-planning.md`：把被否的"走线 v2-41"重号处理（见 §编号冲突）。
- **禁止改动（越界红线，动了等于没守约）**：
  - `scene.schema.json` 的字段语义（只改 prompt 怎么"呈现"契约，不改契约本身）；
  - `graph/`（M8/M9 的布局、校验、绑定、排版、去重）—— M10 只读它们的产物，不碰；
  - `SessionStore` 接口语义（M10 不直接读写会话，只接收 `currentSceneJson` 字符串参数，与今日 `PromptService` 一致）；
  - `GenerationService` 的循环语义（`MAX_ROUNDS=3`、通过即发、失败才进修正轮）—— 只换"怎么造消息"，不换"跑几轮、何时停"；
  - 任何出图质量相关逻辑（坐标、绑定、排版、校验）—— 这些归 M8/M9，M10 不动。

## 分层边界

依据 `docs/architecture.md` §2.4 包表：

| 包 | 职责 | 允许调用 |
|---|---|---|
| `service/` | 生成编排 | `llm/ graph/ rag/ tools/ session/` |
| `llm/`（代码里是 `client/`） | Provider 抽象 + 网关（ADR-3） | — |
| `context/`（**本计划新建**） | 上下文组装：`rank` + token 预算 + 每轮回放 | 调 `client.Message` 构造消息；**当前阶段只接收字符串参数**，不直调 `session/`/`rag/`（M2/M4 未来接） |

**依赖方向（关键决策）**：
- `Message` 放在 `client` 包（`io.github.nihaoljx.flowchart.client.Message`），因为它是 **LLM 网关契约**的一部分；`LlmProvider.chat(List<Message>, int)` 签名需要它可见。
- `context/` 包**依赖** `client.Message`（`context → client`），符合"context 位于 service 与 llm 网关之间"的定位；**不反过来**让 `client` 依赖 `context`（那会倒置分层）。
- 因此新依赖链：`service` → `context` → `client`（→ 外部 LLM）。`graph/`、`session/`、`rag/` 均不被 `context/` 触碰（当前阶段）。

**为什么这样切**：`rank()` / `fit_token_budget()` 是 M2(RAG)、M4(循环)、M11(prompt) 三家的共同基座。独立成 `context/` 包，避免三处各写一份、彼此漂移；且 `context/` 只认"消息"与"内容块"，不认具体业务，可被未来 `agent/`（M4 真 ReAct）复用。

## 前置

执行前必须满足：
1. `reframing` 分支 `v2-40` 已交付且门禁绿（现状：P0/P1/P2/P4 ✅，P3 ❌已取消；见 `state/active/v2-40-contract-capacity.md`）。
2. 无其它阻塞；M13（LLM 网关加固）**不必先于本计划**——本计划的 `chat(List<Message>, int)` 扩展与 M13 的"重试 / token 埋点"**正交**，M13 后续叠加即可（见风险 R1）。
3. `run-verify.ps1` 可跑通（本地 `powershell -File workbuddyFlow/run-verify.ps1`，末行 `VERIFY PASS`、退出码 0）。

---

## 设计（核心抽象）

### D1. `Message`（落 `client` 包）

```java
package io.github.nihaoljx.flowchart.client;

/** LLM 网关消息：角色 + 内容。system=固定指令区，user=每轮变化区 */
public record Message(String role, String content) {
    public static Message system(String content) { return new Message("system", content); }
    public static Message user(String content)   { return new Message("user", content); }
}
```
> 用 `record`（不可变、零样板）。`role` 仅取 `"system"` / `"user"`（本期；M4 真 ReAct 时再加 `assistant` / `tool`）。

### D2. `AssembleInput`（落 `context` 包，组装入参 DTO）

```java
package io.github.nihaoljx.flowchart.context;

public record AssembleInput(
    String userText,          // 用户原始需求
    String currentSceneJson,  // 编辑模式下的当前场景（新建图传 null）
    boolean editMode,         // 是否编辑模式
    int round,                // 第几轮（1=首生成；>1=修正轮）
    List<ValidationIssue> issues, // 修正轮的校验问题（首轮传 null）
    String promptVersion      // 由 M11 维护；v2-41 先填基线值（见 v2-43）
) {}
```

### D3. `ContextAssembler`（落 `context` 包，组装主体）

职责：调 `PromptService` 取各内容块 → 按 `ContextBudget` 排序 → 产出 `List<Message>`。

```java
package io.github.nihaoljx.flowchart.context;

@Service
public class ContextAssembler {
    private final PromptService promptService;
    private final ContextBudget budget;
    private final ContextReplay replay;

    public List<Message> assemble(AssembleInput in) {
        // 1) 取内容块（语义与今日 buildPrompt 完全一致，只是拆开）
        String systemHead = promptService.buildRoleAndContract(); // 角色 + 契约(schema 全文)
        String systemRules = promptService.buildDrawRules();      // 作图规则（含 ≤40、绑定箭头）
        String userBody = promptService.buildUserBody(in);        // 当前场景 + 用户需求 [+ 修正块]
        // 2) rank：硬约束固定进 system 头尾（治 Lost in the Middle）
        // 3) fit_token_budget：契约区全量不降级(G2)，其余按优先级压缩
        List<Message> messages = budget.rank(systemHead, systemRules, userBody, in.round());
        // 4) 回放落盘（含 promptVersion）
        replay.dump(in, messages);
        return messages;
    }
}
```

**`rank()` 固定布局（头→尾）**：
```
[system]
  HEAD: 角色说明 + 契约(scene.schema.json 全文，每轮 JIT)
  TAIL: 作图硬约束（≤40、绑定箭头规则、坐标只是种子值、只输出 JSON）
[user]
  当前场景(编辑模式) + 用户需求 + （修正轮）上一轮校验未通过
```
> 关键：契约与硬约束**永远在 `system`**，不上 `user`、不进中段 —— 这正是治 Lost in the Middle 的本质。

### D4. `ContextBudget`（落 `context` 包，四档优先级）

```java
public class ContextBudget {
    /** 四档优先级：契约区固定全量(HIGH)，其余按档压缩 */
    public List<Message> rank(String systemHead, String systemRules,
                               String userBody, int round) { /* ... */ }
    /** 预算逼近时压缩：LOW=早期历史压缩；MID=RAG背景/旧工具结果二次裁剪留引用；
        STAGE=按阶段加载卸载工具描述/示例；HIGH=契约区不降级(G2) */
    public String fitTokenBudget(String zone, String text) { /* ... */ }
}
```

**四档（与 m10 总计划一致）**：
| 档 | 覆盖内容 | 预算紧时动作 |
|---|---|---|
| 高 HIGH | `system` 角色 + 契约 + 安全边界 + 硬约束（≤40/绑定箭头） | **固定全量，绝不降级**（G2） |
| 中 MID | RAG 背景、旧工具结果 | 二次裁剪，保留引用指针 |
| 低 LOW | 早期历史 | 压缩为摘要 |
| 阶段 STAGE | 工具描述 / few-shot 示例 | 按阶段加载 / 卸载 |

> 当前阶段（M10 先行、M2/M4 未接）：MID/LOW/STAGE 三档实际**无内容可压**（历史/RAG/工具都还没接），`fit_token_budget` 先落框架 + 契约区豁免逻辑，待 M2/M4 接入时自然生效。这样 M10 不与 M2/M4 抢活，只把"地基"打好。

### D5. `ContextReplay`（落 `context` 包，每轮回放）

落盘 `context-replay-<session>-<round>.json`：
```json
{
  "sessionId": "...", "round": 1, "promptVersion": "v1",
  "totalTokensEst": 1234, "budgetHit": false,
  "messages": [
    {"role":"system","length":980,"tokensEst":320,"zone":"HIGH"},
    {"role":"user","length":540,"tokensEst":180,"zone":"USER"}
  ],
  "ts": "2026-09-21T..."
}
```
- `tokensEst` 用启发式（字符数/4，中英文混排近似）；精确计数留给 M13/M14 埋点，本计划只记"估算 + 区间"，不承诺精确。
- 默认落 `workbuddyFlow/`（或 `tools/**` 运行产物目录，待确认；**注意 `.gitignore` 已排除运行产物**，回放文件不入库）。

### D6. 与用户"ReAct 上下文窗口"参考模型的三点对齐

用户 2026-09-20 粘贴的参考模型（静态层 system+工具schema / 动态层 user+memory+RAG / 输出+token 预算）与本计划对齐如下（已校准，非照搬）：

1. **契约 = Tool / Output Schema，固定进 `system` HIGH 区，G2 不裁剪** —— 参考模型的"静态层 = 工具/输出 schema"完全对应 ChartFlow 的 `scene.schema.json`。本计划把它钉死在 `system` 头，每轮 JIT 全量，与 G2 拍板一致。
2. **ChartFlow 的"动态层"按"修正轮"累积，不是 ReAct step** —— 参考模型以 ReAct 单步（Thought→Action→Observation）为单位累历史；ChartFlow 当前是"首生成 + ≤2 修正轮"的简单循环（非真 ReAct，真 ReAct 归 M4）。因此动态层 = `round1=场景`、`round2=场景+issues`，**历史/RAG 两档在 M2/M4 接入前恒为空**。本计划不为"还没接的东西"预留假数据。
3. **新增 alignment：上下文缓存（Context Caching）作为 M10↔M13 协同点** —— 参考模型隐含"system 前缀每轮不变"。本计划把 `system` 内容做成"每轮稳定不变的前缀"，M13 网关加固时即可启用 Provider 的 prompt-cache（缓存相同前缀、降本提速）。这是 M10 给 M13 留的钩子，本计划只保证"system 前缀稳定"，不实现缓存本身。

### D7. `PromptService` 退化方式（内容提供者）

`buildPrompt(String, String)` / `buildFixPrompt(...)` **删除**（其职责拆给下列内容块方法），`PromptServiceTest` 同步改写（见 §步骤 + 文件级改动表）。新接口：

| 方法 | 返回内容块 | 对应今日 buildPrompt 段落 |
|---|---|---|
| `buildRoleAndContract()` | 角色说明 + `## 契约` + schema 全文 | 原 73-75 行 |
| `buildDrawRules()` | `## 作图规则`（≤40、绑定箭头、坐标种子值、只输出 JSON 等） | 原 76-118 行 |
| `buildUserBody(AssembleInput)` | 编辑模式场景块 + 修改规则 + `## 用户需求` +（修正轮）校验未通过块 | 原 120-144（新建）/ 163-178（修正） |
| `loadSchema()` | schema 缓存加载（保留） | 原 248-267 行 |

> 语义不变保证：上述四块**拼接顺序与今日 `buildPrompt` 的 `sb.append` 顺序逐字一致**（仅把"契约+规则"挪到 `system`、"场景+需求"留在 `user`），故出图质量零漂移。

### D8. `LlmProvider` 扩展（触 ADR-3，但与 M13 解耦）

```java
public interface LlmProvider {
    String chat(String prompt) throws Exception;                 // 保留（delegate 到下方）
    String chat(String prompt, int timeoutSeconds) throws Exception; // 保留（delegate）
    /** 新增：真·多消息入口，system+user 角色由此生效 */
    List<Message> chat(List<Message> messages, int timeoutSeconds) throws Exception; // 注：返回提取后的纯文本，保持与现有调用方一致
}
```
> 返回类型仍为 `String`（提取后的纯文本），`GenerationService` 拿到后照旧 `extractJson(raw)` —— 调用方零改动心智。

`OpenAiCompatibleProvider` 实现（**已有基础**：其 `chat(String,int)` 在 98-103 行本就拼 `messages:[{"role":"user","content":...}]`）：
- 新增 `chat(List<Message>, int)`：把 `Message` 映射成 `[{"role":m.role(),"content":m.content()}]`，复用现有 `sendWithHardTimeout`（147-171 行）与 `extractContent`（186-214 行）；
- 旧 `chat(String,int)` 改为 `return chat(List.of(Message.user(prompt)), int)`，逻辑不变、测试 `OpenAiCompatibleProviderTest` 的 `.chat("...")` 用例**继续绿**。

### D9. `GenerationService` 接入点

| 位置 | 今日 | 改后 |
|---|---|---|
| 199-208 行（首轮造 prompt） | `prompt = promptService.buildPrompt(userText, effectiveSceneJson);` | `List<Message> messages = contextAssembler.assemble(new AssembleInput(userText, effectiveSceneJson, editMode, 1, null, promptVersion));` |
| 235 行（调模型） | `raw = llmProvider.chat(prompt, roundTimeout);` | `raw = llmProvider.chat(messages, roundTimeout);` |
| 330-340 行（修正轮造 prompt） | `prompt = promptService.buildFixPrompt(userText, issues, previousJson);` | `messages = contextAssembler.assemble(new AssembleInput(userText, previousJson, editMode, round, issues, promptVersion));` |
| 构造器 / 字段 | 无 `ContextAssembler` | 注入 `ContextAssembler contextAssembler`；`String prompt` 变量改 `List<Message> messages` |

`promptVersion` 来源：v2-41 先填基线常量（如 `"v1"`，可由 `PromptService` 持有或 `application.yml` 的 `prompt.version`）；**v2-43 接 M11 的递增维护**（改 prompt 内容即 +1）。

---

## 步骤

### v2-41 ContextAssembler（本计划主体）
1. 新建 `client/Message.java`（D1 record）。
2. 扩展 `LlmProvider` 接口（D8）：加 `chat(List<Message>, int)`；旧两个 `String` 重载保留。
3. `OpenAiCompatibleProvider` 实现新重载（D8）：映射 `Message`→API `messages`，复用 `sendWithHardTimeout`/`extractContent`；旧 `chat(String,int)` 改为 delegate。
4. 新建 `context/AssembleInput.java`（D2）、`context/ContextBudget.java`（D4，先落框架 + 契约区 HIGH 豁免）、`context/ContextReplay.java`（D5，落盘 json）、`context/ContextAssembler.java`（D3）。
5. `PromptService` 重构（D7）：删 `buildPrompt`/`buildFixPrompt`，改暴露四个内容块方法；`loadSchema` 保留。
6. `GenerationService` 接入（D9）：注入 `ContextAssembler`，三处调用点改造；变量 `prompt`→`messages`。
7. **测试改写**：
   - `PromptServiceTest`：原断言（"含用户需求""内嵌 schema""列 7 种 type 禁 freedraw""箭头优先绑定""≤40""编辑模式保留"）改挂到对应内容块方法上，语义不变。
   - `GenerationServiceTest`：所有 `when(llmProvider.chat(anyString(), anyInt()))` 改为 `when(llmProvider.chat(anyList(), anyInt()))`；断言 `contextAssembler.assemble(...)` 被调用、且 `chat` 收到 `List<Message>`。
   - `OpenAiCompatibleProviderTest`：`.chat("...")` 用例**不变**（走 delegate），新增 1 例验证 `chat(List.of(system,user), int)` 发出 `messages` 含两条且 role 正确（可用 wiremock / 本地 stub server，沿用现有测试手法）。
8. 跑 `run-verify.ps1` 全绿（后端 + 前端 + checkstyle）。

### v2-42 全量 JIT 嵌入（G2）+ 编辑模式增量 + 每轮回放落盘
1. `ContextBudget.rank` 中契约区（schema 全文）**每轮无条件全量**进 `system` HEAD（G2：零漂移、最稳）；`fit_token_budget` 对契约区返回原文（不降级）。
2. 编辑模式 `buildUserBody`：场景 >30 元素时改"结构摘要 + 被改元素全文 + 引用"（呼应参考模型"上下文卸载"，仅作用于历史/旧图，不作用于契约）。
3. `ContextReplay.dump` 在 `assemble` 末尾落 `context-replay-<session>-<round>.json`（含 `promptVersion`）。

### v2-43 Prompt 版本号（Q3-A）
1. `ContextAssembler` 每轮带上 `promptVersion`（由 M11 维护、内容变更即递增；v2-41 先用基线值）。
2. `promptVersion` 写入 `context-replay-*.json` 与生成日志；暴露读取入口供 M14（评估回归门禁）按版本对照。
3. 不做 Git 式 diff 追踪（仅一个版本号字段 + 日志，近零成本）。

---

## 验收口径（缺一条不算完成）

1. **契约全量 JIT（G2）**：每轮 `system` 消息头**完整内嵌** `scene.schema.json` 全文；电池题 2（历史 143 元素大图）复跑"字段缺失率 = 0、≤40 生效"，与今日一致或更好（不追求 token 下降，追求字段完整）。
2. **Lost in the Middle 治理**：硬约束（≤40、绑定箭头规则）固定在 `system` 头尾；电池题 2 复跑"≤40 生效"（实产 ≤ 阈值）。
3. **每轮回放**：`context-replay-<session>-<round>.json` 落盘，含 `role`/`length`/`tokensEst`/`promptVersion`；回放可区分首轮与修正轮窗口。
4. **迁移零退化**：电池题 2 / 题 4 复跑，出图质量（图形重叠 / 文字互叠 / NaN / 坐标合理性）与今日**逐字节一致或更好**——M10 只改组装，不改出图。
5. **接口解耦**：`chat(List<Message>, int)` 已落地；`OpenAiCompatibleProviderTest.chat("...")` 旧用例仍绿；`GenerationServiceTest` 改为 `anyList()` 断言。
6. **门禁全绿**：`run-verify.ps1` 末行 `VERIFY PASS`、退出码 0（后端 180 测试 + checkstyle + 前端三绿）。
7. **Q3-A（v2-43 验收）**：回放与生成日志含 `promptVersion`；M11 改 prompt 内容后版本号递增、回放可区分前后两版窗口。

---

## 覆盖测试数据

| 场景 | 输入 | 期望 |
|---|---|---|
| 正常·小图 | "画登录流程图" | 契约全量嵌入 system；出图与今日一致；≤40 生效；回放含 promptVersion |
| 正常·大图 | 电池题 2（143 元素历史图） | 契约全量嵌入、字段零漂移；LLM 正确改图（token 不降但契约完整） |
| 编辑模式 | 47 元素图"删掉节点 X" | user 消息含当前场景 + 需求；>30 元素走"摘要+被改元素"；保真通过 |
| 修正轮 | 首轮校验失败带 2 个 issue | 第 2 轮 user 消息含"上一轮校验未通过"块；messages 含 system+user 两条 |
| 异常·预算逼近 | 人为把预算调到很小 | `fit_token_budget` 触发 MID/LOW 压缩（历史/RAG/工具描述），**契约区 HIGH 不降级、不丢 `type`/`required`** |
| 接口·角色 | 调 `chat(List.of(system,user), int)` | 发出 `messages` 两条、role 分别为 system/user；返回提取后纯文本 |
| 版本对照（Q3-A） | M11 改 prompt 后重跑同一题 | 回放中 promptVersion 递增、两版窗口可区分 |

---

## 风险登记

| ID | 风险 | 影响 | 缓解 |
|---|---|---|---|
| **R1** | `LlmProvider` 接口扩展触 **ADR-3**，与 **M13**（网关加固：重试 / token 埋点）可能冲突 | 若 M13 先改接口签名，本计划需返工 | 本计划只加**新重载** `chat(List<Message>, int)`，不动旧 `String` 重载；M13 后续在**同一接口**上加重试/埋点，二者正交、可叠加，无签名冲突。M13 **不必先于**本计划。 |
| **R2** | `PromptServiceTest` 因删 `buildPrompt` 需整体改写 | 测试红、回归盲区 | 改写时把原断言 1:1 挂到新内容块方法；先改测试再改实现，保持"测试定义行为"。 |
| **R3** | 契约区被误压缩导致字段漂移 | 后端解析失败、出图崩 | `fit_token_budget` 对 HIGH 区**白名单豁免**（硬编码"schema 块永不压缩"）；验收口径 1 用电池题 2 锁零漂移。 |
| **R4** | `system` 内容每轮不完全稳定，导致 M13 上下文缓存失效 | 缓存命中率低、降本失败 | D6 第 3 点：本计划保证 `system` 前缀（角色+契约+规则）**每轮逐字节一致**；只有 `user` 变化。验收时可 diff 两轮 `system` 文本相等。 |
| **R5** | "只改组装不改语义"被破，出图退化 | 用户感知"越改越差" | 验收口径 4 用电池题 2/题 4 锁"逐字节一致或更好"；跑 ② 离线 E2E / ③ 真实 LLM E2E 佐证（不只靠门禁）。 |

---

## 编号冲突解决（v2-41 重号）

`m8-layout-planning.md` 曾把"走线"标为 **v2-41（待立）**，与本文（M10 上下文组装 v2-41）**重号**。用户 2026-09-20 已**否定 M8 走线方案**（根因是"该用的组件没用上"，非后端走线重写）。处理方式：

1. 把 `m8-layout-planning.md` 里的"走线 v2-41"**标记为「已否 / 作废」**，并**重编号为 v2-48**（避开与 M10 冲突）。
2. 本计划（M10 上下文组装）**独占 v2-41**，状态置「待审」。
3. 同时在 `m8-layout-planning.md` 顶部加一行说明：走线需求改由"M9 契约 + M11 提示词 + 前端适配"用**既有原语正确组合**解决（绑定箭头折点、`line` 无箭头线），不再做后端走线重写。

> 此项为文档编辑，随本计划**确认后**一并执行（不急于动手）。

---

## 完成后（状态机收尾，按 AGENTS.md 四拍）

1. 本计划从 `plans/underway/v2-41-context-assembler.md` 移入 `state/completed/underway/`（保留原文，顶部加一行"已交付 + 证据：run-verify 退出码 0 / 电池题 2 字段缺失率=0"）。
2. `state/active/v2-40-contract-capacity.md` 同步移 `state/completed/underway/`（v2-40 已实质完成，勿滞留 active）。
3. 更新 `plans/masterPlan/m10-context-engineering.md` 状态行：v2-41 标记「完成」，v2-42/v2-43 标记「进行中/待审」；模块整体仍「规划中」直到 v2-43 收。
4. `state/activeLog/2026-09-21.md` 追加一行：`MM-DD HH:mm v2-41 ContextAssembler 已交付 → 证据(run-verify PASS / 电池题2字段缺失率=0 / 回放文件 sample)`。
5. 改动交用户提交（**git 写操作由人执行**，Agent 不代跑 commit/push）。

---

## 附录 A：本计划不做的（给用户吃定心丸）

- ❌ 不重写后端走线 / 路由（用户已否，改由 M9+M11+前端用既有原语组合解决）。
- ❌ 不引入"越界 / 拒画"任何逻辑（用户："什么都能画，没有越界一说"）。
- ❌ 不动导出（超出本计划范围）。
- ❌ 不碰 `graph/` 布局 / 校验 / 绑定 / 排版（归 M8/M9）。
- ❌ 不接 RAG / 真 ReAct 历史（归 M2/M4，本计划只留地基与钩子）。

## 附录 B：文件级改动清单

| 文件 | 改动类型 | 具体内容 | 注意 |
|---|---|---|---|
| `client/Message.java` | 新增 | `record Message(String role, String content)` + `system()/user()` 工厂 | 落 client 包，作网关契约 |
| `client/LlmProvider.java` | 修改 | 加 `chat(List<Message>, int)` 重载；旧两重载保留 | R1：与 M13 正交 |
| `client/OpenAiCompatibleProvider.java` | 修改 | 实现 `chat(List<Message>, int)`；旧 `chat(String,int)` 改 delegate | 复用 147-171 / 186-214 |
| `context/AssembleInput.java` | 新增 | 组装入参 DTO（D2） | — |
| `context/ContextBudget.java` | 新增 | 四档 rank + fit_token_budget（契约区 HIGH 豁免） | R3 白名单豁免 |
| `context/ContextReplay.java` | 新增 | 每轮回放 json 落盘 | 运行产物，不入库 |
| `context/ContextAssembler.java` | 新增 | 组装主体（D3） | @Service 注入 PromptService/Budget/Replay |
| `service/PromptService.java` | 修改 | 删 `buildPrompt`/`buildFixPrompt`；改暴露 4 个内容块方法；`loadSchema` 保留 | D7；R2 测试改写 |
| `service/GenerationService.java` | 修改 | 注入 `ContextAssembler`；199-208/235/330-340 三处改造；`prompt`→`messages` | D9 |
| `service/PromptServiceTest.java` | 修改 | 断言挂到新内容块方法 | R2 |
| `service/GenerationServiceTest.java` | 修改 | `anyString()`→`anyList()` | — |
| `client/OpenAiCompatibleProviderTest.java` | 修改 | 旧 `.chat("...")` 不变；新增 `List<Message>` 用例 | — |
| `plans/masterPlan/m8-layout-planning.md` | 修改 | 走线 v2-41 → 标记「已否/作废」+ 重编号 v2-48 | 编号冲突解决；确认后执行 |
| `plans/masterPlan/m10-context-engineering.md` | 修改 | v2-41 标记完成；状态行更新 | 完成后步骤 3 |
| `state/active/v2-40-*.md` → `state/completed/underway/` | 移动 | v2-40 归档 | 完成后步骤 2 |

---

## 收尾记录（2026-09-24 实际交付，含与本文的差异）

**状态：已完成**，已移入 `state/completed/underway/`。

### 证据
- `mvn -o test` = **180 通过 / 0 失败**（原 175 + 新增 5）。
- 「只搬家不改字」机械证据：新旧 `buildPrompt` 与 `buildSystemBlock()`+`buildUserBlock()` 的**双引号字面量逐条比对** = **76 : 76，MISMATCH 0**；修正块同样一致。
- 新增用例：`OpenAiCompatibleProviderTest` 4 例（system/user 顺序、旧入口仍单条 user、空列表拒绝、`Message` role 校验）+ `GenerationServiceTest.callSendsSystemAndUserMessages` 1 例。

### 与本文设计的差异（都以"能对上、但有取舍"为前提）

| 项 | 本文原设计 | 实际落地 |
|---|---|---|
| 静态层来源 | 读 `resources/prompts/whiteboard/system-prompt.md` | **仍读 `PromptService` 的 Java 字符串**；canonical markdown **刻意不接**（口径 v5.7.0/v5.8.0 未收敛 + markdown 会把 10 个 relation 写死、丢 `RelationCatalog` 单一真相源） |
| `fit_token_budget()` | 四档优先级压缩 | **只留框架 + 「契约区永不降级」白名单**；其余三档当前无内容可压，不写死代码 |
| `rank()` | 硬约束固定 `system` 首尾 | **固定布局**（system 一块 + user 一块，顺序固定） |
| `ContextReplay` 落盘内容 | 角色 / 长度 / token 数（+ 可选脱敏全文） | 只存**元数据**（role / 字符数 / token 估算 / zone），不存正文 |
| 包依赖 | `context/` 只依赖 `client/` | **注入 `service.PromptService`**（包级反向依赖换简洁；代码注释已标技术债） |
| `AssembleInput` | 主签名含 history/memory/toolResults | 收进 **`Extras` 子记录** + `of()` 工厂，主签名保持 7 参 |
| `PromptService` 旧方法 | 删 `buildPrompt` / `buildFixPrompt` | **保留为 delegate**（= system 块 + user 块）⇒ `PromptServiceTest` 零改动、无第二份文案 |

### 门禁例外
`run-verify.ps1` 第 3 步 checkstyle 当前为红，4 条违规**全部先于本计划存在**（`PromptService.java:121/126` 两条超长行在原文件 L107/L112 即已超长、长度一字不差；`test/graph/AbValidatorCheck.java:3,7` 两个无用导入，本计划未触碰该文件）。故本文「验收口径 6 / 门禁全绿」这一条**未达成**，记档待清。

### 顺带发现并修复（超出本计划范围）
`src/main/resources/schemas/scene.schema.json` **是坏 JSON**（`arrow` 对象删 `oneOf` 时连带删掉收尾大括号）→ 前端冒烟 `scene-smoke.mjs` 崩在第 16 例。后端不解析该文件（当纯文本嵌 prompt），故 `mvn test` 全绿掩盖了它。已补回大括号：冒烟 **17/17 PASSED**。
