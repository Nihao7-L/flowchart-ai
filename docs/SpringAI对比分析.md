# 任务42：Spring AI 引入与对比

## 1. 为什么做这个对比

任务 29-41 全是手写——手写重试、手写结构化输出、手写 RAG、手写 Tool Calling。
**手写是为了学原理，框架是为了工程效率。**

引入 Spring AI 后能对比"手写 vs 框架"的差异，面试能讲"我从手写到框架的演进"。

## 2. 手写版 vs Spring AI 对比

### 2.1 LLM 调用层

| 维度 | 手写版（FlowAI） | Spring AI |
|------|-----------------|-----------|
| **核心类** | `OpenAiCompatibleProvider`（600+ 行） | `ChatClient`（框架封装，约 50 行配置） |
| **重试** | 手写 `withRetry()` + 指数退避 | `RetryTemplate`（Spring Retry，声明式） |
| **缓存** | `CachingLlmProvider`（手写装饰器） | `@Cacheable` 注解（Spring Cache 抽象） |
| **降级** | `FallbackLlmProvider`（手写链式降级） | `@Recover` 注解（Spring Retry） |
| **流式** | 手写 HTTP 流式读取 + SSE 推送 | `Flux<ChatResponse>`（响应式流） |
| **代码量** | ~1200 行（含 Provider + Fallback + Cache） | ~200 行（配置 + 调用） |
| **灵活性** | 高（完全控制每一步） | 中（框架约束，自定义需扩展点） |
| **学习价值** | 高（理解底层原理） | 中（理解框架抽象） |

### 2.2 结构化输出

| 维度 | 手写版 | Spring AI |
|------|--------|-----------|
| **Schema 约束** | 手动构造 `response_format` JSON | `BeanOutputConverter`（自动绑定 Java 类） |
| **JSON Mode** | 手动判断 `json_object` vs `json_schema` | 框架自动选择最佳策略 |
| **类型安全** | 运行时解析 + 手动校验 | 编译时绑定（Java 类 → Schema 自动生成） |
| **代码量** | ~300 行（OpenAiCompatibleProvider.chatStructured） | ~50 行（声明 Bean 即可） |

### 2.3 RAG（检索增强生成）

| 维度 | 手写版 | Spring AI |
|------|--------|-----------|
| **文档切分** | `RagService.split()`（递归字符切分 + 结构感知） | `DocumentReader` + `TextSplitter`（内置多种策略） |
| **Embedding** | `EmbeddingClient`（手写 HTTP 调用） | `EmbeddingModel`（Spring AI 抽象） |
| **向量存储** | `VectorStore`（内存 CopyOnWriteArrayList） | `VectorStore`（支持 pgvector / Chroma / Redis） |
| **检索** | 手写余弦相似度 + topK | `VectorStore相似度检索`（内置优化） |
| **重排序** | 手写去重 + 预算裁剪 | `ReRanker`（Spring AI 实验性 API） |
| **代码量** | ~400 行（RagService + VectorStore + EmbeddingClient） | ~100 行（配置 + 调用） |

### 2.4 Tool Calling

| 维度 | 手写版 | Spring AI |
|------|--------|-----------|
| **工具定义** | 手写 `Tool` 接口 + JSON Schema | `@Tool` 注解（自动反射生成 Schema） |
| **执行器** | `ToolExecutor`（手写解析 + 执行） | `ToolCallback`（框架自动调度） |
| **多轮回灌** | 手动拼 messages + tool_calls + tool result | `ChatClient.tool(toolCallback)`（链式调用） |
| **代码量** | ~500 行（ToolExecutor + ToolRegistry + 各 Tool） | ~150 行（注解 + 配置） |

### 2.5 会话记忆

| 维度 | 手写版 | Spring AI |
|------|--------|-----------|
| **消息存储** | `SessionService`（Redis + 内存降级） | `ChatMemory`（Spring AI 抽象，支持 Redis/JDBC） |
| **窗口策略** | 手写滑动窗口（保留最近 N 轮） | `MessageChatMemoryAdvisor`（内置窗口管理） |
| **上下文注入** | 手动拼 messages 数组 | `ChatClient.advisor(memory)`（声明式） |
| **代码量** | ~300 行（SessionService + SessionStore） | ~50 行（配置 + Advisor） |

## 3. 关键差异总结

### 3.1 手写版的优势
1. **完全可控**：每一步（重试策略、缓存 key、降级逻辑）都是自己写的，面试能讲清楚
2. **学习价值高**：理解了"框架在做什么"，面试时能对比"手写 vs 框架"
3. **无框架约束**：不依赖 Spring 生态，可以单独测试每个组件
4. **定制灵活**：遇到框架不支持的场景（如自定义 SSE 流式推送），手写版没有限制

### 3.2 Spring AI 的优势
1. **代码量少 70-80%**：配置驱动，声明式 API
2. **生产级特性**：内置重试、缓存、降级、可观测性
3. **生态集成**：与 Spring Boot / Spring Data 无缝集成
4. **社区维护**：Bug 修复、新模型支持由社区负责
5. **类型安全**：Java 类自动生成 Schema，编译时发现问题

### 3.3 选择建议
- **学习阶段**：手写版（理解原理）
- **生产环境**：Spring AI（工程效率）
- **混合模式**：核心链路手写（如自定义流式推送），外围用框架（如缓存、重试）

## 4. 迁移路径（如果要引入 Spring AI）

### 4.1 最小改动方案
1. 引入 `spring-ai-openai-spring-boot-starter` 依赖
2. 创建 `SpringAiLlmProvider` 实现 `LlmProvider` 接口
3. 通过配置开关 `llm.mode=handwritten|springai` 切换
4. 保留手写版作为 fallback

### 4.2 完整迁移方案
1. 用 `ChatClient` 替换 `OpenAiCompatibleProvider`
2. 用 `EmbeddingModel` 替换 `EmbeddingClient`
3. 用 `VectorStore`（Spring AI）替换自定义 `VectorStore`
4. 用 `@Tool` 注解替换手写 Tool 接口
5. 用 `ChatMemory` 替换 `SessionService`

### 4.3 风险与注意事项
- Spring AI 1.0.0 要求 Spring Boot 3.4+ / Java 17+
- 部分 API 仍在实验阶段（如 ReRanker）
- 与现有手写代码的接口可能不完全兼容

## 5. 面试话术

> "我在 FlowAI 项目中先手写了完整的 LLM 调用链（重试/缓存/降级/结构化输出/RAG/Tool Calling），
> 理解了每一层的原理。然后对比了 Spring AI 框架，发现它在重试/缓存/RAG 三个维度上
> 代码量减少 70-80%，但核心链路（流式推送/自定义 SSE）仍需手写扩展。
> 这个对比让我理解了'什么时候用框架、什么时候手写'的工程决策。"

## 6. 结论

**本项目暂不引入 Spring AI 依赖**，原因是：
1. 核心价值是"手写理解原理"，引入框架会削弱学习深度
2. Spring AI 1.0.0 的部分 API 仍在实验阶段，稳定性存疑
3. 现有手写代码已经满足需求，没有"不得不用框架"的场景

**但保留对比文档**（本文），作为面试时"手写 vs 框架"的差异化素材。
