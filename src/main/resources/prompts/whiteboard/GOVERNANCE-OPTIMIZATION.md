# 提示词治理优化策略调研（GitHub / 博客，30+ 条可借鉴）

> 调研时间：2026-10-17。目标：优化 ChartFlow 当前「五层提示词治理」方法。
> 当前成熟度（见 STRATEGY.md）：①资产化 ✅ ②契约化 ✅领先 ③预算治理 ⚠️只有闸门 ④回归评测 ❌最大缺口 ⑤运行期治理 ⚠️已做热加载未做标签。
> 结论主线：**抄理念不引工具**（Java 项目塞 Node 栈不划算；Langfuse 要额外基础设施）。每条都给了「落到本项目的具体动作」。

---

## 一、资产化（版本 / CHANGELOG / 守卫）

**1. 不可变版本 + 机器 diff 视图**
来源：Langfuse（`docs.langfuse.com`、Prompt Management at Scale）。每次保存生成新版本、绝不原地改，UI 可 diff 任意两版。
落到本项目：现在的 CHANGELOG.md 是人写文本，无法机器比对。可让 `DiagramPromptRegistry` 在加载时给每个模板算一个 `contentHash`，守卫测试断言「同内容同 hash、改内容必变 hash」，等于把 Langfuse 的 diff 能力用测试锁死。

**2. 受保护标签 + 审计日志**
来源：Langfuse「Protected labels + audit trail」。production 标签只能 admin 移动，每次移动记录操作人 + before/after。
落到本项目：热加载目前谁都能改外部文件即生效。可加一个 `prompts.allow-external-override=false` 开关，默认关；开时记录「谁、何时、哪个模板、从哪版到哪版」到应用日志。

**3. Git 化版本 + pre-commit 钩子语法校验**
来源：Omnithium《生产级 AI 智能体的提示词版本控制与回归测试》。
落到本项目：已有 git，但缺钩子。可在 `prompts/whiteboard/**` 上加一个轻量校验脚本（不是 `mvn`），提交前跑「frontmatter 字段合法 + 字节不超闸门」，拦住坏模板进仓库。

**4. 提示词目录 + 元数据检索（防 prompt sprawl）**
来源：Omnithium「Prompt Catalog」。记录 owner / criticality / compliance / last-test-pass 时间。
落到本项目：25 个图型模板已多，可在 `diagrams/` 每个文件 frontmatter 加 `owner` + `lastReviewed` 字段，注册表启动时打印未评审超过 N 天的模板清单。

---

## 二、契约化 / 上下文架构

**5. 五层系统提示词架构**
来源：Zylos《Context Engineering for AI Agents》、Claude Code（110+ 条件片段组装，基础 ~2900 token）。五层 = Identity / Behavioral / Format / Edge-case / Examples。
落到本项目：印证「分层」方向完全正确。建议把内核再显式分成这三段顺序：角色身份 → 硬纪律（禁止式）→ 输出格式（契约引用句），与我们「核心约束放最前」一致。

**6. 最小可行上下文（MVC / Just-in-time 组装）**
来源：Zylos、metavert-cms「Four Moves（Offloading/Retrieval/Isolation/Reduction）」。每步只注入此刻需要的，过量注入引发「context distraction」。
落到本项目：图型模板已经是 JIT（命中才发）。下一步可把「工具描述」也做成按需——当前 4 个工具全发，可参考 tool masking（策略 29）。

**7. 指令位置权重：前 500 词最高，2000 词后骤降**
来源：Zylos 2025 评估。
落到本项目：内核 md 已 3223B（约 1000+ 中文字），关键约束（不夹散文、契约格式、image/magicframe 口径）必须放在内核最前面，不要埋在中间段。

**8. 三种指令风格混用**
来源：Zylos。禁止式（hard safety，如「永不暴露 API key」）/ 条件式（situational）/ 解释式（rationale，如「因为渲染器会strip markdown 所以别用表格」）。
落到本项目：我们 md 多用「禁止式」，可在关键处加「解释式」提升合规（例如「不要输出到结果里，因为那是内部装配动作」已部分做了）。

**9. Lost-in-the-middle：关键内容放首尾**
来源：masterprompting《Context Engineering》。检索文档按相关性排位置 1 和 N，不要堆中间。
落到本项目：多图型模板注入在契约段之后、用户消息之前，是「偏后」位置，属安全区；但若未来一次发多个图型段，要把最关键的（diagramType 声明规则）放首。

**10. XML 标签隔离检索/契约内容**
来源：toolchew《context engineering in 2026》。用 `<doc></doc>` 让模型不把检索文本当指令。
落到本项目：契约段现在是纯文本块，可包一层 `<contract>` 标签明确「这是机器契约不是你的发挥空间」，降低模型误读。

**11. 子代理隔离（Isolation）**
来源：metavert-cms。研究子任务的中间 scratch 不污染主代理推理。
落到本项目：当前是单 AgentLoop。若未来做「先规划图结构、再生成坐标」两阶段，可用隔离避免规划草稿污染生成上下文。

---

## 三、预算治理（token 预算 / 裁剪 / 压缩）

**12. Token 预算分层 + 动态截断**
来源：Charter Global《Context Engineering 2025》、masterprompting。
落到本项目：我们只有「硬闸门」（超 12000 直接拒）。可加「软预算」——当内核+模板接近 12000 时，按 frontmatter 的 `priority` 自动丢弃最低优先级模板段，而非整段拒收（这就是我们五层模型里缺的「自动裁剪」）。

**13. 损失容忍路由压缩（Loss-tolerance routing）**
来源：leanctx（GitHub `jia-gao/leanctx`，MIT，本地跑）。按内容类型分段：zero-tolerance（代码/JSON/工具名 verbatim 0% 改）/ high-tolerance（文档/日志 ~50% 删）/ conditional（散文 self-LLM 41-49% 删）。实测省 10-40% token、仅 -1.8pp 精度。
落到本项目：内核 md 本身不宜压（会改语义），但**契约段里的冗余描述**可走 high-tolerance 压缩。注意：这是双刃剑，必须先有评测（策略 17）兜底再上。

**14. 上下文压缩工具链**
来源：LLMLingua（microsoft）、mem0、Letta（虚拟内存式 paging）、tiktoken + ttok（CLI 截断到 token 上限）。
落到本项目：可加一个 `ttok` 式预算强制器——在 `DiagramPromptRegistry` 装配完块后，若超硬闸门，先尝试砍最低优先级模板段；仍超则降级到纯内核。

**15. 结构化输出约束省重试 token**
来源：Guidance（regex 约束每个生成步，消除礼貌前缀/坏 JSON，省最多 50% 重试 token）。
落到本项目：契约已是 JSON schema，等价于 Guidance 的硬约束。保持即可。

**16. KV-cache 友好分层（稳定前缀最大化命中）**
来源：secborn、aicodingpatterns、Anthropic 官方 blog。静态指令 → 检索 → 历史 → 用户消息，稳定前缀越长缓存越省。**KV 命中率是生产代理最重要指标**（Manus 实证：缓存读 $0.30/MTok vs 未缓存 $3/MTok，10× 差）。
落到本项目：我们的「内核+契约在最后、图型段在末尾」顺序正好最大化前缀命中，与工业界一致。可加一条：**外部热加载改写模板时，只重编译被改的模板，不动内核/契约前缀**，保住缓存。

**17. 多缓存断点按更新频率隔离**
来源：Anthropic prompt-caching 文档（最多 4 个 breakpoint）。系统指令（rarely 变）+ RAG（每天变）分断点独立缓存。
落到本项目：可把「内核 + 契约」设为断点 1（几乎不变），「图型模板段」设为断点 2（随图型变），「用户消息」断点 3。这样换图型只失效断点 2，内核缓存不破。

**18. 缓存预热**
来源：Anthropic。首请求 `max_tokens:0` + 显式 cache breakpoint 预写缓存，用户打字时预热，首真实请求命中热缓存。
落到本项目：后端可在会话建立时（用户还在输入）先发一个空请求预热内核前缀，降首图延迟。

---

## 四、回归评测（eval / 门禁）—— 最大缺口

**19. promptfoo 式测试驱动（YAML 用例 + 30+ 断言）**
来源：promptfoo（GitHub `promptfoo/promptfoo`）、dev.to 实战。断言类型：equals/contains/regex/icontains/is-json/javascript/python/webhook/similar/llm-rubric/rouge… 非零退出即 CI 失败。
落到本项目：我们已有 `FidelityScorer` + gen_seq.py/render_at.py。可自建一个 eval runner：把 20-30 条「需求→出图」用例存成 yaml，每次改 prompt 后批量跑，断言「通过校验 + 声明了正确 diagramType + 关键节点存在」。

**20. 黄金数据集 + 基线对比 + promotion gate**
来源：HackerNoon《Building a Production Pipeline》。顺序：version → replay dataset → evaluate → compare baseline → gate → canary → monitor。CI 在每次动 prompt 文件的 PR 自动触发。
落到本项目：把「改 prompt = 跑回归」固化成 `mvn` 之外的 eval 脚本，改动 prompts/ 目录即触发。

**21. 动态 few-shot 选择（embedding kNN）**
来源：Medprompt（promptbase GitHub）、`ElmiraGhorbani/dynamic_prompting`。把样例库 embedding，按与当前 query 相似度取 top-k，优于静态 few-shot。
落到本项目：图型模板目前是「规则文本」，可加「该图型 1-2 个正确样例 JSON」作为 few-shot，且按用户需求相似度选最贴近的样例注入（参考图同理）。

**22. 确定性断言每 PR、model-graded 留 nightly**
来源：promptfoo 社区实践。确定断言（is-json/icontains/regex/javascript）免费又稳，每 PR 跑；llm-rubric（用裁判模型打分）贵且有方差，留 nightly/发布分支。
落到本项目：评测分层——PR 跑「通过校验 + diagramType 正确 + 节点数达标」硬断言；观感分（像不像参考图）放 nightly，避免每次改 prompt 烧裁判 token。

**23. 语义相似度断言**
来源：promptfoo `similar`（embedding cosine 阈值）。
落到本项目：出图质量可加「节点 label 集合与需求关键词的余弦相似度 ≥ 阈值」，防「画了一堆无关框」。

**24. 从真实失败案例长测试集**
来源：dev.to promptfoo 实战。昨天真实工单 = 明天回归集，CSV 直接喂 50 条。
落到本项目：把 `chartflow_render` 里渲染失败的 case 沉淀成 eval 用例库，滚雪球增长。

**25. 多模型/多候选横向对比**
来源：promptfoo「wording bake-off」、qaskills 指南。同一输入跑多个 prompt 变体，挑得分最高的而非「听起来最好的」。
落到本项目：候选表 4 个模型，可在 nightly 用同一组用例横向比「哪个候选在该图型上通过率最高」，反哺候选排序。

---

## 五、运行期治理（热更新 / 回滚 / 标签 / 可观测）

**26. 标签即部署（labels = 指针）**
来源：Langfuse「production / staging / canary」标签，移动标签 = 部署/回滚，零代码、60s TTL 客户端缓存。
落到本项目：我们热加载是「文件即版本」，可升级为「每个模板带 `labels: [stable, experimental]`」，运行时按标签选；回滚 = 把 stable 标签指回上一 hash。比删文件回滚更优雅。

**27. 金丝雀 1% + 自动回滚**
来源：Omnithium。1% 流量先放新版，监控成功率/转接率，超基线降级自动回滚（转接率飙升 200% 即回滚）。
落到本项目：白板是单人交互，全量即 100%。但可做「同一会话内 A/B」：本轮用新模板、下轮回退，对比通过率（配合策略 25）。

**28. A/B 评估框架**
来源：Omnithium。对照组（旧）/ 实验组（新）分流 + 统计显著性。
落到本项目：新增图型模板上线前，先拿 10 条该图型需求，新旧模板各跑一遍比对通过率/时长。

**29. 生产可观测性 + 按 prompt 版本归因**
来源：Langfuse trace 链接 prompt 版本。每请求归因到具体版本，per-version 看延迟/成本/质量，阈值告警 Slack/webhook。
落到本项目：后端可在生成日志里打 `diagramType + templateHash + model`， Grafana/日志看板按模板版本聚合「通过率/平均轮次/平均耗时」，回归一眼可见。

**30. GitHub repo_dispatch 触发 CI**
来源：Langfuse GitHub Integration。提示词变更触发 Actions 或 webhook 同步回 repo 做版本控制。
落到本项目：在 `.github/workflows/` 加一个「改动 prompts/ 即跑 eval 脚本」的 workflow（用户红线：git 操作需本人确认，但加 workflow 文件是给他审批，不自动 push）。

**31. 工具描述工程 / tool masking**
来源：Zylos、semantic_router。工具 schema 名/描述/参数塑造模型行为，劣名致歧义；隐藏无关工具保 KV 不破。
落到本项目：`read_contract/read_scene/submit_spec/update_elements` 四个工具描述可精修；并参考 tool masking——复杂图型不需要 `update_elements` 时不在该轮注入，缩短前缀。

---

## 六、跨层 / 长链路技巧

**32. 任务复述（task recitation）**
来源：metavert-cms（Manus 实践）。长链路（50+ 工具调用）维护并持续重写 plan/todo，把全局目标推回近期注意力。
落到本项目：AgentLoop 可在每轮 system 末尾附一句「当前目标：画出 X 图型」，防长链路漂移（参考「编辑模式原样保留吃掉模糊追问」那条坑）。

**33. 错误保留（error preservation）**
来源：metavert-cms。失败动作 + 错误信息留上下文，模型更新先验避免重复错。
落到本项目：`MemoryStore` m5 已在做（issue 历史注入 user block），方向对，可扩展到「上一轮 tool 报错原文也保留」。

**34. 结构化变体（structured variation）**
来源：metavert-cms。模板/措辞/排序的小幅变化打破模型「照搬历史 pattern」的失败模式。
落到本项目：多图型模板写法已有差异，但内核若长期不变可能导致模型过度拟合某句式——可周期性微调内核 phrasing 观察是否影响通过率（需评测兜底）。

---

## 优先级建议（落到本项目，三档）

| 档 | 策略 | 理由 |
|---|---|---|
| **立刻做（补齐最大缺口④）** | 19、20、22、24 | 回归评测是五层里最空的，且我们有 gen/render 工装可直接组 runner |
| **近期做（把已有能力做厚）** | 1、12、14、17、26、29 | 在现有资产化/预算/热加载上做增量，不引新依赖 |
| **二期（按需）** | 13、18、21、27、28、31、33 | 双刃剑或需额外基础设施，先有评测再上 |

**一句话**：你现在的结构（分层 + 契约派生 + 图型路由 + 热加载）已经站在工业界正确一侧；下一步最值钱的动作是把「回归评测」这层从 ❌ 补到 ✅，其余都是在这个骨架上贴肉。
