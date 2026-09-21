# 模块计划 · LLM 网关加固（并行轨 M13）

> 状态：规划中
> 关联架构：`architecture.md` 2.4 包表（`llm/`）；ADR-3（LLM 调用收口到 llm/）；`docs/constraint.md` 日志规范
> 所属阶段：**并行轨（不占 S 顺序槽位）**；建议 **M10 上下文工程开工前就绪**——M10 / M4 / M11 均经 `llm/` 出窗口，网关越早收口越稳；可随时插入，不阻塞三条 AI 主轴
> 覆盖任务：v2-51 ~ v2-52
> 方向来源：用户 2026-09-20 在交叉阅读 javaguide《llm-api-engineering》《structured-output-function-calling》后拍板「**Q2-A = 现在做最小 LLM 网关**」
> **决策落地（Q2-A，2026-09-20 用户拍板）**：最小 LLM 网关 = ① 统一收口（ADR-3 强制，业务不得直连 Provider）② 8 段调用链 + SSE 异常（取消 / 超时 / 断流 / 重连）处理 ③ 指数退避重试（可重试 5xx / 429 / 解析错，max 2；非重试 4xx / 401 / 403）④ 每调用 token / 成本 / 延迟日志（落 M7 可观测）。**不含限流 / 多租户**（单用户本地，见 §边界）。

## 元信息
- 模块编号：M13（网关工程轨：**不对应业务功能、不进 `coreFun.md` 功能清单**，与 M12 同属"底座"类）
- 状态：规划中
- 创建：2026-09-20 + AI

## 目标
把今天散在 `GenerationService` / `PromptService` 里的"直接调 `llmProvider.chat`"收敛成**一个 `llm/` 网关层**，统一负责：调用链编排、SSE 流式异常、重试退避、以及每调用的成本/延迟埋点。
让"换模型 / 加 Provider / 查一次出图花了多少 token 和钱"都只改一处，且重试与降级行为一致、可观测（数据供 M7 消费）。

## 边界
- **允许新增**：`llm/` 内 `LLMGateway`（统一入口）、`RetryPolicy`（指数退避 + jitter）、`CallMetrics`（每调用 token/成本/延迟 记录）、`SseErrorHandler`（取消/超时/断流/重连）
- **允许修改**：现有 `llm/` Provider 抽象（补收口校验：业务调用必须经 `LLMGateway`，不经裸 Provider）；`GenerationService` / `PromptService`（改调 `LLMGateway`）
- **禁止改动**：业务功能语义（M1~M6 已定）；**禁止引入限流 / 多租户 / 配额系统**（单用户本地，超出本期 scope，见 `constraint.md` §二）
- **不做**：生产级多实例 / 负载均衡 / 熔断集群（维持单进程单实例）

## 分层边界
落在 `llm/`（与 ADR-3 一致）；被 `service/` / `agent/` / `context/` / `rag/` 调用，不反向依赖业务包。
**与 M7 的切分（关键，避免重复）**：**M13 负责"产生"每调用的成本/重试/延迟原始记录**（在调用点打点）；**M7 负责"消费"这些记录做成 Trace / 指标 / 审计看板**。M13 不建 dashboard，M7 不碰重试逻辑。

## 前置
- 各模块落地（S1~S5）或至少 M10 开工前（建议更早）
- 日志规范已生效（见 `constraint.md` 六）

## 步骤
1. **v2-51 统一收口 + 重试退避 + SSE 异常**：
   - `LLMGateway` 作为唯一出口：所有 `chat` / `chatStream` 经它；加收口校验（业务直接 new Provider 调用在 review 阶段即拦）。
   - **8 段调用链**收口：构造请求 → Prompt 缓存命中判断 → 发请求 → 收流式(SSE 空行分隔) → 解析 → 校验 → 重试判断 → 返回。
   - **SSE 异常**（javaguide llm-api-engineering）：客户端取消 / 网关超时 / 供应商断流 / 可重连 四类分别处理，不静默丢。
   - `RetryPolicy`：**可重试** = 5xx / 429 / 解析失败；**不可重试** = 400 / 401 / 403（直接报错，不白烧）；指数退避 + 随机 jitter，**上限 2 次**（用户拍板"最小"，不堆 3 次）。
2. **v2-52 每调用成本 / 延迟埋点（供 M7 消费）**：
   - `CallMetrics` 每调用记：模型 / `promptVersion`(来自 M10) / 输入+输出 token / 估算成本 / TTFT(首 token) / E2E 延迟 / 是否重试 / 是否 429 / 是否解析失败 / 是否取消。
   - 落日志（按 `constraint.md` 六 字段命名）+ 内存聚合；M7 的 `TraceService` / metrics 直接读这份原始记录，不重复打点。
   - **指标口径**（与 javaguide 对齐）：TTFT / E2E / tokens / retry 率 / 429 率 / parse-fail 率 / cancel 率。

## 验收口径
1. 业务代码无一处直连裸 Provider（grep `llmProvider.chat` 仅存于 `llm/` 内部）；所有出图调用经 `LLMGateway`。
2. 制造一次 429 / 5xx（mock 或供应商限流）→ 自动重试 ≤2 次后成功或明确失败，**不静默丢**、日志可见重试次数。
3. 制造一次 401 / 403 / 400 → **不重试**，立即返回明确错误（不白烧重试预算）。
4. 每调用日志含 token / 成本 / 延迟 + `promptVersion`；M7 能从这份记录聚合出 TTFT / 429 率 / retry 率。
5. SSE 中途取消 → 不报"成功"，状态记为 cancel，成本记录完整。

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 一次出图请求 | 经网关、日志含 token/成本/延迟、M7 可聚合 |
| 可重试 | 供应商返回 429 / 500 | 重试 ≤2 次，最终成功或明确失败，日志记重试 |
| 不可重试 | 返回 401 / 403 / 400 | 不重试，立即明确错误 |
| 流中断 | SSE 中途取消 / 断流 | 状态 cancel，不误报成功，成本记录完整 |
| 版本关联 | M10 带 promptVersion 出图 | 成本记录含 promptVersion，M14 可据此分组 |

## 完成后
移入 `state/completed/masterPlan/`；`state/activeLog/<YYYY-MM-DD>.md` 追加一行带证据。

## 状态跟踪
- [ ] v2-51 `LLMGateway` 统一收口 + `RetryPolicy`(指数退避 max2) + `SseErrorHandler`(取消/超时/断流/重连)
- [ ] v2-52 `CallMetrics` 每调用 token/成本/延迟埋点（含 promptVersion，供 M7 消费）
