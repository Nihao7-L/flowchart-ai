# v2-55（前置核）· Agent Loop 最小核：messages 单线积累 + 工具派发

> 状态：**步骤 1–3 全部完成并实测通过（2026-10-13，用户授权"提交→下一步→刁难测试→有问题再改"）**：
> ① `agent/` 四件套 + 4 工具 + 21 单测全绿；
> ② 探针 `tools/e2e/probe_tool_calls.py` 实测 4 候选 tool_calls 支持度（glm / deepseek / grok 全过，gpt-6.1-sol 欠费）→ agent 模式候选子集 = 现有前三，无需砍人；
> ③ 真实 smoke（deepseek）：steps=1 一轮直通 COMPLETED，32 元素流程图零丢弃；
> ④ **接线完成并实测**：`AgentLoopService`（flag `chartflow.agent-loop.enabled`，yml 已显式 true）+ `GenerationService.generateViaAgent`（会话状态/落库/兜底与 legacy 同纪律）+ SSE `tool_call`/`tool_result`（前端忽略未知类型，零前端改动）+ `AgentLoopListener`（观测回调异常不打穿生成）。
> **实测补丁 2 个（E2E 中发现并修复）**：`FailoverAgentClient`（glm 大图实测 502 ⇒ 单候选一步失败=整次兜底；一步内按序切候选+粘性主选，正式冷却/分类归 M13）；编辑保真缺口（SceneGate 移植 detectRewrite + 注入 SceneDrift，只拦首次提交 = legacy 第 1 轮口径）。
> **E2E 刁难 7/7（8082 真实 LLM，工装 `%TEMP%\chartflow_render\agent_e2e.py`）**：大图四层分区 179s ✓ / 契约违规诱导触发修正轮 ✗→✓ / 模糊需求出示例+【AI 备注】✓ / 编辑改名保真 ✓ / 重画诱导首提交被拦（预期）→二次放行 ✓ / 时序图 read_contract 按需加载被模型真实调用 ✓。截图 `shots/agent_e2e_*.png` 7 张。
> **遗留（如实）**：契约违规组观感差（模型顺"纯文字节点"违规要求、连线混乱）= 校验器不查观感，归 ReadabilityGate（M4 后半）；SceneGate 与 GenerationService 双份管线待收口（步骤 4 对照后"谁存活删谁"）。
> 下一步 = 步骤 4：电池题双路径对照（agent vs legacy），指标入 activeLog；步骤 5 归档 + m4 状态行。
> 来源：用户 2026-10-13 拍板「先把 messages 和 tool 做起来，就像 learn-claude-code 的 s01+s02」——即把 M4（G1 真 ReAct，2026-09-20 已拍板）的**核**提前先行，其余（编辑工具/无进展检测/checkpoint/NDJSON）保持后置。
> 上级计划：`plans/masterPlan/m4-loop-engineering.md`（本文件是其 v2-55 的工作台副本，收窄范围）。
> 参照：learn-claude-code s01（messages[] + while 循环 + tool_use 驱动）、s02（TOOL_HANDLERS 派发表）。

## 一、目标（一句话）

把 `GenerationService.generateInternal()` 里「隐式 for 轮次循环 + 每轮重建 prompt」换成 **一份 `messages[]` 单线积累 + 工具派发表驱动的 agent loop**：模型通过 `tool_calls` 提议动作，服务端执行后把 Observation（观察结果）追加回 `messages[]`，循环继续/退出由闸门决定。现有校验器/几何审计/故障转移**全部保留，降格为工具与观察**，不重写。

## 二、为什么这样切（设计理由，防跑偏）

1. **messages 单线积累**（s01 的本体）：现在每轮都重建 prompt（system + 需求 + 判决书），模型看不到自己上一轮干了什么。改成一份 messages 从头追加到尾后：模型天然看见自己的历史动作与失败结果——Manus"保留错误"、12-factor#9（unify execution state）在这里自动成立，previousJson 压缩问题也顺势缩小（历史在消息里，不在拼接的 prompt 里）。
2. **KV-cache 友好**（Manus 第 1 条）：system 块字节级不动 + messages **只追加不回头改** + 确定性序列化 ⇒ 前缀缓存命中率最大化。这条是纪律，写进验收。
3. **submit_scene 工具 = 结构化输出免费拿**：让模型把整份场景 JSON 作为**工具参数**提交（`submit_scene(scene={...})`），厂商的 function calling（函数调用）参数本来就是强 JSON ⇒ parseGate 对提交动作天然成立；截断/畸形参数走现有冷却与重试。
4. **工具集必须小**（教训来自课程与本项目双重验证）：只给 4 个领域工具，**不给** Bash/文件/通用工具——这是垂直生成管线，不是编码 agent。工具越多，模型迷路概率和 token 成本都涨。
5. **legacy 保底**：feature flag 切换，老管线原样保留。原因：候选模型对 `tool_calls` 的支持度参差不齐（免费档尤其），没探明前不能全押。

## 三、范围

**新增（`agent/` 包）：**

| 件 | 职责 | 对应课程 |
|---|---|---|
| `AgentLoop` | while 循环：调模型 → 解析 `tool_calls` → 派发 → Observation 追加回 messages → 闸门判断退出 | s01 主循环 |
| `AgentMessages` | 一份消息列表的封装：append-only、确定性序列化（进 prompt 前排序/去抖）、步数与预算记账 | s01 `messages[]` |
| `ToolRegistry` | `Map<String, AgentTool>` 派发表 + 工具 schema 清单（进 system 的 tools 定义） | s02 `TOOL_HANDLERS` |
| `AgentTool` 接口 | `name / description / parametersSchema / execute(args, ctx) -> Observation` | s02 工具接口 |
| 4 个工具（下表） | 首批领域工具 | — |

**首批工具集（只有这 4 个）：**

| 工具 | 参数 | 返回（Observation） | 复用的现有件 |
|---|---|---|---|
| `submit_scene` | `scene`（整份场景 JSON） | 受理回执；触发校验闸门 | Binder→Dedup→Sanitize→claimFramesOnly 校验链、parseGate 语义 |
| `validate_scene` | 无（校验当前已提交场景） | 点名化 issue 三元组清单（含 GeometryAudit 几何项） | GraphValidator、GeometryAudit（NAMED_CAP=8） |
| `read_scene` | 可选 `focusIds[]` | 场景摘要：元素统计 + 指定元素全量（修正轮"点名元素摘录"的正规化形态） | scenePlanner 摘要逻辑 |
| `read_contract` | `section`（元素类型名） | 该类型的契约片段（按需加载，不全量塞） | PromptService 契约段派生 |

**修改：** `GenerationService`（循环体替换为 AgentLoop 接线 + feature flag `chartflow.agent-loop.enabled`，**默认 false**）；`DiagramController` SSE 事件补 `tool_call/tool_result` 两类（过程可见，前端可先忽略）。

**不做（明确出界）：** 编辑工具（归 M3）、检索工具（归 M2 RAG）、无进展检测 / checkpoint / run journal（v2-56）、NDJSON 流式、独立需求评估器（v2-55 后半）、ReadabilityGate 美观项（G4 后半）。**每个"不做"都在后续子计划里，不是砍掉。**

## 四、循环语义

```
messages = [system(稳定块), user(需求)]
while steps < MAX_STEPS(8) && 预算未耗尽:
    resp = failover.chat(messages, tools=registry.schemas())
    for call in resp.tool_calls:            # s02：一批工具调用全部执行
        observation = registry.dispatch(call)
        messages.append(user(tool_result(call.id, observation)))
    if resp 提交了 submit_scene:
        gate = 校验链(scene)                # 复用现有闸门
        if gate.issues 为空: 落库 + SSE 终态 + return
        else: messages.append(user(修正指令: 点名化 issue))  # 不通过就继续
    else:
        messages.append(...)                # 无工具调用的纯文本轮：回喂"请用工具或提交"
超限 → 输出最后一份合法 partial + 原因（不伪装成功，s17 原则）
```

- `MAX_STEPS=8` 与总预算（`totalBudgetMs()`）双闸门，沿用 8d661b9 口径。
- 每批 `tool_calls` **全部执行完再回喂**（s02 纪律：不留孤立 tool_result）。
- 超时/空内容/截断处理**原样复用** FailoverLlmProvider（`70e501b` 之后的口径）。

## 五、验收口径

1. flag 开启后：电池题 2（流程图）/ 题 4（时序图）端到端出图，硬指标（NaN/重叠/空图）不劣于 legacy 路径；SSE 能看到 tool_call/tool_result 事件。
2. flag 关闭：legacy 路径回归零变化（`mvn -o test` 全绿）。
3. KV-cache 纪律：同一会话内发给厂商的 messages 前缀字节级稳定（system 不动、只追加）——用现有 ContextReplay 落盘抽查。
4. `read_contract` 按需注入：system 里**不再**全量塞契约（对比两次请求的输入 token，契约段按调用拆分）。
5. 探针先行：`tools/e2e/probe_tool_calls.py`——逐候选验证 `tool_calls` 支持度（能否稳定返回结构化参数、并行调用、中文参数），**探针不过的候选在 agent 模式下不入选**（failover 候选表按 flag 分流）。
6. 门禁四绿：`mvn -o test` / checkstyle / tsc / `scene-smoke.mjs`。

## 六、风险与前置（不粉饰）

| 风险 | 处置 |
|---|---|
| **候选模型 tool_calls 支持度参差**（最大风险） | 验收 5 的探针先行；不支持的工具候选仍服务 legacy 路径 |
| **成本放大**：每个工具轮 = 1 次模型调用 | MAX_STEPS=8 + 总预算双闸门；submit_scene 争取 1~2 步内完成（提示词引导"先想后交"） |
| 工具参数=整份场景 JSON，超大图单参数溢出 max_tokens | 沿用 Candidate.maxTokens；截断走现有冷却+重试；v2-55 后半再考虑分节提交 |
| 循环打转（模型反复 read 不提交） | MAX_STEPS 兜底；v2-56 无进展检测正式解决 |
| messages 变长撑爆窗口 | 修正指令用点名化 issue 摘要；read_scene 返回有界；窗口预算复用 ContextBudget |

## 七、步骤（确认后按此动码）

1. `agent/` 四件套接口 + 4 工具（复用现有校验链，纯包装）——**不接 GenerationService**，先单测。
2. 探针 `probe_tool_calls.py` 跑全候选 → 出支持度表 → 定 agent 模式候选子集。
3. AgentLoop 接线 GenerationService + feature flag + SSE 两类新事件。
4. 电池题 2/4 双路径对照（agent vs legacy），指标入 activeLog。
5. 收尾：按状态机归档、更新 m4 状态行。

## 附：关键接口签名（骨架级，完整可粘贴代码待确认后产出）

```java
public interface AgentTool {
    String name();
    String description();
    Map<String, Object> parametersSchema();          // JSON Schema（函数参数定义）
    Observation execute(JsonNode args, ToolContext ctx);
}

public final class ToolRegistry {                    // s02: TOOL_HANDLERS
    private final Map<String, AgentTool> handlers;   // 不可变，启动时装配
    public Observation dispatch(ToolCall call, ToolContext ctx) { ... }
    public List<Map<String, Object>> schemas() { ... }  // 进请求的 tools 定义
}

public final class AgentLoop {                       // s01: while + tool_use
    // 依赖: LlmProvider(经 Failover)、ToolRegistry、闸门、SSE 发射器
    public AgentResult run(List<Map<String, Object>> seedMessages, RunBudget budget) { ... }
}
```
