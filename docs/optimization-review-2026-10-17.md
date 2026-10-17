# ChartFlow 优化评审报告

- **评审日期**：2026-10-17
- **评审方式**：只读（未修改任何文件、未执行任何 push/commit）
- **范围**：后端 Java（`src/main/java` 14437 行 / `src/test/java` 8658 行，实测 `@Test` 388 个）+ 前端 React/TS（`frontend/src` 共 10 个文件，最大 `sceneAdapter.ts` 1320 行）+ 构建/CI/Docker 配置
- **说明**：行数均为实测总行数；所有 `文件:行号` 均可按相对路径定位

---

## 结论摘要（Top 10，按收益排序）

| # | 问题 | 位置 | 一句话修法 |
|---|------|------|-----------|
| 1 | 48 个提交未推送 + 22 项未提交，本机是唯一副本 | `git` | `git push origin reframing`，并提交未跟踪的 `prompt/` 包 |
| 2 | 每请求一条裸线程，无取消、无 SSE 回调，断线后 LLM 继续烧 token 到 1000 秒预算 | `DiagramController.java:92` | 线程池 + `onTimeout/onCompletion/onError` 置取消标志 |
| 3 | 一次提交对同一份 JSON 解析 15~20 次、整场景序列化 12+ 次 | `SceneGate.java:110` | 入口解析一次成 `SceneDoc(JsonNode)`，各阶段 `SceneDoc→SceneDoc` |
| 4 | 全库 39 个 ObjectMapper（26 个 `new` + 13 个 `static final`），0 个 `@Bean` | 全库 | 统一 `@Bean ObjectMapper` 注入 |
| 5 | 两处真实竞态（memory store / session store） | `InMemoryMemoryStore.java:45`、`InMemorySessionStore.java:52` | 统一锁监视器；`compute` 式原子替换 |
| 6 | docker-compose 把 Redis `6379:6379` 发布到宿主，仅内部服务需要 | `docker-compose.yml:27` | 删 ports 或绑 127.0.0.1 |
| 7 | 会话永不释放（无 TTL/淘汰/删除） | `SessionStore.java` 全接口 | `lastAccessAt` + 定期清理，或 Caffeine |
| 8 | 巨型类：ScenePlanner 1751 行 / PromptService 992 行等 7 个 | 见 §三 | 按已标注的职责边界拆分 |
| 9 | 前端零单元测试（1320 行 sceneAdapter.ts 无任何覆盖） | `frontend/package.json` | 引入 vitest，为纯函数补单测 |
| 10 | 文档测试数字全错（README 184 / AGENTS 276 / 实际 388） | `README.md:19,203,234` | 改成真实数字 |

---

## 一、P0：正确性与资源

### 1.1 本机是唯一副本（先做，零风险）
`reframing` 相对 `origin/reframing` **ahead 48**（222 files changed, +28455 / -3751）；`main` 停在 `70813bb`。工作树还有 22 项未提交，其中整个 `src/main/java/io/github/nihaoljx/flowchart/prompt/` 包**未被 git 跟踪**，却被 4 个主源码文件引用：

- `agent/AgentLoopService.java`
- `agent/tools/AgentTools.java`
- `agent/tools/SubmitSpecTool.java`
- `service/PromptService.java`

已核实：**HEAD 自身自洽可编译**（HEAD 既不引用也不包含该包），所以不是"仓库坏了"，而是"一台机器承担了全部历史"。
**安全核验已做**：真实 API key 不在任何 tracked 文件、不在 48 个未推送提交、也从未在任何提交历史中出现过（pickaxe `-S` 全历史 0 命中）；key 只在 `.gitignore:40`（application.yml）与 `.gitignore:43`（.env）屏蔽的两个文件里。推送不会泄漏。

### 1.2 请求不可取消，断线后继续烧 token
`controller/DiagramController.java:92-96`：
```java
new Thread(() -> generationService.generate(request.sessionId(), request.message(), emitter)).start();
```
- 无池、无名、非 daemon；全库 grep 确认 `Executors.` / `ExecutorService` / `@Async` / `@Scheduled` / `ThreadPoolTaskExecutor` **0 命中**
- `onTimeout` / `onCompletion` / `onError` **0 命中**
- 客户端断开后 LLM 继续跑到 `service/GenerationBudget.java:44` 的 `TOTAL_BUDGET_MS = 1_000_000L`
- 另一处 `new Thread(` 在 `llm/OpenAiToolClient.java:172`（看门狗）

**修法**：注入 `ThreadPoolTaskExecutor`；注册三个回调置取消标志并 `body.close()`；看门狗与预算检查读该标志早退。

### 1.3 String→String 管线：重复解析/序列化
`graph/validate/SceneGate.java:110-147` 管线每环各自 `readTree` + `writeValueAsString`：

| 环节 | 位置 |
|------|------|
| 闸门内 | `SceneGate.java:169`（ArrowGeometry.normalize）、`:121`(parseGate)、`:128→:207`(emptySceneIssue)、`:137→:287,294`(detectRewrite) |
| 变换链 | `graph/transform/SceneBinder.java:79,102`、`SceneDeduper.java:78,120`、`SceneSanitizer.java:163,280`、`ScenePlanner.java:137,150` |
| 三套校验各解析一遍 | `graph/validate/GraphValidator.java:99`、`SemanticValidator.java:276`、`GeometryAudit.java:115` |
| 布局链 | `graph/layout/GenericLayoutFamily.java:71,152`、`ElkLayoutEngine.java:284`（解析）+ `:142,158,190,232,243,264`（**一个方法族里 6 处 `writeValueAsString(p.root)`**）、`agent/tools/SubmitSpecTool.java:539,569` |

**修法**：`SceneGate.process` 入口解析一次为 `SceneDoc(JsonNode root)`，各阶段签名 `String→String` 改 `SceneDoc→SceneDoc`，只在出口序列化一次。

### 1.4 ObjectMapper 泛滥
实测：`new ObjectMapper()` **26 次** + `static final ObjectMapper` **13 处** = 39 个实例；src/main 中 `@Bean` **0 命中**。每个实例各带一套序列化缓存，配置各自为政：
- `GraphValidator.java:87-88` 与 `GeometryAudit.java:105` 各持独立 mapper 解析同一份 JSON（`GeometryAudit.java:110` 注释自认"与 GraphValidator 看到的是同一份"）
- `graph/transform/SceneSanitizer.java:445,495` 为造一个 `ArrayNode` 各 `new ObjectMapper()` 一次

**修法**：统一 `@Bean ObjectMapper` 注入；`ArrayNode` 用 `JsonNodeFactory.instance.arrayNode()`。

### 1.5 两处真实竞态
1. `session/InMemoryMemoryStore.java:45-65` vs `:74-87`：`recordIssues` 在 `store.compute(...)` 里改 `deque`（compute 只持 ConcurrentHashMap 的 bin 锁，不是 deque 监视器），`recent` 却 `synchronized (deque)` 迭代 → 可抛 `ConcurrentModificationException` 或读到撕裂状态。
2. `session/InMemorySessionStore.java:52-102` + `controller/DiagramController.java:117-132`：外层 ConcurrentHashMap、内层普通 Jackson Map，全是"get→判断→就地 mutate"；`syncCanvas` 顺序做 `updatePositions/removeElements/setUserElements/releaseBindings` **四次独立读改写**。同 session 并发 PUT 会丢更新。

**修法**：两侧统一 `deque` 监视器 + `recent` 返回不可变快照；`SessionStore` 加 `update(String id, UnaryOperator<Map<String,Object>> fn)`（内部 `models.compute`），控制器四步合成一次调用。

---

## 二、P1：性能

| 位置 | 问题 | 修法 |
|------|------|------|
| `llm/OpenAiToolClient.java:79-81` | `private final HttpClient http = HttpClient.newBuilder()...` 每实例一个，而 `agent/AgentLoopService.java:108-121` 按候选逐个 new → N 份连接池 + N 组 selector 线程 | HttpClient 提为 `@Bean` |
| `service/PromptService.java:312-313`（及 `:386-387`） | 每请求重解析 14.3KB 的 `schemas/scene.schema.json` 并重拼逐字节相同的契约文本（文件读**已**被 `resourceCache :168-169` 缓存，解析没有） | 缓存 `buildContractBlock()` 结果为 volatile 字段，随 registry reload 失效 |
| `graph/validate/GeometryAudit.java:189-217` | `:176 countCrossings` 与 `:191-210` 逐对点名是同一 O(n²) 做两遍（`NAMED_CAP` 只截断输出未截断计算） | 一趟 O(n²) 收集交叉对，计数取 `list.size()` |
| `graph/transform/ScenePlanner.java:988-1049 / :1189` | 路由候选评分里对每条候选通道重复全量相交计数（`routeOne :834` → `recordCandidate :945`）→ 候选数 × O(n²) | 预处理路径×障碍相交位集，评分时只做位运算 |
| `graph/validate/SemanticValidator.java:270-289` | `readTree` 后 `convertValue` 双重物化 + 每次新建 `new TypeReference<List<Map<String,Object>>>(){}`（`:286-287`）；调用者 `:47 :87 :168` | `private static final TypeReference<...> ELEMENTS`，门内单次解析共享给三个校验器 |
| `prompt/DiagramPromptRegistry.java:117` | 内层循环对每个关键词现做 `keyword.toLowerCase(Locale.ROOT)`（26 模板 × 若干关键词 → 每请求上百次分配） | reload 时预小写存进 `DiagramTemplate` |
| `graph/layout/ElkLayoutEngine.java:600` | 每次布局 `new RecursiveGraphLayoutEngine()` | 复用实例或 ThreadLocal（**待验证**：ELK 线程安全性未确认） |
| `llm/OpenAiToolClient.java:153-156` | 非流式分支硬编码 60s 读超时，无视入参 `timeoutSeconds`（流式 `:142` 已用上） | 非流式也用入参超时 |
| `agent/tools/SubmitSpecTool.java:294` | 对自家布局引擎刚产出的 JSON 跑**完整**闸门（结构类校验几乎不可能失败） | 拆 `processExternal`（全量，给 submit_scene）/ `auditSelf`（只跑 GeometryAudit） |
| `session/SessionStore.java` 全接口（100 行） | 无 TTL/淘汰/删除方法，会话**永不释放** | `lastAccessAt` + 定期清理，或 Caffeine `expireAfterAccess` |

**交叉判定口径分裂（值得单独修）**：
- `GeometryAudit.java:483-527 countCrossings` 用叉积做**规范相交**（`:513-522` 注明"共线/端点相接不算交叉"）
- `ScenePlanner.java:1008-1049 segCross` 是**轴对齐专用**（`:1010-1011` 用 `Math.abs(p1[0]-p2[0]) < 0.5` 认竖直、共线重叠也算、魔数 3/4/10px；`:1046` 注明"端点 10px 内的接触不算"）

→ **路由器自认无交叉，体检器照样报交叉**，用户会看到无意义的重排提示。修法：抽出唯一几何原语（含统一容差常量），交叉判定只留一套；GraphValidator 退回纯 schema 级。

**明确澄清为非问题**（不要误改）：
- `GraphValidator.java:135-161` 两遍遍历是必要设计（先收集全集 id 才能判悬空引用），非 O(n²)
- `PromptService` 内 12 处 `StringBuilder` 与 `GenerationService.java:357` 已避免循环内 `+=`
- `graph/validate/RequestIntent.java:115-122` 的 200 余次 `contains` 无正则编译，量级可忽略

---

## 三、P2：结构与重复逻辑

### 3.1 巨型类实测（总行数 / 字节）

| 文件 | 总行 | 字节 | 说明 |
|------|------|------|------|
| `graph/transform/ScenePlanner.java` | **1751** | 82125 | public 面仅 4 个（`:122 :134 :194 :243`），天然四段：模块分区与树 `:284-453`、模块排序 `:462-773`、正交路由与标签 `:551-1398`、块内排版与写回 `:1422-1788`（嵌套 `Partition :1848` / `Block :1921`） |
| `service/PromptService.java` | **992** | 47878 | 31 方法 / 5 职责，可拆 `:311-538` ContractRenderer（约 230 行）+ `:636-878` SceneDigest |
| `graph/layout/SceneLayout.java` | 958 | 37293 | 默认永不执行（见 3.3） |
| `graph/layout/ElkLayoutEngine.java` | 879 | 39636 | `:134-264` 超长方法族，6 处重复序列化可收敛成单一出口 |
| `graph/validate/GraphValidator.java` | 750 | 33436 | |
| `graph/validate/GeometryAudit.java` | 725 | 32347 | `:113-229` 超长方法 |
| `agent/tools/SubmitSpecTool.java` | 615 | 29071 | `:229-345 execute` 单方法 **117 行**，混合参数校验/家族路由/闸门调用/标题注入/`geometry.` issue 过滤(`:309-332`)/4 个返回分支 → 拆 `collectSpec` / `arrange` / `postProcess` / `outcome` |

### 3.2 装配上的开闭违反（建议优先修）
`agent/AgentLoopService.java` 构造器里**手工 new**：
```java
new LayoutFamilyRegistry(List.of(
        new GenericLayoutFamily(new GenericLayoutFamily.ScenePlannerHolder(planner, layoutEngine)),
        new SequenceLayout()));
```
注释自己写了"加新图型 = 加一个家族类 + **这里注册一行**"。
**修法**：把 `LayoutFamily` 变成 `@Component`、构造器注入 `List<LayoutFamily>`（Spring 天然收集），加图型就不用改这个类。

### 3.3 两套布局引擎并存
- `SceneLayout.java:68-71` = `@ConditionalOnProperty(havingValue = "legacy")`
- `ElkLayoutEngine.java:63-66` = `havingValue = "elk", matchIfMissing = true`
- `src/main/resources/application.yml:4-9` = `chartflow.layout.engine: elk`

→ **默认配置下 SceneLayout（958 行）永不执行**。运行时唯一注入点是 `AgentLoopService.java:79`；src/main 无任何 `new` 两个引擎（唯一 new 在测试 `LayoutBench.java:108-113`，用 `-Dengine=elk` 做 A/B 计时）。
两个引擎的常量是**人工平行维护**的（`SceneLayout.java:88 GRID=20.0 / :91 NODE_HEIGHT=60.0 / :109-110 LATIN_CHAR_WIDTH=10.0, CJK_CHAR_WIDTH=20.0 / :132 MAX_SLOT_TRIES=40 / :135-136 SHAPE_TYPES` vs `ElkLayoutEngine.java:72-73 / :76`），而 `graph/layout/LayoutSpec.java` 已是共享常量池（`ElkLayoutEngine.java:328-337,427-446,739-755` 全用它），`ElkLayoutEngine.java:887` 注释自认"自研引擎 SceneLayout 一直写着尺寸，这里是补齐它的行为"。
**修法**：要么删 SceneLayout（及其对偶测试），要么加引擎等价性集成测试锁定行为，否则两套实现会持续漂移。

### 3.4 未接线 / 死代码（grep 全库确证）
| 位置 | 问题 |
|------|------|
| `feedback/ReferenceImageAligner.java:27` | src/main 无 `new` 它的地方，未接线 |
| `feedback/SketchToSpecExtractor.java:19` | **无任何实现的接口** |
| `feedback/DiagramFeedbackCollector.java:34` | 无 `new` 它的地方 |
| `graph/validate/SemanticValidator.java:167 shapeCoverage` | 全库无调用者（`:158-163` 自认"`FidelityScorer` 不再引用它打分"） |
| `session/MemoryStore.java:47 clear(String)` | 全库无调用者 |

因果链：`FidelityScorer` 只被未接线的 `ReferenceImageAligner.java:29` 引用 → **整条保真打分链在运行时不活跃**。会让维护者误以为存在质量门。修法：删除或接线。

#### ✅ 决断（2026-10-17 二审，逐项复核后）：**全部保留，不删**

上表是"无生产调用者"的事实陈述，但**不等于可以删**。逐项复核结论：

| 项 | 二审发现 | 决断 |
|---|---|---|
| `feedback/ReferenceImageAligner` | 有**活跃测试** `ReferenceImageAlignerTest`（:24 `new ReferenceImageAligner()`；`FidelityScorerTest:163` 也在用） | **保留**：它是 D1/D5 反馈闭环的已建成零件，删掉等于把已交付能力退回 |
| `feedback/SketchToSpecExtractor` | 是 `@FunctionalInterface`，被 `ReferenceImageAlignerTest:72,:84` 用 lambda 实现 | **保留**：接口是接线点，不是死代码 |
| `feedback/DiagramFeedbackCollector` | 有活跃测试 `DiagramFeedbackCollectorTest`（:85/:95/:127/:137，含 `readAll`/`bestOf` 断言） | **保留**：同属反馈闭环，测试在守 |
| `SemanticValidator.shapeCoverage` | javadoc `:158-163` **明确写着**这是 ref_cat 事故后**刻意保留的诊断信息**（"本方法留在这里只作诊断信息，FidelityScorer 不再引用它打分"），并解释了为何绝不能当保真分用 | **保留**：是有据可查的**设计决定**，不是遗留垃圾；删掉会丢失那条事故教训的落点 |
| `MemoryStore.clear(String)` | 被 `InMemoryMemoryStoreTest:90` 直接调用（测试隔离用） | **保留**：测试 API 也是 API |

**为什么不按原建议删**：五项里**四项有活跃测试**、一项带明确事故注释。删除的收益是"少几百行"，代价是**不可逆地丢掉已建成的反馈闭环零件与事故教训**，并且会让现有测试编译失败——不符合本目标"小步可回退、每步有测试证据"的纪律。若要真正减少误导，成本更低的替代做法是**给 `feedback` 包加包级 `package-info.java` 说明"当前未接入运行时链路，属 roadmap 基础设施"**（本次未做，留作可选后续）。

**其他澄清**：`graph/transform/OrthoAStarRouter.java` 是 live（`ScenePlanner.java:614-625` 调用）；`ArrowGeometry.normalize` 由 `SceneGate.java:169` 调用，live。

#### ✅ 决断（§3.3 两套布局引擎）：**保留 SceneLayout，且有测试在守**

`SceneLayout`（958 行）默认配置下确实永不执行（`havingValue="legacy"` vs `elk`/`matchIfMissing`），但**不是无主死代码**：
- 有专属测试 `SceneLayoutTest`（505 行，`:65 new SceneLayout()`），把契约钉死：零重叠、网格对齐（GRID=20.0）、分层、同层兄弟同 x、10 层链长宽比受控、**同输入同输出（确定性）**、frame/text 不被移动、坏 JSON 原样返回、编辑模式冻结既有坐标、未绑定箭头重新吸附；
- `LayoutBench.java:113` 用它做 `-Dengine=legacy|elk` 的 A/B 计时基准；
- 它是 ELK 之外的**零依赖退路**（ELK 出问题时的替换实现），删了就没有对照物。

⇒ 已经满足原修法的第二条（"加引擎等价性测试锁定行为"）的实质要求：**两个引擎各自都有契约测试**（`SceneLayoutTest` + `ElkLayoutEngineTest`）。故**保留两者**，不做删除；剩余风险仅是"常量人工平行维护"（`SceneLayout:88,91,109-110,132` vs `ElkLayoutEngine:72-73,76`，而 `LayoutSpec` 已是共享池），属可后续收敛的小项。

---

## 四、配置与安全（改动最小、收益直接）

1. **更正（2026-10-17 复核）**：`.env.example` 与 `.env` **均含** `LLM_API_KEY`（模板第 2 行、本地第 2 行）——此前评审误报"缺 key"，**实际无此问题**，docker-compose 的 `${LLM_API_KEY}` 有来源。真正的配置问题是下一条：明文 key 在 `application.yml` 里重复 5 次、无 `${LLM_API_KEY:}` 占位。（原文的".env 缺 LLM_API_KEY"为提取键名的正则漏判，已作废。）
2. **`application.yml`（18335 字节，gitignored 私有）每个候选硬编码明文 api-key**，同一把 key 重复多次（siliconflow 4 候选共用一把、ailanglang/gpt 各一把）。**注意：这是多候选 failover 表、各候选本就是不同 key，所以"改成单个 `${LLM_API_KEY:}`"不适用**，也不应改——该文件被 gitignore、按 AGENTS 约定"含 key 永远本地不入库"，无公开泄漏风险。真正可做的只有：同 key 候选用 **YAML 锚点 `&`/`*` 复用 `base-url`/`api-key`** 去掉重复（纯可读性，非安全必需）。
3. **`docker-compose.yml:27` 把 Redis `6379:6379` 发布到宿主** —— 只有 `flowai` 需要它。→ 删掉 `ports` 或绑 `127.0.0.1`。
4. **`Dockerfile` 以 root 运行、无 `HEALTHCHECK`**（`/api/health` 已存在）。→ 加 `USER` 与 `HEALTHCHECK`；`mvn dependency:resolve` 换 `dependency:go-offline` 更稳。
5. **依赖陈旧**（`pom.xml`）：Spring Boot **3.2.0**（2023-11，3.2.x 已有多个 CVE）、`maven-compiler-plugin` **3.8.1**（`pom.xml:80-81`，2018 年）、Java 17（`pom.xml:11`，可升 21/25）、springdoc 2.3.0（`:29-30`）、ELK 0.12.0（`:39,:44`）、maven-checkstyle-plugin 3.3.1（`:125-126`）。注意 pom 有意不继承 `spring-boot-starter-parent`（`:88` 注释），所以 BOM 升级要手工同步。

---

## 五、测试与 CI

- **文档测试数字全错**：实测 `@Test` = **388**；README 说 184（`README.md:19`、`:203`、`:234` 三处），workbuddyFlow/AGENTS.md 说 276。三个数字没有一个对。
- **前端零单元测试**：`frontend/package.json` 只有 `dev/build/preview/lint`（无 `test`），无 vitest/jest 配置，`frontend/src` 下**没有任何 test/spec 文件**。而 `frontend/src/lib/sceneAdapter.ts` 有 **1320 行**（占前端源码 76%），全是 `convertScene` / `planRemoval` / `healCorruptedArrows` / `collectUnboundArrows` / `toSkeleton` / `toUserElementBriefs` / `isModelElement` / `isFullyBoundArrow` / `isUserElement` 这类**纯函数**——最适合单测，且注释里记着一串回归（Bug A 双图、Bug B 箭头消失、r8 行尾被裁）。README 宣称的"17 个适配层冒烟用例"实际载体是 Python 探针，不是 JS 单测。
- **19 个 Python E2E 探针不在 CI**：`.github/workflows/ci.yml` 只跑 `mvn clean test` + `checkstyle:check` + `npm ci/build/lint`；`workbuddyFlow/tools/e2e/*.py`（含 `e2e_prompt_battery.py`、`e2e_real_llm_layout.py`、`harness.py`）只能手工跑。至少把不需要真实 LLM 的那几个加进 CI。
- **`ci.yml:41-45` 后端跑两次 mvn**：`mvn -B clean test` 然后 `mvn -B checkstyle:check`，可合并成一条 `mvn -B clean test checkstyle:check`，省一次 JVM 启动与依赖解析。
- 好的一面：`permissions: contents: read` 最小权限、`concurrency` 取消旧运行、maven/npm 缓存都配了。

---

## 六、文档与仓库卫生

- **README 与装配不符**：README 写"graph/SceneLayout / ElkLayoutEngine 保留为可选能力，当前不在运行时流水线"。对 `SceneLayout` **成立**（`havingValue="legacy"`），但 **ElkLayoutEngine 是默认主引擎**（`matchIfMissing=true` + `engine: elk`），且 `AgentLoopService.java:79` 确实注入 `LayoutEngine`。这半句不准。
- **仓库根垃圾**：`.acl-repair/`（评审工具自身产物，未跟踪）、`frontend/vite.config.ts.timestamp-*.mjs` ×3、Spring Initializr 残留的 `HELP.md`、`workbuddyFlow/plans/**/TEMP.md`。
- **装配不一致**：`InMemorySessionStore.java:21-23` 是 `@Component`（注入使用），而 `InMemoryMemoryStore` **无注解**、由 `service/GenerationService.java:71` 直接 `new` → 两个 Store 装配方式不一致，测试替换困难。
- 好的一面：`.gitignore` 覆盖完整（`target/`、`node_modules`、`.playwright/`、`application.yml`、`.env`、`buildinfo`、`vite timestamp`），frontend 树 git 干净，无 TODO/FIXME 残留，`.dockerignore` 存在。

---

## 七、前端

代码质量整体很高：注释把"为什么"写透（Excalidraw 0.18 的 `fitToContent` vs `fitToViewport` 互斥联合类型、字体子集懒加载导致行尾被裁 4.8%、`CaptureUpdateAction.NEVER` 的版本迁移、`Bug A/B` 的判据）；TS 是 `strict: true` + `noUnusedLocals` + `noUnusedParameters`；`any` 与 `ts-ignore` 计数**全为 0**；ESLint `--max-warnings 0` + `--report-unused-disable-directives`。剩下三条真问题：

### 7.1 状态回写静默失败 + PUT 无序号（最该修）
`frontend/src/App.tsx:199-208`：
```ts
fetch(`/api/model/canvas?sessionId=${sessionId}`, { method: 'PUT', ... }).catch(console.error);
```
只 `console.error`。而这个接口正是 v2-34 为解决"删掉的线下一轮复活、手绘的线 AI 看不见"才加的——它一失败，后端模型就与用户所见分叉，用户毫无感知。且并发 PUT 没有版本号/序号，乱序到达即旧覆盖新。
**修法**：加可见的错误提示 + 载荷序号（或 `AbortController` 串行化）。

### 7.2 无 ErrorBoundary
`frontend/src/App.tsx:263-269`：`Canvas` 是 `lazy` 的，只有 `Suspense` 兜加载期；渲染/effect 抛错会白屏整站。`onRenderIssue` 只覆盖转换失败（`frontend/src/components/Canvas.tsx:137-143`），不覆盖 throw。
**修法**：加 ErrorBoundary。

### 7.3 SSE 无客户端超时
`frontend/src/App.tsx:129-161`：`while (!streamDone)` 只处理"收到终态"和"流关闭"两种情况。若服务端既不返回终态也不关闭，`reader.read()` 会永久挂住，`isLoading` 一直 `true`、输入框永久禁用（后端最坏要 1000 秒）。`settledRef` 治不了这个。
**修法**：加客户端看门狗 `AbortController`。

### 7.4 次要项
- `Canvas.tsx` 字体预加载（`FONT_PRELOAD_TIMEOUT_MS = 2000`）随 `elements` 每次 effect 触发，整段场景文本重复 `document.fonts.load`，可按文本 memoize
- 分割线有 `role="separator"` 但无键盘操作 / `aria-valuenow`
- `App.css` 192 行全局样式，无 CSS module（当前规模可接受）

---

## 八、建议执行顺序

1. `git push origin reframing` + 提交 `prompt/` 包 —— **零风险，先做**（key 已核验安全）
2. ✅ `.env`/`.env.example` **均已有** `LLM_API_KEY`（无缺失，已核实）；compose Redis 端口已改为只绑 `127.0.0.1`（2026-10-17 已做）
3. `DiagramController` 换线程池 + 三个 SseEmitter 回调（收益最大：省 token、止泄漏）
4. 两处竞态修复 + `SessionStore` 加 TTL
5. `SceneGate` 改 `SceneDoc→SceneDoc` 单次解析；ObjectMapper 收敛为 `@Bean`
6. `ScenePlanner` / `PromptService` 拆类；`LayoutFamily` 改注入
7. 给 `sceneAdapter.ts` 补 vitest（顺手把 README 里的 184/276 改成真实数字 388）
8. ✅ 决断 `SceneLayout` 与 `feedback` 死代码（2026-10-17 二审：**全部保留**——四项有活跃测试、`shapeCoverage` 是 ref_cat 事故后刻意保留的诊断项；详见 §3.3/§3.4 的「决断」小节）
9. 依赖升级（Spring Boot 3.2.0 → 3.3+/3.4+，compiler plugin 升级，Java 21）

> 建议**一步一提交**：在 48 个未推送提交的基础上小步走，出问题可单独回退。

---

## 附录：核验方法

以下结论均由命令实测得出，可复现：

- 行数：`Get-Content <file>).Count`（总行）/ `Measure-Object -Line`（非空行）
- 死代码：`git grep -n "<符号>"` 全库无命中
- 竞态/线程：`git grep -n "Executors\.|ExecutorService|@Async|@EnableAsync|@Scheduled|ThreadPoolTaskExecutor|onCompletion|onTimeout|onError"` = 0 命中
- ObjectMapper：`git grep -c "new ObjectMapper()"` = 26；`git grep -c "static final ObjectMapper"` = 13；`git grep -rn "@Bean"` = 0 命中（exit 1）
- 密钥安全：`git grep` tracked 文件 / `origin/reframing..reframing` 48 个提交树均无真实 key；`git log --all -S <key片段>` 全历史 0 命中
- 测试数：`Get-ChildItem src\test -Recurse -Filter *.java | Select-String '@Test'` = 388
