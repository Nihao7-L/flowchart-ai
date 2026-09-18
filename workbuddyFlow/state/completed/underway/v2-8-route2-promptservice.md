# v2-8 路线2 补全：PromptService 改由 scene.schema.json 派生 prompt

> 关联：v2-8 出图契约（`state/completed/underway/v2-8-scene-contract.md`）已落盘 `resources/schemas/scene.schema.json`，但 `PromptService` 仍残留旧"按图类型读 txt 模板"的实现，而 `templates/*.txt` 已被用户删除（路线2 取消图类型分类）→ 门禁 `clean test` 因 NPE 崩 5 例。
> 本计划 = 用户拍板的「路线二 / 选项 B：走完路线2」的即时动作：删 `PromptService` 对旧模板/图类型的依赖，改用 schema 派生 prompt，重写 `PromptServiceTest`。

## 目标
- `PromptService.buildPrompt(userText)` 不再读任何 txt 模板、不再有"图类型"参数；改为从 `schemas/scene.schema.json` 读取契约、内嵌进 prompt。
- prompt 与契约永不失同步：契约改字段，prompt 自动跟着变。
- 恢复门禁全绿（18 测试 0 失败 0 错误 + checkstyle 0 违规）。

## 边界
- 只改 `PromptService`（main）+ `PromptServiceTest`（test）+ 一处引用已删类的注释（`OpenAiCompatibleProvider`）。
- 不改 `scene.schema.json`（v2-8 已定稿）。
- 不做 `SchemaService` 校验（属 v2-9）、不做生成端点重建（属 M1）、不做布局算法（属 M6a）。
- 不恢复被用户删除的 `FlowchartData`/`MindmapData`/`ParserService` 及其模板（那是路线2 的既定删除，守边界不动）。

## 分层边界
- 表现/契约层：`resources/schemas/scene.schema.json`（唯一真相源，已落盘）。
- 应用层：`service/PromptService`（本计划改造对象）——负责"契约 → LLM prompt"的派生。
- 测试层：`service/PromptServiceTest` 断言产物含用户需求、内嵌契约、列出 8 种元素类型、指导箭头 start/end 绑定、schema 缓存生效。

## 前置
- v2-8 已落盘 `scene.schema.json`（存在且合法 draft-07）。
- `model/` 仅剩 `Result.java`；`service/` 仅剩 `PromptService.java`；`templates/` 为空（已删）。
- 运行时门禁基线：改造前 `clean test` 5 Errors（均来自 `PromptServiceTest` 因 `PromptService.loadTemplate` 读空模板 NPE）。

## 步骤
1. 重写 `PromptService`：`TEMPLATE_PATHS`/`loadTemplate(type)` 整段删除；新增 `SCHEMA_PATH` + `schemaCache`（ConcurrentHashMap）；`loadSchema()` 用 `ClassPathResource.getInputStream()` 读 `schemas/scene.schema.json`（对 JAR/文件系统均安全），懒加载 + 缓存；`buildPrompt(String userText)` 内嵌契约原文 + 作图规则（8 元素类型、箭头优先 start/end 绑定、坐标作种子值、只输出 JSON）。
2. 重写 `PromptServiceTest`：5 例改为验证"产物含用户需求 / 内嵌 `elements` 契约 / 列出 8 种 type / 指导箭头 start·end / 同输入两次结果一致"，不再依赖任何模板文件。
3. 修 `OpenAiCompatibleProvider.java:80` 注释：把已过时的 `ParserService` 引用改为"解析/校验层"。
4. 跑 `clean test` + `checkstyle:check` 复核。

## 验收口径
- [x] `PromptService` 无 `TEMPLATE_PATHS`、无 `buildPrompt(String,String)` 两参签名、无对 `templates/` 的任何引用。
- [x] `buildPrompt(userText)` 产物包含：用户原文 + `scene.schema.json` 原文（含 `"elements"`）+ 8 种元素 type + `start`/`end` 绑定指引。
- [x] `clean test`：Tests run: 18, Failures: 0, Errors: 0。
- [x] `checkstyle:check`：0 violations。
- [x] 未恢复任何被删的旧模型/模板文件（守路线2 删除边界）。

## 覆盖测试数据
- 用户需求样例：登录流程（"用户输入账号密码 → 系统验证 → 成功进入首页"）、架构示意（"画个架构示意"）、连线（"把 A 连到 B"）、缓存（"缓存测试"）。
- 元素类型断言集：`rectangle / ellipse / diamond / arrow / line / text / frame / freedraw`（8 种全覆盖）。
- 边界：同输入两次调用结果相等（验证 schema 缓存不引入随机性）。

---
状态：落盘完成（待归档）
落盘时间：2026-09-14
