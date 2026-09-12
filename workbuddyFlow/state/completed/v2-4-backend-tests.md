# v2-4 后端单测（第二批：HTTP 契约 / LLM 客户端 / 数据模型）

> 任务编号：v2-4（与 `plans/masterPlan/m0-harness.md` 步骤 4 对应）
> 状态：完成
> 来源模块：`plans/masterPlan/m0-harness.md`
> 创建：2026-09-12 + AI

## 元信息
- 任务编号：v2-4（与 `plans/masterPlan/` 对应子计划一致）
- 状态：完成
- 来源模块：见 `plans/masterPlan/m0-harness.md` 步骤 4
- 创建：2026-09-12 + AI

## 目标
把后端单测覆盖面从「仅 `service` 层」扩到 **HTTP 契约 / LLM 客户端 / 数据模型** 三处，让 M1 重构（`/api/generate` → `/api/chat`）有真正的回归安全网，而不是改完不知道坏了什么。

## 边界
- 允许新增：`src/test/java/io/github/nihaoljx/flowchart/{controller,client,model}/` 三个测试类
- 允许修改：无（本计划未修改任何既有文件）
- 禁止改动：业务包功能实现（`controller` / `service` / `client` / `model` 主源码）、`pom.xml`、`application.yml`

## 分层边界
落在 test 侧的 `controller` / `client` / `model` 三个包，与主源码包一一对应，**不触碰 `service`**（已有 24 个测试且全绿）。
为什么这样切：本轮补的是"改动风险最高、当前零覆盖"的三处——HTTP 契约（接口一改就漂）、LLM 调用拼装（外部依赖不可测）、数据模型（前端依赖的字段语义）。

## 前置
- `run-verify.ps1` 可用（v2-3 已完成）；
- **零新依赖**：`spring-boot-starter-test` 已含 JUnit 5 / Mockito / MockMvc / `ReflectionTestUtils`；LLM 桩服务用 JDK 自带的 `com.sun.net.httpserver`，不引入 MockWebServer。

## 步骤
1. 落盘 `DiagramControllerTest`：`MockMvcBuilders.standaloneSetup` + 四个依赖 mock（不启 Spring 上下文，不受 `application.yml` 影响）
2. 落盘 `OpenAiCompatibleProviderTest`：JDK `HttpServer` 在 `127.0.0.1` 随机端口起桩，`llm.base-url` 指向它，全程不触网
3. 落盘 `ModelContractTest`：`Result` 统一响应结构 / 请求 record 字段语义 / `MindmapData` 递归兜底
4. 全量 `mvn clean test`：24 → **58** 个测试全绿
5. 变异验证：把 `/api/generate` 的错误码改坏，确认测试变红，再还原并校验 sha256

## 验收口径
1. 全量 `mvn clean test`：`Tests run: 58, Failures: 0, Errors: 0` → `BUILD SUCCESS`、退出码 0 ✅
2. 新增 34 个测试全绿：Controller 15 / Client 10 / Model 9 ✅
3. `run-verify.ps1 -SkipFrontend` 末行 `VERIFY PASS`、`EXITCODE=0` ✅
4. 变异验证：错误码 `400 → 401` 后 `generateRejectsUnknownType` 变红（`expected:<400> but was:<401>`）、退出码 1；还原后 sha256 与改前逐字节一致 ✅

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | `/api/generate`：合法 text + `type=flowchart` | code 200，含 `data` / `svg` / `plantUml` / `type` 四字段 |
| 正常 | `type=mindmap` / `type=architecture` | 分别走 `parseMindmap+buildMindMap` / `parseArchitecture+buildArchitecture` |
| 边界 | `text` 恰好 2000 字 | 放行（不越界误杀） |
| 边界 | `type` 缺失 | 默认按 `flowchart` |
| 边界 | `format` 缺失（下载） | 默认导出 PNG |
| 异常 | LLM 未配置 / 文本空白 / 文本 null / 2001 字 / 非法 type / LLM 抛错 | 503 / 400 / 400 / 400 / 400 / 500，错误码与文案锁定 |
| 异常 | `/api/download` 渲染失败 | HTTP 500 |
| 正常 | 桩服务返回标准 OpenAI 响应 | 提取 `choices[0].message.content` |
| 边界 | prompt 内含 `"`、换行、`\` | Jackson 转义，请求体不破 |
| 异常 | HTTP 401 / 缺 `choices` / 空 `choices` / 缺 `message` / 空白 `content` / 非 JSON | 各自抛异常且消息可定位 |
| 边界 | `MindmapData.children = null` | 兜底为空列表，遍历不 NPE |
| 正常 | `Result.success/error`、record 相等性 | 字段语义与相等性锁定 |

## 完成后
- 状态行改「完成」；
- 移入 `state/completed/`；
- 更新 `plans/masterPlan/m0-harness.md` 里 v2-4 的状态行；
- `state/activeLog/<YYYY-MM-DD>.md` 追加一行带证据记录（命令 + 输出 / 退出码）；
- 若曾进入 `state/active/`，先移出再归档。（本计划未经 `state/active/`，直接归档）
