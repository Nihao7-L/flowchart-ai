# M10 静态层设计方案（基于参照库方法论重设计 · 2026-09-22）

> 状态：**已落地（2026-09-24，随 v2-41）** —— 落地形态与本文设计有 7 处取舍差异，逐条对照见 `plans/masterPlan/m10-context-engineering.md`「落地记录」。本文保留为设计记录，**读代码时以落地记录为准**。
> 关联模块：M10 上下文工程（masterPlan/m10-context-engineering.md）
> 设计依据：用户 Obsidian 参照库 `F:/ProgramData/Obsidian Data/你好/references`
> - 方法论总结：上下文工程方法论 / Prompt编写方法论 / 企业级上下文设计模式_实例对照
> - 社区精选：四层上下文栈 / 大厂提示词清单 / 五个上下文技巧 / 别把上下文当自然而然 / 从提示词到挽具工程
> - 实例库：Manus(Modules/Prompt) / NotionAI(Prompt) / Claude Code(2.0)
> 创建：2026-09-21 · 重设计：2026-09-22

---

## 一、定位：上下文工程，而非"写好一段提示词"

本文件设计的是 **M10 静态层**——即每次 LLM 调用都固定携带的「系统指令 + 契约 + 护栏」。**动态层**（用户需求、当前场景、历史、校验 issues、RAG 背景）由 `ContextAssembler` 每轮策展注入，不在此文件定稿。

核心心智模型（来自参照库）：

- **上下文窗口 = 有限 RAM**（工程师笔记）：注意力预算固定，每多一个 token 就稀释聚焦力。目标 = **最小高信号 token 集**。
- **四策略 写·选·压·隔**（LangChain/Anthropic）：静态指令**写**进稳定前缀；动态内容**选**需而入；历史/RAG **压**缩；大对象/错误**隔**离。
- **静态/动态分离 + KV-cache 稳定前缀**（Claude Code 范本）：静态层不变 → 前缀计算可缓存复用，省时省钱；动态层单独成块。
- **关键约束固定首尾**（Anthropic 长上下文技巧）：治 Lost in the Middle（中间迷失）。

---

## 二、静态层 / 动态层分离

| 层 | 内容 | 变化频率 | 来源 | KV-cache |
|----|------|----------|------|----------|
| **静态层（本文件 + 契约区）** | 角色 / 上下文 / 契约(schema) / 护栏 / 任务流程 / few-shot / 输出格式 | 仅规则/契约变更时变 | 配置文件 + `scene.schema.json` | **前缀稳定，可缓存** |
| **动态层** | 用户需求 / 当前场景 / 历史对话 / 校验 issues / RAG 背景 | 每轮变 | user 消息 + SessionStore + 修正模式 | 不缓存，每轮重算 |

> 设计红线：**静态层正文（frontmatter 除外）必须完全稳定**。任何会导致前缀变化的改动（如改版本号文本、加时间戳）都会让 KV-cache 失效（MeshedSociety 技巧①）。版本号走 frontmatter 元数据 + 回放日志，不进正文。

---

## 三、静态层分区（XML/Markdown 块，对应 Manus 模块骨架）

借鉴 Manus `<Modules>` 的"一关注点一标签"思路 + NotionAI 域概念 XML 化，把 system 正文切成清晰区块，模型按标签定位、互不污染：

| 区块（标签） | 内容 | 借鉴 |
|------|------|------|
| `<role>` 角色 | 白板 AI 助手，唯一职责=转 JSON；正面约束+替代路径 | NotionAI 域概念 / 大厂清单①身份 |
| `<context>` 上下文 | 所属系统/下游/技术约束/契约来源 | Claude Code system-reminder 思路 |
| `<contract>` 契约区 | 运行时全量注入 `scene.schema.json`（G2） | G2 全量 JIT |
| `<guardrails>` 护栏 | G1~G10，每条带「原因+替代路径」 | 企业级模式④别过度执行 / 正面约束 |
| `<workflow>` 任务流程 | 5 步内部思考，模糊/矛盾处理 | Manus `<agent_loop>` 操作手册 |
| `<fewshot>` 示例 | 3 个多样化示例（简单/复杂分组/编辑保真） | MeshedSociety ⑤ / Claude Code 示例 |
| `<output>` 输出格式 | 单 JSON、无围栏、数字类型、铁律重申 | Anthropic prefill / OpenAI 格式 |
| `<traceability>` 可追溯 | z-order/relation/group 决策可追溯 | 原设计保留 |

> 当前运行时（OpenAiCompatibleProvider）把整段 prompt 作为**单条 user 消息**发送，暂无独立 system 消息。v2-41 落 `ContextAssembler` 后，应把上表静态层放进 `system` 消息、动态层放进 `user` 消息——分区标签同时提升可读性与「按标签定位」能力。

---

## 四、rank()：关键约束固定首尾

`rank()` 把最高优先级约束钉在 system 消息**首尾**，中段放可压缩/可阶段加载的内容。映射（治 Lost in the Middle + 四失败模式）：

```
[首] 铁律 TL;DR（G2 纯JSON / G10 只译需求不泄漏 / G1 仅7元素）   ← 防 中毒+分心
[高] <role> <context> <contract(全量schema)>
[高] <guardrails> G1~G10（含原因+替代路径）                     ← 防 混淆+冲突
[中] <workflow> 5步 + <fewshot> 3示例
[尾] <output> 输出格式（单JSON/数字类型/无围栏） + <traceability>  ← 防 分心（末位强调）
```

四失败模式压测（LangChain/Karpathy，给改图跑偏做定位）：
- **中毒 Poisoning**：历史/校验 issues 里的错误被反复引用 → 修正模式只给「错误+正确解」，不塞原始错误原文（MeshedSociety ③）。
- **分心 Distraction**：无关长文淹没信号 → 动态层裁剪，静态层不含噪声。
- **混淆 Confusion**：重复说明导致格式错 → 护栏用「正面约束+替代路径」而非堆禁止项（Prompt 方法论⑥）。
- **冲突 Clash**：前后指令矛盾 → 静态层内部不自相矛盾（修掉旧版「G2 禁围栏但示例用围栏」的矛盾）；frame 上色口径 G9 与 schema 一致。

---

## 五、fit_token_budget()：四档优先级 + 错误留上下文

四档（工程师笔记三支柱 + 企业级模式）：

| 档 | 内容 | 动作 |
|----|------|------|
| 高（固定） | system 约束 / 当前目标 / 安全边界 / **契约区全量** | 永不降级（G2：不省 token 牺牲契约完整） |
| 中 | RAG 背景 / 旧工具结果 | 二次裁剪，留引用（"哪来的"） |
| 低 | 早期历史 | 摘要压缩（信号保留，非截断） |
| 阶段 | 工具描述 / Schema（按任务阶段加载卸载） | 新建/编辑/修正分阶段 |

**错误留上下文（修正模式，MeshedSociety ③ / Manus）**：当 user 消息带上一轮校验 issues，组装为 `<correction>` 块，格式——`(ERROR) 字段X类型错 (CORRECTION) 应写数字 (NOTE) 原因：字符串会被当NaN`。模型据此避免重复犯错。**校验 issues 是动态层，不污染静态层**。

---

## 六、工具面 ≤30（本项目的"工具"=元素/关系种类）

MeshedSociety 实测：>30 工具性能恶化、>100 几乎崩溃。本项目的"可输出面"即元素类型(7) + relation 枚举(10) + 样式字段(~12) ≈ 29，**刚好在红线内**。设计约束：
- 不擅自新增元素/关系种类（属契约变更，走 schema 评审）。
- 每个"工具"（元素/关系）在契约区写清**何时用/何时不用/出错怎么办**（对齐 NotionAI `<tool calling spec>` 的精细描述）。
- 关系选择给「默认策略」：流程→`flow`、调用→`call`、数据→`data`，减少模型犹豫（MeshedSociety ④）。

---

## 七、few-shot 多样化 + 防过度执行

- **few-shot 多样化（MeshedSociety ⑤）**：3 示例覆盖「简单流程 / 复杂分组 / 编辑保真」三种表述与复杂度，逼模型泛化而非照抄（修旧版"极简需求照搬 n1/100,100/开始"的模仿偏置）。
- **防过度执行（NotionAI `Avoid overperforming` / Claude Code「不多做不少做」）**：
  - 正面写「该做什么」+「停止条件」：用户没要求的图元/样式/解释一律不加。
  - 编辑模式 G7：只改点名元素，其余逐字照搬。
  - G10：模糊/自相矛盾需求 → 最小合法场景，不反问、不画元指令（修旧版 w05 指令泄漏）。

---

## 八、预填充强制 JSON（给 v2-41 的建议，Anthropic 战术⑥）

当前 provider 发单条 user 消息、无 prefill。建议 v2-41 在 `system` 后追加一条 `assistant` 前缀消息，内容仅 `{`（或 `{"elements": [`），**强制续写合法 JSON**，从机制上根除 G2 围栏问题、降低重试率。属装配层改动，不改 prompt 文本语义，与本文解耦。

---

## 九、Harness 五层（治理层 checklist，来自"从提示词到挽具工程"）

长运行 Agent 的安全束带，映射到 flowchart 治理层：

| Harness 层 | 本项目落点 |
|-----------|-----------|
| ① Limits 限制 | 沙箱/只读/契约 `additionalProperties:false` 硬拒非法字段 |
| ② Instructions 指令 | 本静态层 + `AGENTS.md`；每次出错就更新规则（如 G8/G9/G10 即迭代沉淀） |
| ③ Checks 检查 | 确定性门禁：schema 校验 / `SceneDrift` 编辑保真 / 美观检查（frame 不越界） |
| ④ Recovery 恢复 | 重试计数 / 回滚 / 缩范围（修正模式把 issues 喂回） |
| ⑤ Review 审查 | M14 评估回归门禁（A/B 对照 promptVersion） |

---

## 十、与 M10 / M11 衔接（既有决策不变）

- **G2 全量 JIT**：契约区每轮全量注入，不降级、不摘要、零漂移。
- **Q3-A promptVersion**：frontmatter `prompt_version` 由 M11 在改文本时递增；M10 每轮组装带上并写入 `context-replay-<session>-<round>.json`。
- **rank()/fit_token_budget()**：见第四、五节；v2-41 实现，与 prompt 文本换版解耦。
- **frontmatter 剥离**：ContextAssembler 读 `system-prompt.md` 时只把 `---` 之后正文作为 system 消息，frontmatter 仅取版本号。

---

## 十一、设计依据（方法论 → 本项目落地映射）

| 参照库来源 | 借鉴的模式 | 落到本项目 |
|-----------|-----------|-----------|
| 上下文工程方法论（四策略/静态动态/KV-cache） | 写选压隔、稳定前缀 | 第二节静态/动态分离、第二节 KV-cache 红线 |
| 上下文工程方法论（四失败模式） | 中毒/分心/混淆/冲突压测 | 第四节 rank + 四失败模式压测清单 |
| 上下文工程方法论（系统提示词护栏） | XML 分区、最小高信号集 | 第三节分区标签、首/尾铁律 |
| 四层上下文栈（HackerNoon） | 指令/知识/记忆/工具四层 | 第二节分层表（本项目的"工具"=元素种类） |
| 大厂提示词清单（GitHub） | 五原则 + 用例指南 | 第三节角色/工作流/通信格式；第七节防过度执行 |
| 五个上下文技巧（MeshedSociety） | ①标签②摘要③错误留上下文④工具≤30⑤few-shot多样 | 第三节标签、第五节修正模式、第六节工具面、第七节示例 |
| 别把上下文当自然而然（工程师笔记） | 摘要/分块/有状态、上下文预算 | 第五节 fit_token_budget 四档 |
| 企业级上下文设计模式_实例对照 | ①XML模块骨架②静动分离③域概念④别过度执行⑤few-shot⑥信息优先级 | 第三节分区、第二节分离、第七节防过度执行 |
| Manus Modules | XML 模块骨架 / `<agent_loop>` / `<tool_use_rules>` 禁编工具 | 第三节分区、第四节 workflow |
| NotionAI Prompt | `<tool calling spec>` 精细描述 / `Avoid overperforming` | 第六节工具描述、第七节防过度执行 |
| Claude Code 2.0 | 静态/动态 `<system-reminder>` / 示例定详略 | 第二节分离、第七节示例 |
| Prompt编写方法论（Anthropic/OpenAI） | 正面约束+替代路径 / prefill / 首尾关键指令 | 第三节护栏带替代路径、第八节 prefill、首/尾铁律 |
| 从提示词到挽具工程（HackerNoon） | Harness 五层 | 第九节治理层 checklist |

---

## 十二、验收（迁移不退化，对齐 masterPlan 验收口径）

1. 契约每轮全量嵌入（G2），大/小图字段零漂移。
2. 关键约束（≤40、绑定箭头、G8 数字类型、G9 frame 不上色）固定首尾，电池题复跑生效。
3. **新增回归点**：自相矛盾/模糊输入不再把提示词步骤画成图（G10）；frame 不再上色（G9）；坐标/尺寸全为数字（G8）。
4. 每轮回放含 `promptVersion`；M11 改文本后版本号递增、两版窗口可区分（Q3-A）。
5. 编辑模式保真：未点名元素逐字保留（G7）。
