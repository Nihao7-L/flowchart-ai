# M1 前置 · 旧链路代码清算

> 状态：已执行（2026-09-14）
> 关联架构：`../../docs/architecture.md` ADR-3 / ADR-4、2.4 包表
> 关联计划：`../masterPlan/m1-generation.md`（本文是其硬前置）
> 回滚点：HEAD `4d724f4`（清算前的提交，工作区与远端 `reframing` 一致）

## 元信息
- 编号：M1-PRE（不在路线图 v2-N 编号内，属 M1 开工前的一次性前置）
- 状态：已完成
- 创建：2026-09-14 + AI
- 决策来源：用户 2026-09-14 明确指示——① 不保留导出下载能力 ② 旧 UI 现在删 ③ 落一份清算清单；其余按 AI 判断清理

## 目标
M1 开工前把停在旧架构（`/api/generate` → PlantUML → SVG）上的代码清干净，使后端只保留"目标架构仍会复用"的资产，
避免新旧两条链路长期并存、以及旧链路悄悄继续被 Spring 装载。

## 边界
- 允许：删除与目标架构无关的类 / 资源 / 依赖；修剪受影响的测试；更正被牵连的注释
- 不允许：改动 `ParserService` / `PromptService` 的行为契约（它们是 M1 v2-8 / v2-9 的**改造对象**，不是删除对象）
- 不触碰：前端 `frontend/`、`docs/` 五份知识文档的结构、`run-verify.ps1`、`ci.yml`

## 分层边界
只动 `controller` / `service` / `model` / 资源目录与 `pom.xml`；不新增任何包。

## 前置
- M0 全闭合（v2-1 ~ v2-7），`run-verify.ps1` 可用
- 工作区干净且已推送（保证任何删除都可回滚）

## 处置清单

### A. 删除（与目标架构无关）
| 文件 / 项 | 行数 | 删除理由 |
|---|---|---|
| `resources/static/index.html` `app.js` `style.css` | 736 | 旧单页 UI，只调 `/api/generate` + `/api/download`；已被 v2-6 的 `frontend/`（Vite + React）取代。此前 Spring Boot 仍把它当首页，导致新旧两个前端并存 |
| `service/DiagramService.java` | 258 | 干两件事：IR → PlantUML 语法拼装、PlantUML → SVG/PNG 渲染。**目标架构后端不出图**（ADR-4：坐标与渲染由前端 Excalidraw 兜底），整类作废 |
| `service/DiagramServiceTest.java` | 254 | 随测试对象一起删 |
| `pom.xml` 的 `plantuml` 依赖 | — | 只服务于上一条的渲染功能 |
| `controller` 的 `POST /api/generate` | — | 旧主链路入口；目标入口是 `POST /api/chat`（SSE），由 M1 v2-11 补入 |
| `controller` 的 `POST /api/download` | — | 用户明确"不要导出下载能力" |
| `model/GenerateRequest.java` | 17 | 仅服务 `/api/generate` |
| `model/DownloadRequest.java` | 14 | 仅服务 `/api/download` |

### B. 顺带修掉的一个真实缺陷（不只是措辞）
`PromptService` 的模板缓存原用 `HashMap`，且注释以"这里是个人项目、请求量低"来自我合理化。
实际风险：缓存是**懒加载**的（首个请求到达才写），而 Spring MVC 的请求处理是**多线程**的——
`HashMap` 并发 `put` 可能破坏内部结构（链表成环），后续 `get` 陷入死循环；这个故障只在并发下暴露，单线程测试永远测不出来。
已改为 `ConcurrentHashMap`（`computeIfAbsent` 原子），并把注释重写为说明**真实原因**，而不是辩解。

### C. 保留（目标架构仍需要）
| 文件 | 为什么留 | M1 用途 |
|---|---|---|
| `client/LlmProvider.java` `OpenAiCompatibleProvider.java` | HTTP、代理、超时、错误处理、`extractContent` 是真实资产 | v2-11 迁 `llm/` 包 + 加流式 |
| `service/ParserService.java` | 校验语义（start/end 唯一、decision 两条出边、边引用存在）是有价值的输入 | v2-9 拆成 schema 校验 + `GraphValidator`（须改为收集多条 issue） |
| `service/PromptService.java` | 模板外置 + 懒加载 + 缓存的机制可复用 | v2-8 换模板内容为 schema 契约 + few-shot |
| `model/FlowchartData` `MindmapData` | IR 雏形（`nodes+edges` / 递归树） | 并入统一 IR |
| `FlowchartApplication` `Result` `OpenApiConfig` `/api/health` | 无旧架构耦合 | 直接沿用 |
| `resources/templates/*.txt` | 位置与加载机制保留；**内容目前仍是 PlantUML 提示词，属待替换资产** | v2-8 整体替换 |

> 过渡态说明：清算后 `ParserService` / `PromptService` / `templates/` 处于"**有测试覆盖但暂无调用方**"状态。
> 这是有意的——M1 v2-8 / v2-9 会立刻接上它们，因此本清单不把它们列为删除对象。

### D. 不动的
`frontend/`（v2-6 产物）、`docs/` 五份、`.github/workflows/ci.yml`、`workbuddyFlow/run-verify.ps1`。

## 测试连带影响
| 测试类 | 变化 | 说明 |
|---|---|---|
| `DiagramServiceTest` | 删除 | 随测试对象 |
| `DiagramControllerTest` | 15 → 3 | 重写为：health 契约 + 两条「旧端点不再被映射（404）」防回归断言 |
| `ModelContractTest` | 9 → 6 | 移除 GenerateRequest / DownloadRequest 三个用例 |
| `OpenAiCompatibleProviderTest` | 10 → 10 | 不动 |
| `ParserServiceTest` | 12 → 12 | 不动 |
| `PromptServiceTest` | 5 → 5 | 不动 |
| **合计** | **58 → 36** | |

## 验收口径与实测
1. `mvn clean test` → **Tests run: 36, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS（26.7s）✅
2. `mvn checkstyle:check` → **You have 0 Checkstyle violations**（5.9s）✅
3. 旧端点确已下线：测试日志出现 `No mapping for POST /api/generate` 与 `No mapping for POST /api/download`，404 断言通过 ✅
4. 源码内不再有"面试"框架化表述：`grep -rn "面试" src/` 无命中 ✅

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 健康检查 | `GET /api/health` | 200 + `code=200` / `message=ok` |
| 旧入口防回归 | `POST /api/generate` | 404（不再被映射） |
| 旧入口防回归 | `POST /api/download` | 404（不再被映射） |

## 遗留与后续
- `templates/*.txt` 内容仍是"教 LLM 写 PlantUML"，由 M1 v2-8 整体替换——**届时 `PromptServiceTest` 的模板断言需同步修改**
- `ParserService` / `PromptService` 暂无调用方，属过渡态；M1 v2-8 / v2-9 接上后即消除
- 本次为纯删减 + 注释修复，前端无改动，故未跑 `run-verify.ps1` 完整三步；push 后云端 CI 会自动复跑全套
- README 中残留的旧链路描述（PlantUML 示例、"多格式导出"特性等）随本次一并更正
