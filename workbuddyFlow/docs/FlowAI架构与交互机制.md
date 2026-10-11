# FlowAI 架构与交互机制

> 综合文档：前端选择、IR 数据结构、SSE 事件机制、线程模型、校验与重试

## 一、整体架构

### 1.1 系统架构图

```
+-------------------------------------------------------------+
|                          用户层                             |
|  +---------------------+       +---------------------+     |
|  |      浏览器          |       |       聊天框         |     |
|  |    (Excalidraw)     |       |      (用户输入)      |     |
|  +----------+----------+       +----------+----------+     |
+-------------+-----------------------------+----------------+
              |                             |
              |  POST /api/chat             |
              |  PUT /api/model/canvas      |
              V                             V
+-------------------------------------------------------------+
|                    后端层 (Spring Boot)                      |
|  +---------------+  +---------------+  +---------------+   |
|  |   Controller  |  |    Service    |  |      LLM      |   |
|  |   (接口层)    |--|    (业务层)   |--|    (AI层)     |   |
|  +-------+-------+  +-------+-------+  +---------------+   |
|          |                  |                               |
|          |                  V                               |
|          |         +---------------+                       |
|          |         | Session Store |                       |
|          |         |   (缓存层)    |                       |
|          |         +---------------+                       |
|          |                                                 |
|          +-------------------------------------------------+
|                        SSE 事件流                           |
+-------------------------------------------------------------+
```

> **AI 层已重构为多候选故障转移（2026-10-07 / 10-10）**：图中的「LLM（AI 层）」不再是单一 `LlmProvider.chat(prompt, timeout)` 调用，而是一个 `LlmProvider` 接口 + `FailoverLlmProvider`（`@Primary`，由 `llm.failover-enabled=true` 启用）构成的候选链。生成循环通过 `LlmEffort`（难度档 = 轮次与需求规模）筛出本轮可用的候选，按 tier 升序逐个尝试，失败按 `LlmFailureKind` 分类冷却后自动切换；真正的流判死依据是**流式空闲超时**而非总时长。详见 [§3.4 LLM 调用层（多候选故障转移）](#34-lm-调用层多候选故障转移)。

### 1.2 前端选择

FlowAI 需要支持**"手绘白板 + AI 改图"双通道协作**：

| 方案 | 优点 | 缺点 |
|------|------|------|
| **React Flow** | 结构化流程图强 | 手绘能力弱 |
| **Excalidraw** | 手绘自然，可嵌组件 | 结构化编辑弱 |
| **自绘 Canvas** | 完全可控 | 开发成本高 |

**选择 Excalidraw**：贴合手绘直觉，`convertToExcalidrawElements` 能渲染 IR。

> ⚠️ **frame 裁剪（渲染层已知坑，2026-09-18 修复）**：`convertToExcalidrawElements` 会按「绑定元素归属哪个 frame」**自动给元素反填 `frameId`**，把跨区箭头收编成某 frame 的子元素；而 frame **会裁剪框外的子元素**——于是连接不同区域的连线，落在所属 frame 边界外的那段被整段吃掉（用户原话「线被截断 / 整段消失」）。现由 `frontend/src/lib/sceneAdapter.ts` 的 `detachCrossFrameArrows`（接入 `finishConversion`）兜底：**两端绑定元素归属不同 frame** 的箭头清掉 `frameId` 变自由元素（不再被裁），框内箭头保留 `frameId` 仍随框移动。

> ⚠️ **同文件另外两处渲染层修复（2026-10-10）**，文档此前均未覆盖：`bindContainerTexts` 补全 `text.containerId` 的**双向绑定**（把文字回填进容器 `boundElements` 并居中，否则独立文字带合法 `containerId` 会静默不渲染——三图验收实测 17 个容器内文字全黑）；`healCorruptedArrows` 用边界不变量自愈被 Excalidraw 绑定重算污染的连线几何（Bug B）。此外 `convertScene` 现为**三级降级兜底**（整批失败 → 剔嫌疑绑定重试 → 逐元素拼接），保证「只要还有一个元素转得出来就绝不返回空场景」。

### 1.3 前后端交互图

```
+------------------------+              +------------------------+
|                        |              |                        |
|    前端 (Excalidraw)   |              |   后端 (Spring Boot)   |
|                        |              |                        |
+-----------+------------+              +------------+-----------+
            |                                        |
            |  1. POST /api/chat                     |
            |  {sessionId, message}                  |
            |  ----------------------------------->  |
            |                                        |
            |                                        |  2. new Thread()
            |                                        |     generationService
            |                                        |     .generate()
            |                                        |
            |  3. SSE 事件流                         |
            |  <-----------------------------------  |
            |  thinking/validation/result            |
            |                                        |
            |  4. convertScene()                     |
            |     updateScene()                      |
            |                                        |
            |  5. PUT /api/model/canvas              |
            |  {positions, removedIds, ...}          |
            |  ----------------------------------->  |
            |                                        |
            |                                        |  6. sessionStore
            |                                        |     .updatePositions()
            |                                        |     .removeElements()
            |                                        |     .setUserElements()
            |     .releaseBindings()  ← 用户拖动连线解除绑定  |
            |                                        |
            |  7. 下次生成使用最新 IR                |
            |                                        |
```

## 二、IR（Intermediate Representation）

### 2.1 定义

IR 是白板内容的**唯一真相源**，包含白板元素的语义和坐标数据。

### 2.2 元素分层机制

#### 为什么分成两种元素？

```
+-------------------------------------------------------------+
|                    用户手绘元素（上层）                       |
+-------------------------------------------------------------+
|  * 不带 customData.chartflowModel 标记                      |
|  * 用户自由绘制的图形                                        |
|  * AI 生成新图时保留不覆盖                                   |
+-------------------------------------------------------------+
                            +
+-------------------------------------------------------------+
|                    AI 生成元素（下层）                        |
+-------------------------------------------------------------+
|  * 带 customData.chartflowModel = true 标记                  |
|  * 后端 IR 生成的图形                                        |
|  * 新图生成时只替换这部分                                     |
+-------------------------------------------------------------+
```

#### 为什么要分层？

| 原因 | 说明 |
|------|------|
| **防止覆盖** | AI 生成新图时只替换 AI 元素，用户手绘保留 |
| **用户体验** | 用户标注压在最顶层，方便查看和操作 |
| **数据隔离** | 两类元素分开管理，互不干扰 |
| **历史保留** | 用户手绘内容可以跨多轮对话保留 |

#### 判断方法

```typescript
// sceneAdapter.ts
export const MODEL_FLAG = 'chartflowModel';

export const isModelElement = (el: { customData?: unknown }): boolean => {
  const data = el.customData as Record<string, unknown> | null | undefined;
  return data?.[MODEL_FLAG] === true;
};
```

### 2.3 IR 数据结构

```
+-------------------------------------------------------------+
|                      IR 数据结构                             |
+-------------------------------------------------------------+
|  {                                                          |
|    "elements": [                                            |
|      +-------------------------------------------------+    |
|      |  rectangle  |  ellipse  |  diamond  |  arrow    |    |
|      |  矩形      |  椭圆     |  菱形     |  箭头     |    |
|      +-------------------------------------------------+    |
|      |  line      |  text    |  frame    |  freedraw  |    |
|      |  直线      |  文本    |  画框     |  手绘      |    |
|      +-------------------------------------------------+    |
|    ]                                                        |
|  }                                                          |
+-------------------------------------------------------------+
```

### 2.4 支持的元素类型

| 类型 | 必填字段 |
|------|----------|
| **shape** (rectangle/ellipse/diamond) | id, type, x, y, width, height |
| **arrow** | id, type + (start.id+end.id 或 points) |
| **line** | id, type, x, y, points |
| **text** | id, type, x, y, text |
| **frame** | id, type, x, y, width, height |
| **freedraw** | id, type, x, y, points |
| **image** | id, type, x, y, width, height, fileId（前向兼容：校验器已识别，渲染层透传 `fileId`） |
| **embeddable** | id, type, x, y, width, height, url（前向兼容；前端把 `url` 对齐到 Excalidraw 的 `link`） |
| **magicframe** | id, type, x, y, width, height（前向兼容；前端在 `sceneAdapter.ts` 降级为 `frame` 渲染，`children` 仍由后端补） |

> **v2-40（S1a「契约容量」）扩展的可选字段**（全部可选，旧场景 JSON 原样可渲染，新增字段不填时退化为旧行为）：
> - shape 新增：`strokeStyle`（原仅 arrow/line 支持）、`roundness`、`link`、`fillStyle: zigzag`
> - arrow 新增：`startArrowhead` / `endArrowhead`（12 种端点，含数据库鸦脚 `crowfoot_many`）、`link`
> - text 新增：`verticalAlign`、`autoResize`、`link`
> - 层次：`shape.group`（模块名，自由文本，由后端编译为 `groupIds` 与 frame 成员）、`frame.children`（成员列表，由后端 `ScenePlanner` 计算、**不在契约里手填**）
>
> 详见 `state/active/v2-40-contract-capacity.md`。

### 2.5 IR 存储架构

```
+-------------------------------------------------------------+
|                      IR 存储架构                             |
+-------------------------------------------------------------+
|                                                             |
|  +-----------------+      SSE 双向同步      +-------------+ |
|  |  前端 Excalidraw | <------------------> |  后端 Session| |
|  |  (渲染镜像)      |                       |  Store       | |
|  +-----------------+                       |  (唯一真相源) | |
|                                             +------+------+ |
|                                                    |        |
|                              +---------------------+        |
|                              |                             |
|                              V                             V |
|                    +-----------------+    +-----------------+|
|                    |  InMemoryStore  |    |  RedisStore     ||
|                    |  (内存，M1)     |    |  (Redis，M5)    ||
|                    +-----------------+    +-----------------+|
|                                                             |
+-------------------------------------------------------------+
```

> **存储实现状态（与图不一致，需更正）**：图中并列的 `RedisStore` 目前**尚未实现**——`SessionStore` 接口只有 `InMemorySessionStore` 一个实现（M1，进程内内存）。`pom.xml` 里虽引入了 `spring-boot-starter-data-redis`，但**没有任何代码使用它**（属于 M5 预留依赖，未接线）。因此当前部署下单实例重启即清空会话模型；多实例/持久化需等 M5 落地。文档其余各处提及的 session 读写均指 `InMemorySessionStore`。

## 三、SSE 事件机制

### 3.1 事件类型

```
+-------------------------------------------------------------+
|                      SSE 事件类型                            |
+-------------------------------------------------------------+
|                                                             |
|  +-------------+  +-------------+  +-------------+         |
|  |  thinking   |  | validation  |  |   result    |         |
|  |  思考中     |  |  校验中     |  |   结果      |         |
|  +-------------+  +-------------+  +-------------+         |
|  | "第N轮      |  | "校验中..." |  | 完整 IR     |         |
|  |  生成中..." |  | "失败:N个   |  | JSON        |         |
|  |             |  |  问题"      |  |             |         |
|  +-------------+  +-------------+  +-------------+         |
|                                                             |
|  +-------------+                                           |
|  |   error     |                                           |
|  |   错误      |                                           |
|  +-------------+                                           |
|  | 错误信息    |                                           |
|  +-------------+                                           |
|                                                             |
+-------------------------------------------------------------+
```

### 3.2 事件判断流程

```
+-----------------+
|    生成开始     |
+--------+--------+
         |
         V
+-----------------+
| thinking        |  sendEvent(emitter, "thinking", ...)
+--------+--------+
         |
         V
+-----------------+
| llmProvider     |  llmProvider.chat(messages, roundTimeout)
| .chat()         |  → FailoverLlmProvider 多候选故障转移（见 §3.4）
+--------+--------+
         |
         V
+-----------------+
| sceneBinder     |  bind(): 吸附归一化，两点箭头升级为绑定式
| .bind()         |
+--------+--------+
         |
         V
+-----------------+
| sceneDeduper    |  dedup(): 去重（与用户手绘/自身重复线）
| .dedup()        |
+--------+--------+
         |
         V
+-----------------+
| sceneSanitizer  |  sanitize(): 宽容归一化（修空 label / 直写字符串 label）
| .sanitize()     |  （2026-10-07，零信息损失修复，免得为一个空 label 白烧一轮）
+--------+--------+
         |
         V
+-----------------+
| scenePlanner    |  claimFramesOnly(): 只填 frame.children（画框认领）
| .claimFramesOnly|  （v2-41 原生坐标模式：不再调后端布局引擎重排几何）
+--------+--------+
         |
         V
+-----------------+
| validation      |  sendEvent(emitter, "validation", "校验中...")
+--------+--------+
         |
         V
+-----------------+
| parseGate      |  解析闸门（2026-10-10）：STRICT_JSON 严格读一遍，
|                |  读不通 ⇒ 转一条 issue 交修正轮，且不记 lastParseable
+--------+--------+
         |
         V
+-----------------+
| graphValidator  |  graphValidator.validate(json)  ← 结构/语义校验
| .validate()     |  + geometryAudit.audit(json)   ← 几何体检（v10.0.0）
+--------+--------+
         |
    +----+----+
    │         │
    V         V
+-------+  +-------+
| 通过   |  | 失败   |
+---+---+  +---+---+
    │         │
    V         V
+-------+  +-----------+
|result |  |validation |  "失败: N 个问题"
| 事件  |  | 事件      |
+-------+  +-----------+
                  |
                  V
           +-----------+
           | 重试?     |  ≤3轮
           +-----------+
                  |
           +------+------+
           |             |
           V             V
     +----------+  +----------+
     | 继续     |  | 最终失败  |
     +----------+  +----------+
```

### 3.3 SSE 配置

| 配置 | 值 | 说明 |
|------|-----|------|
| **TOTAL_BUDGET_MS** | 900,000 ms (15分钟) | 生成流程总时间预算（`GenerationService` 常量；2026-10-07 由 420s 上调到 900s，因多候选故障转移后单轮可能跨多个候选） |
| **SSE_TIMEOUT_MS** | 920,000 ms (≈15.3分钟) | SSE 连接超时 = `TOTAL_BUDGET_MS + 20,000`（`DiagramController` 推导，恒等与预算对齐，避免流被容器掐断） |
| **MAX_ROUND_SECONDS** | 420 s | 单轮 LLM 调用硬上限（秒），实际取 `min(本值, 剩余预算 − TAIL_RESERVE_MS)` |
| **MIN_ROUND_SECONDS** | 10 s | 剩余预算低于此值不再开新一轮，直接走兜底下发 |
| **TAIL_RESERVE_MS** | 30,000 ms | 留给「发结果 + 关闭流」的收尾余量，不参与 LLM 调用 |
| **MAX_ROUNDS** | 3 | 最大轮次 = 1 次首生成 + 最多 2 次修正轮；只有校验报 issue 才进下一轮 |
| **TEXT_EVENT_STREAM_VALUE** | text/event-stream | 响应媒体类型 |

## 3.4 LLM 调用层（多候选故障转移）

> **这是 2026-10-07 / 10-10 的一次架构重构，旧文档完全没有覆盖。** 此前是单家 `LlmProvider.chat(prompt, timeout)`；现在改成「`LlmProvider` 接口 + `FailoverLlmProvider` 候选链」，以应对免费模型额度/过载不稳定的现实（实测单家glm-4-flash 常年 429/1305 过载）。

### 3.4.1 组件

| 组件 | 职责 |
|------|------|
| **`LlmProvider`（接口）** | 3 个重载：`chat(String)` / `chat(String, int)` / `chat(List<Message>, int)`。**生成循环用的是 `chat(List<Message>, int)`**——入参是 system + user 两条消息与本轮超时（秒），不再是拼接好的单个 prompt 字符串。 |
| **`FailoverLlmProvider`** | `@Primary`，仅当 `llm.failover-enabled=true` 生效。持有候选表，按 tier 升序逐个尝试；单候选失败按 `LlmFailureKind` 分类冷却后切下一个；同一厂商（`baseUrl` 的 host）的其余候选在一次链里跳过，避免「一家全挂还反复重试」。 |
| **`LlmProperties`** | `@ConfigurationProperties(prefix="llm")`，含 `failoverEnabled` 与 `List<Candidate>`。每个候选字段：`tier` / `minRound` / `vision` / `largeOutput` / `maxTokens` / `timeoutSeconds` / `stream` / `idleTimeoutSeconds` / `baseUrl` / `apiKey` / `model`。 |
| **`LlmEffort`** | ThreadLocal 难度档。生成开始按需求规模（`RequestScale.estimate`）设 `scale`；每轮设 `round`。`effectiveEffort = max(round, scale≥LARGE ? 2 : 1)`，路由只放行 `minRound <= effort` 的候选。 |
| **`LlmFailureKind`** | `DETERMINISTIC` / `TRANSIENT` / `QUOTA` / `PERMANENT`。由 `classify(status, body)` **先读 body 语义、再按状态码兜底**（防止把「上下文超长」误归为限流/额度而摘掉整条链）。冷却：`DETERMINISTIC=0s`（不冷却）、`TRANSIENT=120s`、`QUOTA=600s`（其中 429 限流 60s）、`PERMANENT=停用`。`abortChain` 只在「上下文超长」为真时中断整链并抛 `LlmRequestRejectedException`。 |
| **`OpenAiCompatibleProvider`** | `@ConditionalOnProperty(name="llm.provider", havingValue="openai", matchIfMissing=true)`，OpenAI 兼容的流式实现。**判死主依据是「流式空闲超时」（`idleTimeoutSeconds`，连续多久没新内容才判死）而非总时长**——模型还在稳定吐字就一直等，不会因「跑得久」被误杀。截断（`finish_reason=length`）靠 `maxTokens` 兜底；每次调用累加 `TokenUsage`（整次一份账本、流结束打一行，规避魔搭流式每帧塞全 0 usage 占位导致刷屏）。 |

### 3.4.2 路由规则

```
effort = max(round, scale ≥ LARGE ? 2 : 1)
可用候选 = candidates 中 minRound ≤ effort 的那些（按 tier 升序）
```

> 后果：**小需求首轮只有 `minRound=1` 的便宜模型**（如 `zhipu-glm-4-flash`）；首轮没过校验（= 难任务）后，第 2 轮起才放开 `minRound=2` 的强模型（如 `ms-deepseek-v4.1-flash`、`ms-qwen3.8-flash-next`、`ms-step-3.7-flash`）。「图没变化 / 答非所问」先查日志两行：`生成轮次 x/3` + `可用候选 [...]`——若追问不升档、候选集合不变，则必然同模型同输出。

### 3.4.3 配置（`application.yml`）

- `llm.failover-enabled: true` —— 关闭则退回旧单家配置（`provider` / `base-url` / `api-key` / `model` / `timeout-seconds`）。
- `chartflow.layout.engine: elk`（默认 ELK 分层竖排；`legacy` 为自研零依赖退路）。
- 候选表字段示例（以 `ms-deepseek-v4.1-flash` 为例）：`tier:1, min-round:2, large-output:true, stream:true, timeout-seconds:420, idle-timeout-seconds:150, max-tokens:8192`。`max-tokens` 不设时用厂商默认（往往偏小，大图会被截断成半份 JSON）；设大了某些厂商会返 400，需实调。
- 实测剔除清单、免费清单会随厂商变动——这些 `model id` 故意外置在 `application.yml`（不写进 Java），并有每日巡检 automation 核对。

### 3.4.4 失败语义

单轮 `llmProvider.chat(...)` 抛异常时，**若还有下一轮且剩余预算够（`> MIN_ROUND_SECONDS`）**，不直接兜底，而是 `continue` 进下一轮重试——轮次本身就是「换一批更贵候选」的开关。只有预算耗尽或已到 `MAX_ROUNDS` 才走兜底下发（见 §6.2）。

## 四、线程模型

### 4.1 为什么启动新线程？

LLM 调用耗时 30-300 秒，必须避免阻塞 Spring MVC 请求处理线程。

```
+-------------------------------------------------------------+
|                    线程架构                                  |
+-------------------------------------------------------------+
|                                                             |
|  +-------------------------------------------------------+  |
|  |              Tomcat 线程池 (200个)                      |  |
|  |  +---------+ +---------+ +---------+ +---------+      |  |
|  |  | 线程 1  | | 线程 2  | | 线程 3  | |   ...   |      |  |
|  |  +---------+ +---------+ +---------+ +---------+      |  |
|  +-------------------------------------------------------+  |
|                           |                                 |
|                           | DiagramController.chat()        |
|                           V                                 |
|  +-------------------------------------------------------+  |
|  |           SSE 生成线程 (每个请求1个)                    |  |
|  |  +---------+ +---------+ +---------+ +---------+      |  |
|  |  | 生成线程1| | 生成线程2| | 生成线程3| |   ...   |      |  |
|  |  +---------+ +---------+ +---------+ +---------+      |  |
|  +-------------------------------------------------------+  |
|                                                             |
+-------------------------------------------------------------+
```

### 4.2 线程数量

| 场景 | Tomcat 线程 | SSE 生成线程 | 总计 |
|------|-------------|--------------|------|
| 静态（无请求） | 200 个 | 0 | ~251 个 |
| 3 个用户同时生成 | 200 个 | 3 个 | ~254 个 |
| 100 个用户同时生成 | 200 个 | 100 个 | ~351 个 |

## 五、校验机制（GraphValidator）

### 5.1 校验层次

```
+-------------------------------------------------------------+
|                      校验层次                                |
+-------------------------------------------------------------+
|                                                             |
|  +-------------------------------------------------------+  |
|  |  1. JSON 解析校验                                      |  |
|  |     检查 JSON 格式是否正确                              |  |
|  +-------------------------------------------------------+  |
|                           |                                 |
|                           V                                 |
|  +-------------------------------------------------------+  |
|  |  2. 结构校验                                          |  |
|  |     检查 elements 数组是否存在                          |  |
|  +-------------------------------------------------------+  |
|                           |                                 |
|                           V                                 |
|  +-------------------------------------------------------+  |
|  |  3. id 唯一性校验                                      |  |
|  |     检查每个元素的 id 是否唯一                          |  |
|  +-------------------------------------------------------+  |
|                           |                                 |
|                           V                                 |
|  +-------------------------------------------------------+  |
|  |  4. 逐元素字段校验                                     |  |
|  |     检查每个元素的必填字段                              |  |
|  +-------------------------------------------------------+  |
|                           |                                 |
|                           V                                 |
|  +-------------------------------------------------------+  |
|  |  5. 引用完整性校验                                     |  |
|  |     检查箭头端点是否指向存在的元素                      |  |
|  +-------------------------------------------------------+  |
|                                                             |
+-------------------------------------------------------------+
```

> **顺序修正（2026-10-10）**：上述 5 层是 `GraphValidator` 内部层次。在它**之前**还有一个独立的**解析闸门 `parseGate`**（`GenerationService`，用开启 `FAIL_ON_TRAILING_TOKENS` 的 `STRICT_JSON` 严格读一遍）——它先于 `validate` 执行，专门拦「JSON 后多了一句收尾话」这类下游（`JSON.parse`）会判非法的交付，拦下即转一条 issue 交修正轮，且不污染 `lastParseable`。`validate` 之后还会追加 `GeometryAudit.audit()`（几何体检，见 §5.4）。

### 5.2 校验内容

- **结构**：elements 数组存在（且非空——空数组 `{elements:[]}` 单独拦截，绝不落库下发，否则前端收到 0 元素当「成功生成」）
- **id**：唯一、格式正确
- **type**：在允许范围内；`GraphValidator.ALL_TYPES` 现含 11 种：`rectangle` / `ellipse` / `diamond` / `arrow` / `line` / `text` / `frame` / `freedraw` / **`image` / `embeddable` / `magicframe`**（后三者为前向兼容，校验器已识别并分别由 `validateImage` / `validateEmbeddable` / `validateMagicFrame` 处理）
- **箭头**：`start+end` 或 `points`；**不能自环**（`start.id == end.id` 判非法）；**`start/end` 与 `points` 可同时出现**（绑定式箭头的几何由两端图形决定，points 仍可附带）；**`line` 不能绑定**到图形（`line` 绑端点判非法）
- **group 只属前三种图形**：`shape.group`（模块名）只能出现在 `rectangle` / `ellipse` / `diamond` 上；出现在 `arrow` / `line` / `text` / `frame` 上判非法
- **relation**：连线语义值必须在 `RelationCatalog.ALL_RELATIONS`（10 种：`flow` / `call` / `data` / `async` / `dependency` / `one-to-one` / `one-to-many` / `many-to-many` / `inherit` / `block`），否则判非法
- **业务（仅编辑模式第一轮）**：① 改图保真 `SceneDrift`——保留 id 但整图重排坐标判为「整图重排」；② 换图检测 `detectRewrite`——旧元素 ≥3 且 id 重叠 <30% 判为「重新画了一张图」（<3 个要求新场景规模不显著缩水才报警）
- **可选枚举**（v2-40 起，硬编码集合，集合外值 → 整轮被判非法、白耗一整轮重试）：`fillStyle` / `strokeStyle` / `verticalAlign` / `startArrowhead` / `endArrowhead`（**12 种端点，含数据库鸦脚 `crowfoot_one` / `crowfoot_many`**）/ `textAlign` / `fontFamily` / `relation`（见上）
- **几何体检（`GeometryAudit`，v10.0.0，在 `validate` 之后追加 issue）**：只测量不改坐标，把「连线交叉 / 穿身 / 标签被压」量成 issue，借修正轮让 LLM 自己重摆。阈值：`CROSSING_MIN=3` 且占比 `≥0.33`、`THROUGH_MIN=3` 且占比 `≥0.25`、`LABEL_MIN=2` 且占比 `≥0.20`
- **解析闸门（`parseGate`，2026-10-10，在校验之前）**：用 `STRICT_JSON`（开启 `FAIL_ON_TRAILING_TOKENS`）严格读一遍，读不通（如 JSON 后多了一句收尾话）即转一条 issue 交修正轮，且**不记进 `lastParseable`**，堵住「坏 JSON 被记成可解析场景、兜底时下发 → 前端空白画布」的事故

### 5.3 Issue 结构

| 字段 | 说明 | 示例 |
|------|------|------|
| **field** | 问题字段 | "elements[0].type" |
| **reason** | 问题原因 | "类型不在允许范围内" |
| **hint** | 修复提示 | "type 只能是 rectangle/ellipse/diamond/arrow/line/text/frame" |

### 5.4 几何体检（GeometryAudit，v10.0.0）

> **定位**：校验器只查「结构合法性」，不查「画得能不能看」。几何体检是补上后者的「卷尺」——**只测量、不改坐标**，把 `validate` 看不出的观感问题量成 issue，借既有修正轮让 LLM 自己重摆。

- **触发位置**：`graphValidator.validate(json)` 之后，`GenerationService` 把 `geometryAudit.audit(json)` 的结果一并 `addAll` 进 `issues`。
- **三类问题**：
  - **连线交叉（crossing）**：两条非相邻箭头相交点 ≥ `CROSSING_MIN(3)` 且占该元素连线比例 ≥ `0.33`；
  - **穿身（through）**：箭头穿过一个非其端点的「带标签 / 已绑定」图形 ≥ `THROUGH_MIN(3)` 且占比 ≥ `0.25`；
  - **标签被压（label pressed）**：某标签框与另一图形重叠 ≥ `LABEL_MIN(2)` 且占比 ≥ `0.20`。
- **障碍物集合** = 带 `label` 或已绑定的 shape，**减去容器（frame）**；路径复刻 `edgePointTowards` 求包围盒边缘交点；并对「inset 端点」（箭头起/终点落在图形内部）报警。
- **设计纪律**：每次最多返回 0 或 1 条 `ValidationIssue`（避免一图几十个几何问题刷屏），且只报告不修改坐标——怎么挪由 LLM 在修正轮决定。

### 5.5 relation → 视觉样式编译（RelationCatalog）

> v2-40 前合法 `relation` 值散落在 `GraphValidator.RELATIONS` / `ScenePlanner.RELATION_STYLE` / `schema.arrow.relation` 三处各抄一份，扩值易漏改导致「有的环节认、有的环节不认」却静默不一致。现由 `RelationCatalog`（**代码唯一来源**）统管：

- `ALL_RELATIONS`：10 种合法值（`flow` / `call` / `data` / `async` / `dependency` / `one-to-one` / `one-to-many` / `many-to-many` / `inherit` / `block`），与 schema 枚举、提示词三方一致；
- `STYLE`：`relation → {strokeStyle, startArrowhead, endArrowhead}` 映射，例如 `flow`/`call` = solid + triangle，`async` = dashed + triangle_outline，`data` = dotted，`one-to-many` = crowfoot_one → crowfoot_many，`inherit` = solid + triangle_outline，`block` = bar → null。
- **扩值规则（改一处不够）**：本类 `STYLE` 加一项 → `schema.arrow.relation` 加枚举 → 提示词补说明，三者必须一致（`schema` 与提示词仍手抄，注释统一指向本类）。

## 六、重试机制

### 6.1 处理流程

```
+-------------------------------------------------------------+
|                      重试机制                                |
+-------------------------------------------------------------+
|                                                             |
|  +-------------+                                           |
|  |  LLM 生成   |  llmProvider.chat(messages, roundTimeout) |
|  |             |  → FailoverLlmProvider 多候选故障转移     |
|  +------+------+                                           |
|         |                                                   |
|         V                                                   |
|  +-------------+                                           |
|  | 绑定归一化  |  sceneBinder.bind(): 两点箭头→绑定式        |
|  +------+------+                                           |
|         |                                                   |
|         V                                                   |
|  +-------------+                                           |
|  |  去重       |  sceneDeduper.dedup(): 丢重复线             |
|  +------+------+                                           |
|         |                                                   |
|         V                                                   |
|  +-------------+                                           |
|  | 宽容归一化  |  sceneSanitizer.sanitize(): 修空 label      |
|  +------+------+                                           |
|         |                                                   |
|         V                                                   |
|  +-------------+                                           |
|  | 画框认领    |  scenePlanner.claimFramesOnly(): 填 children|
|  +------+------+                                           |
|         |                                                   |
|         V                                                   |
|  +-------------+                                           |
|  | 解析闸门    |  parseGate(): STRICT_JSON 严格读一遍        |
|  +------+------+                                           |
|         |                                                   |
|         V                                                   |
|  +-------------+                                           |
|  |  校验 IR    |  graphValidator.validate()                  |
|  |             |  + geometryAudit.audit()（几何体检）        |
|  +------+------+                                           |
|         |                                                   |
|    +----+----+                                             |
|    |         |                                             |
|    V         V                                             |
| +------+  +------+                                        |
| | 通过  |  | 失败  |                                        |
| +--+---+  +--+---+                                        |
|    |         |                                             |
|    V         V                                             |
| +------+  +----------+                                    |
| |保存  |  |构建修正   |  contextAssembler.assemble(         |
| |结果  |  |prompt    |    AssembleInput(round+1, issues))  |
| +------+  +----------+                                    |
|                     |                                      |
|                     V                                      |
|              +----------+                                  |
|              | 重试?    |  ≤3轮                            |
|              +----------+                                  |
|                     |                                      |
|              +------+------+                              |
|              |             |                              |
|              V             V                              |
|        +----------+  +----------+                        |
|        | 继续      |  | 最终失败  |                        |
|        +----------+  +----------+                        |
|                                                             |
+-------------------------------------------------------------+
```

### 6.2 重试策略

| 轮次 | 处理方式 |
|------|----------|
| 第 1 轮 | 正常生成；`contextAssembler.assemble(AssembleInput.of(sessionId, userText, effectiveSceneJson, editMode, 1, null, promptVersion()))` 组装 system+user 两条消息，校验失败则进入修正轮 |
| 第 2 轮 | `contextAssembler.assemble(... round+1, issues ...)` 把上一轮 issues 编进修正消息（旧的 `PromptService.buildFixPrompt` 已不再是生成入口——其职责拆给 `ContextAssembler` 的内容块方法；`buildFixPrompt` 仍保留为 delegate 但生成循环不再调用它），重新生成 |
| 第 3 轮 | 最后一轮修正轮；若仍校验失败 → 走兜底下发（编辑模式还原图、新建模式给占位框），并把 issue 摘要随 `validation` 事件上报，不再发旧图 |

> **语义变更（2026-09-17）**：原「第 3 轮失败则发送历史结果或错误」的**存档分支已整体删除**。现改为**「通过即发、失败才继续」**（`GenerationService` 注释原话）：只有 `graphValidator.validate()` + `geometryAudit.audit()` 报出 `issue` 才会进入下一轮，没 issue 就立刻 `saveModel + result + complete` 返回。第 3 轮仍失败不再发旧图，而是把 issue 明细随兜底 `validation` 事件上报。

> **2026-10-07 补充（LLM 调用失败不再无条件兜底）**：单轮 `llmProvider.chat(...)` 抛异常时，只要还有下一轮且剩余预算 `> MIN_ROUND_SECONDS`，就 `continue` 进下一轮重试（轮次本身就是「换一批更贵候选」的开关），而不是直接兜底。仅当预算耗尽或已到 `MAX_ROUNDS` 才走兜底。这修正了「首轮候选全挂 = 整次失败、永远轮不到 `minRound=2` 强模型」的事故。

## 七、缓存管理（SessionStore）

### 7.1 缓存触发条件

#### 启动项目后在画布上画图形会触发缓存吗？

**答案：需要满足两个条件**

```
+-------------------------------------------------------------+
|                      缓存触发条件                            |
+-------------------------------------------------------------+
|                                                             |
|  +-------------+                                           |
|  | 条件 1：    |  有 sessionId                              |
|  |             |  （用户已打开聊天框）                       |
|  +------+------+                                           |
|         |                                                   |
|         V                                                   |
|  +-------------+                                           |
|  | 条件 2：    |  前端调用 PUT /api/model/canvas            |
|  |             |  （用户在画布上操作后）                     |
|  +------+------+                                           |
|         |                                                   |
|         V                                                   |
|  +-------------+                                           |
|  | 触发缓存    |  sessionStore.updatePositions()            |
|  | 更新        |  sessionStore.removeElements()             |
|  |             |  sessionStore.setUserElements()            |
|  +-------------+                                           |
|                                                             |
+-------------------------------------------------------------+
```

#### 不同场景分析

| 场景 | 是否触发 | 原因 |
|------|----------|------|
| **只启动项目，没开对话** | ❌ | 没有 sessionId |
| **开对话但不说话** | ❌ | 没有画布操作 |
| **开对话后在画布上画东西** | ✅ | 有 sessionId + 前端调用接口 |
| **AI 生成后不操作画布** | ❌ | 没有用户操作 |

### 7.2 缓存作用

- AI 改图上下文
- 画布状态持久化
- 多轮对话上下文

### 7.3 修改时机

| 操作 | 修改内容 | 方法名 |
|------|----------|--------|
| AI 生成白板内容 | 覆盖整个 IR | `sessionStore.setCurrentModel()` |
| 用户拖拽 | 更新坐标 | `sessionStore.updatePositions()` |
| 用户删除 | 移除元素 | `sessionStore.removeElements()` |
| 用户手绘 | 存入 _userElements | `sessionStore.setUserElements()` |
| 用户拖动连线 | 解除绑定（用户拖线身体/端点把手解除两端绑定） | `sessionStore.releaseBindings()` |

> **画布同步请求体（`CanvasSyncRequest`）**：`PUT /api/model/canvas` 携带 `positions`（拖拽后坐标）、`removedIds`（删除的元素 id）、`userElements`（用户手绘描述）、`unboundArrows`（被解除绑定的连线几何：id / x / y / width / height / points）。`DiagramController.syncCanvas()` 依次调用上面 5 个 session 方法——其中 `releaseBindings` 接收 `unboundArrows`，把用户拖出来的自由箭头几何持久化回 IR，避免「下一轮渲染又把它绑回两端图形之间、用户拖的那一下白拖」。

### 7.4 删除时机

当前实现：**只有重启服务器时才会清空**（需要优化过期机制）。

## 八、完整交互流程

```
+-------------------------------------------------------------+
|                      完整交互流程                            |
+-------------------------------------------------------------+
|                                                             |
|  1. 用户输入需求                                            |
|     | POST /api/chat {sessionId, message}                   |
|     | 方法名: handleSend()                                  |
|     V                                                       |
|  2. 后端启动生成线程                                        |
|     | new Thread(() -> generationService.generate())        |
|     V                                                       |
|  3. thinking 事件                                           |
|     | sendEvent(emitter, "thinking", ...)                   |
|     V                                                       |
|  4. LLM 调用                                                |
|     | llmProvider.chat(messages, roundTimeout)              |
|     | → FailoverLlmProvider 多候选故障转移（见 §3.4）        |
|     V                                                       |
|  5. validation 事件                                         |
|     | sendEvent(emitter, "validation", ...)                 |
|     V                                                       |
|  6. 校验通过                                                |
|     | result 事件                                           |
|     | sendEvent(emitter, "result", json)                    |
|     V                                                       |
|  7. 用户操作画布                                            |
|     | PUT /api/model/canvas                                 |
|     | sessionStore.updatePositions()                        |
|     V                                                       |
|  8. 下次生成时                                              |
|     使用最新 IR 作为上下文                                   |
|                                                             |
+-------------------------------------------------------------+
```

## 九、关键概念

### 9.1 sessionId 和 message

| 概念 | 定义 | 作用 |
|------|------|------|
| **sessionId** | 会话 ID（UUID） | 关联当前图模型，区分不同会话 |
| **message** | 用户输入的消息 | 作为 prompt 的一部分传递给 LLM |

### 9.2 数据流总结

```
用户需求 → LLM 生成 → 校验 → 保存 → 推送 → 渲染 → 操作 → 更新
```

### 9.3 设计原则

1. **IR 唯一真相源**：后端持有，前端只持渲染镜像
2. **双向同步**：AI 改图和人类拖拽都汇到同一份 IR
3. **校验优先**：防止坏数据到达前端（含 `parseGate` 严格 JSON 关 + `GeometryAudit` 几何体检）
4. **重试机制**：最多 3 轮，平衡质量和时间；通过即发、失败才继续
5. **异步处理**：避免阻塞，提高响应性
6. **元素分层**：用户手绘在上层，AI 生成在下层，防止覆盖
7. **多候选故障转移**：免费模型不稳定，靠 `FailoverLlmProvider` 候选链 + 分类冷却 + 流式空闲判死保障可用性（见 §3.4）

## 十、调试建议

### 10.1 查看 SSE 事件

浏览器开发者工具 → Network → /api/chat → EventStream

### 10.2 查看 IR 数据

后端断点：`GenerationService.saveModel()` 方法

### 10.3 查看校验结果

后端断点：`GenerationService.generateInternal()` 方法

### 10.4 查看元素标记

前端控制台：检查元素的 `customData.chartflowModel` 属性