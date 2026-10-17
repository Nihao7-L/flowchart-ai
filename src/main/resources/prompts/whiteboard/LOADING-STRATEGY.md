# 提示词加载策略调研（LOADING FOCUS）

> 本文件只回答一件事：**提示词/图型模板「怎么加载」**——何时装载、怎么路由命中、怎么热更新、怎么缓存贵的前缀。
> 治理五层模型（资产化/契约化/预算/评测/运行期）见 `GOVERNANCE-OPTIMIZATION.md`，本文是其中「加载与装配」这一层的深挖。
> 调研时间 2026-10-17，来源：GitHub 仓库 + 工程博客 + 厂商官方文档。

---

## 我们现在的加载链路（基线，方便对照）

```
请求进来
  → 内核(system-prompt.md)        永远发
  → 契约段(schema 现算)           永远发（稳定前缀）
  → 图型模板(diagrams/*.md)       仅当用户原话命中某模板 keywords 才注入，且拼在最后
装配顺序：内核 + 契约 + 图型段   （图型段必须最后，保证 KV-cache 公共前缀不被切碎）
热加载：classpath 内置 < 外部目录(默认空)；WatchService 监听 + 250ms 防抖；解析失败保旧；删外部文件回退内置
```

结论先说：**这条链路方向对，大部分点都踩在工业界正确线上**。下面 24 条是「贴肉优化点」，不是推倒重来。

---

## A. 何时加载：懒加载 / 预加载 / JIT

**1. 懒加载胜过急切全量预载（Lazy beats eager）**
- 来源：agentic-harness-patterns（GitHub）/ Moltbook "lazy-loading context" / Redreamality 渐进披露
- 机制：不在会话开始灌进所有上下文，只在某个能力被激活时才装载它的正文。一次 JIT 拉取的延迟，通常比每轮都背着用不到的 token 便宜。
- 落到 ChartFlow：我们已是这思路——25 种图型正文**默认不进上下文**，只有命中关键词的 1 种才注入。✅ 已对齐。可补：图型模板里若引用「公共片段」（见策略 16），公共片段也应懒装配，别在装配内核时全拼进去。

**2. 三档渐进式披露（3-tier progressive disclosure）**
- 来源：agentic-harness-patterns（GitHub）
- 机制：每类上下文分三层——①元数据(~100 token：名称/描述/触发提示，常驻)；②指令(<5000 token：激活后才装)；③资源(无限：显式请求才装)。
- 落到 ChartFlow：当前 ①档=模板 frontmatter 的 `keywords`(已常驻内存做路由)，②档=模板 body(命中才装)。**缺 ③档**：模板若想引用大段参考图例/样例，应走「模型显式要才给」而不是默认拼进 body。建议模板 frontmatter 加可选 `resources` 字段，装配时只放引用句，正文按需另取。

**3. AOT vs JIT：单轮问答偏 AOT，探索型偏 JIT**
- 来源：Jatin Bansal "Context Engineering: JIT vs AOT"
- 机制：AOT=调用前一次性拼好（单轮 QA 友好，前缀稳定利于缓存）；JIT=给模型工具让其循环自取（探索型任务友好）。平衡点=任务多变度/平均检索跳数/缓存复用率。
- 落到 ChartFlow：出图是**单轮结构化生成**（一次 submit_spec 落库），天然适合 AOT——把图型模板在 system 块里一次性拼好即可，不必给模型开「检索模板」的工具。✅ 当前架构正确，不要为了「更动态」反而退化成 JIT。

**4. 冷热点混合（高频 Eager，低频 Lazy）**
- 来源：xuezishi "逐步展开/按需加载" 跨领域对照（OS 缺页 / Vite 懒加载 / LLM MoE）
- 机制：高频核心规则、固定元信息用 Eager 预加载（避免反复往返）；低频大块用 Lazy。
- 落到 ChartFlow：**内核+契约=Eager 常驻**（每个请求都发，且是缓存前缀）；**图型模板=Lazy**（只有 1/25 的请求需要某一类）。这条我们已经是冷热点混合，符合范式。

**5. 承诺记忆化（memoize the promise, not the result）**
- 来源：agentic-harness-patterns
- 机制：并发请求同一昂贵上下文时，记忆「未完成的 promise」让所有调用方共享同一次 I/O，而非各自发起重复加载。
- 落到 ChartFlow：启动时扫目录注册模板、以及外部目录 WatchService 触发的重编译，**应做成共享的单次操作**（我们的 `DiagramPromptRegistry` 用 `AtomicReference` 持有不可变快照，天然满足）。✅ 已对齐，注意不要让每个请求各自 `Files.readAll` 重读磁盘。

**6. 在变更点失效，而非靠计时器失效**
- 来源：agentic-harness-patterns
- 机制：基于时间/TTL 的缓存要么喂陈旧数据要么重建过频；应在已知变更点显式调用失效。
- 落到 ChartFlow：我们的热加载是**事件驱动**（文件变更→重编译→原子替换），不是定时轮询，正好踩对。✅ 唯一补强：外部目录删除文件时，要显式回退到 classpath 内置快照（已在做），且日志要打「回退」事件便于排障。

---

## B. 怎么路由命中：确定性 / 语义 / 意图 / 位置权重

**7. 确定性关键词路由（不加 LLM 分类那一跳）**
- 来源：本项目的 `DiagramPromptRegistry.forRequest`（按 frontmatter `keywords` 在用户原话包含匹配，命中多个取最长）
- 机制：路由判据是字符串包含，不是让模型判断。省一次 LLM 调用 = 省几秒 + 省一次失败面。
- 落到 ChartFlow：✅ 这是相比「意图分类 Router」的核心优势。社区 Router 范式（小模型先分类输出 intent+confidence+other 兜底）**不采用**——你的 SLA 是百秒级，多加一跳不划算，且你已有「未命中降级不注入」的兜底。

**8. 长词压短词，防抢答**
- 来源：本项目实战（组织架构图→org-chart 不被「架构图」抢；跨职能流程→swimlane 不被「流程图」抢）
- 机制：易冲突图型的关键词必须比对手更长更具体，否则被高频短词吞掉。
- 落到 ChartFlow：✅ 已落地，且应用启动时可用单测 `longestKeywordDisambiguates` 锁死。新增图型必须跑这条断言。

**9. 路由结果必须落日志**
- 来源：社区 Router 踩坑总结（类别别超 30 个、必须有 other 桶、路由结果进日志）
- 机制：路由错了无从调试，日志是唯一抓手。
- 落到 ChartFlow：建议在 `forRequest` 命中/未命中都打一行 DEBUG（命中哪个 diagramType、原话 hash）。目前只打「已加载 N 个」。补一行结论日志即可。

**10. 意图门卫 + 预算分配（JIT 架构 Stage 1）**
- 来源：DEV.to "Context Window Paradox" JIT 架构
- 机制：轻量分类器先把请求分成 Factual/Analytical/Conversational，再决定检索预算与路径。
- 落到 ChartFlow：可借鉴为「图型识别前置」——在装配前先用关键词判出 diagramType，再决定要不要额外装载该图的「资源档」(策略 2 的③档)。与策略 7 同构，只是把「装载什么」做成按图型分级。

**11. 位置权重：前 500 词指令权重最高（Lost-in-the-middle）**
- 来源：Anthropic / Redis "Context assembly"；GPT-3.5 把答案埋在第 10/20 文档时准确率跌到 52.9%
- 机制：模型对输入首尾利用最好、中间最差（U 形曲线）。
- 落到 ChartFlow：图型模板里的**最关键规则（如「edges 数组序即时间序」）应放在该段开头**，不要把重要约束埋在段尾。装配时图型段拼在契约段之后、用户问题之前，位置天然偏前，利于命中。

---

## C. 装配顺序与 KV-cache 友好

**12. 稳定前缀在前，易变内容在后**
- 来源：Anthropic "Reducing cost with Claude Platform"；PromptLayer/Prefix Caching
- 机制：把静态部分（工具定义、system 指令）放最前，逐轮增长的对话历史放最后。前缀字节一致才能命中缓存，缓存读仅 10% 价格、TTFT 降 40–80%。
- 落到 ChartFlow：✅ 我们装配顺序=内核+契约+图型段，**稳定前缀在前、用户内容在后**，且图型段是唯一的变量（拼最后）。这正好最大化共享前缀。关键纪律：**图型段绝不能插在契约段之前**，否则切碎公共前缀——已有测试 `diagramTemplateAppendedAfterContract` 锁死。

**13. 缓存断点（cache breakpoint）与预热**
- 来源：Anthropic（cache_control 标记断点，最多 4 个，5min/1h TTL）；PromptLayer
- 机制：在稳定前缀末尾打断点，前缀命中缓存；可对前缀发 `max_tokens:0` 的「预热请求」先写缓存，首个真请求命中暖缓存。
- 落到 ChartFlow：若接 Anthropic/支持前缀缓存的厂商，可在「内核+契约」末尾声明断点；并在服务启动（用户打字间隙）发一次空请求预热。对 siliconflow（我们当前主力）未必支持，但架构上预留断点位是零成本。

**14. 易变值不要进前缀**
- 来源：Anthropic 缓存指南
- 机制：时间戳、随机 ID 等动态值若出现在 system 块前部，每次都打破缓存。
- 落到 ChartFlow：✅ 内核/契约/图型段都不含时间戳或请求级随机值。保持。注意未来若给提示词加「当前日期」之类，必须放段尾或作为 user 消息注入，不进 system 前缀。

**15. 工具定义不要自排序**
- 来源：Anthropic 缓存指南
- 机制：Messages API 工具定义在固定位置渲染，工具定义一变就破缓存。
- 落到 ChartFlow：我们的工具集（read_contract/read_scene/submit_spec/update_elements）应**固定顺序、固定 schema**，不要按字母或哈希动态排序，才能稳定命中前缀缓存。

---

## D. 热加载与原子替换

**16. WatchService + 防抖 + 原子替换（失败保旧）**
- 来源：Spring AI Lab v0.3.0（WatchService 监听 ENTRY_CREATE/MODIFY/DELETE，200ms 防抖）；TechLearn WatchService；Apache APISIX Java Runner
- 机制：后台 daemon 线程 `take()` 阻塞等待；事件风暴用 200–500ms 防抖只重载一次；新配置解析成功后再**原子替换**旧对象（`AtomicReference`），解析失败保留旧版——请求线程永远看到旧或新，不会看到半载中间态。
- 落到 ChartFlow：✅ 我们的 `DiagramPromptRegistry` 已是这形态（不可变快照 + 原子引用 + 250ms 防抖 + 失败保旧 + 删文件回退内置）。完全对齐工业实现。可补：防抖阈值从 250ms 提到 300–500ms 更稳（编辑器保存常触发多次 MODIFY）。

**17. 处理 OVERFLOW 事件（WatchService 丢事件）**
- 来源：TechLearn WatchService
- 机制：事件队列溢出时 `OVERFLOW` 事件出现，此时必须**全量重扫目录**追上，不能只处理单事件。
- 落到 ChartFlow：当前 WatchService 循环若只处理逐个事件，遇到 OVERFLOW 会漏更。建议捕获 `OVERFLOW` 时触发一次全目录重编译。这是目前实现的一个真实缺口。

**18. 删文件=回退，不是崩溃**
- 来源：Spring AI Lab（外部目录同名文件删掉后自动回退 classpath 内置）
- 机制：外部覆盖不存在时，classpath 内置自动接管。
- 落到 ChartFlow：✅ 已在做（外部模板删除→回退内置快照）。补强：回退时要打 WARN 日志 + 记录被删文件名，便于排障。

**19. 级联依赖更新（改公共片段→自动重编译引用者）**
- 来源：JPrompt（Spring 提示词框架，`{{> partial}}` + 级联依赖 + WatchService 增量热更）
- 机制：「一份公共内核 + N 份图型模板」结构下，改内核必须自动刷新所有引用它的图型模板，否则出现脏状态（内核改了但图型模板还是旧拼接）。
- 落到 ChartFlow：当前内核与图型模板是**各自独立文件、装配时拼接**，不存在「图型模板内嵌内核副本」的脏状态风险。✅ 架构上天然免疫。但若未来引入 partial 继承（策略 21），就必须实现级联重编译。

**20. 健康管理：解析失败把服务标 DOWN**
- 来源：JPrompt（Actuator 健康检查，解析失败即标 DOWN）
- 机制：热加载不能静默吞错，否则坏模板上线无人知。
- 落到 ChartFlow：当前「失败保旧 + WARN」已够用（服务不崩、用上一版）。建议再加：暴露一个 `/actuator` 之类端点或日志计数「最近一次热更是否全部成功」，运维可观测。

---

## E. 模板组合与继承

**21. 片段继承 `{{> partial}}`（DRY，但带来级联）**
- 来源：Pydantic Logfire（composition refs `@{...}@`）；Mustache `{{> partial}}`；Prompt Weaver
- 机制：公共片段（角色、纪律）抽成 partial，多模板复用；动态 partial 名可由数据层决定。
- 落到 ChartFlow：当前**不做 partial 继承**（按你之前定的「粒度选 1：内核+每图型一份」）。理由充分——避免级联重编译复杂度。若未来图型模板长得高度同质，再引入，且必须配策略 19 的级联更新。

**22. 模板继承（base + block 覆写）优于散 partial**
- 来源：Wilfred gist（Handlebars 模板继承，Django 风格 `{{#block}}`/`{{#partial}}`）
- 机制：base 模板留默认块，衍生模板只覆写差异块，比到处 `{{> header}}{{> footer}}` 更不易碎。
- 落到 ChartFlow：可作为「25 种图型模板」的进阶写法——`diagrams/_base.md` 定义四段式骨架（定义/声明/节点/连线/常见错），各图型只填差异。但代价是引入解析层，**当前不迫切**，记此备查。

**23. 条件块与动态选择（数据层决定装哪个）**
- 来源：StudyRaid（Mustache 动态 partial `{{> (alertType)}}`）；Logfire `{{#if}}`
- 机制：渲染时按数据条件选 partial，逻辑留在数据层、模板保持逻辑无关。
- 落到 ChartFlow：可借鉴为「同一个 diagramType 按规模/语言选不同细则」——例如时序图区分「简单/复杂」两档细则。但属于过度工程，**二期再议**。

**24. 渲染两遍：先展开组合引用，再渲染变量**
- 来源：Pydantic Logfire（先 `@{...}@` 组合引用展开，再 Handlebars 变量渲染）
- 机制：组合引用先于变量渲染，缺引用在 preview 期就报校验错，不会上线才爆。
- 落到 ChartFlow：我们装配是「拼接多个 md 文件 + 契约段插变量」。若未来引入组合引用，必须保证「先展开组合、后填变量」的顺序，并在加载期（不是请求期）做引用完整性校验。

---

## F. 客户端缓存与可用性（运行期加载）

**25. 客户端侧缓存 + TTL + 后台重新校验**
- 来源：Langfuse Prompt Caching（SDK 默认 60s TTL；过期时先返回陈旧版、后台异步拉新版；拉取失败回退到过期版）
- 机制：首次拉取后本地缓存，之后零网络延迟；TTL 过期不阻塞请求；服务挂了也有旧版可跑。
- 落到 ChartFlow：我们当前是**进程内 classpath/文件直读，无远程**，所以这条主要对应「外部目录热更的本地快照缓存」——已在用 `AtomicReference` 快照。若未来把提示词挪到远程（Langfuse/DB），必须照搬：TTL + 后台刷新 + 拉取失败回退旧版。

**26. 启动时预取（pre-fetch），保证首请求不冷**
- 来源：Langfuse
- 机制：启动期主动拉取并填满缓存，首个真请求直接命中暖缓存。
- 落到 ChartFlow：我们启动已扫目录注册（等效预取）。✅ 对齐。若接远程，加显式 `prefetchAll()`。

**27. 明确版本标签 = 部署指针（production/latest）**
- 来源：Langfuse（默认取 `production` 标签；未 promote 用 `label=latest`；更新=新建 version + 挪标签）
- 机制：代码永远引用标签，改提示词只是挪标签，天然支持回滚/灰度/多环境。
- 落到 ChartFlow：当前「版本」靠 CHANGELOG + md 头治理。若要上「按图型灰度不同提示词版本」，可借标签机制——模板 frontmatter 加 `labels`，装配时按运行期配置选 `production`/`canary`。二期。

---

## 落地优先级（只看加载这一层）

| 档 | 条目 | 动作 | 成本 |
|---|---|---|---|
| **立刻做（补缺口）** | 17 OVERFLOW 全量重扫、9 路由结论日志、16 防抖 250→400ms、20 热更健康计数 | 小改 `DiagramPromptRegistry` + 单测 | 低 |
| **已对齐，保持** | 1/3/4/5/6/7/8/12/14/15/16/18/25/26 | 不改动，新增图型时跑断言锁死 | 0 |
| **二期（引入 partial/远程时再做）** | 2③档资源、19 级联、21/22/23/24 继承、27 标签灰度 | 需先有诉求再上，否则过度工程 | 中 |

**一句话**：加载这块你现在的架构已经站在工业界正确侧，真正要补的是 **WatchService 的 OVERFLOW 兜底 + 路由/热更可观测日志** 这两处小缺口，其余是「保持 + 二期备援」。
