# v2-7 checkstyle 接入（最小规则集）

## 元信息
- 任务编号：v2-7
- 状态：完成（2026-09-12）
- 关联模块：`plans/masterPlan/m0-harness.md`（M0 工程底座）
- 创建：2026-09-12 + AI ｜ 完成：2026-09-12 + AI

## 目标
把 checkstyle 接进质量门禁第 3 步，让"代码规范"从**永远跳过**变成**真生效**：违规即 `BUILD FAILURE` → `run-verify.ps1` 报 `VERIFY FAIL`、退出码非 0。

## 边界
- 允许新增：`checkstyle.xml`（项目根，与 `pom.xml` 同级）
- 允许修改：`pom.xml`（新增插件声明；不改动现有插件与依赖）
- 禁止改动：业务包任何文件（**含纯格式化**）—— M0 边界写明「禁止改动业务包功能实现」

## 分层边界
只落在**构建配置层**（项目根 `checkstyle.xml` + `pom.xml`），不进任何 Java 包，与业务代码正交。
原因：本任务属 Harness 四层中的"约束层"，与业务包刻意解耦。
**实际执行结果：全程零源码改动**（`git status src/main` 为空），与边界一致。

## 前置
- 需要联网下载 `maven-checkstyle-plugin 3.3.1` + `checkstyle 10.12.4` 及其传递依赖。**按项目约定由用户执行** —— ✅ 09-12 15:07 用户执行 `mvn checkstyle:check` 完成下载（此后 Agent 可离线复跑）。
- 更正：本项目 Maven 本地仓库**不是 `~/.m2`**，而是 `D:\maven-home\apache-maven-3.9.5-bin\apache-maven-3.9.5\repository`（由 Maven 安装目录 `conf/settings.xml` 的 `<localRepository>` 指定）。上一步"本地 `.m2` 无 checkstyle 构件"的表述口径有误，实际该表述指错了目录。

## 步骤
1. 写 `checkstyle.xml`：最小规则集 + 未启用清单（每条带理由）—— ✅ 09-12
2. `pom.xml` 挂 `maven-checkstyle-plugin 3.3.1`（显式 `<dependency>` 覆盖 checkstyle 版本为 10.12.4，以完整支持 Java 17 语法），**不绑生命周期**——只有门禁第 3 步显式调用才跑，避免拖慢 `mvn test` —— ✅ 09-12
   - 修正：`<configuration>` 的元素名须为 `<inputEncoding>`（mojo 字段名）；写成 `<encoding>` 会被 IDEA 的插件描述符校验判为"此处不允许使用元素"（`encoding` 只是该参数的用户属性名）。
3. 首次下载与验证 —— ✅ 09-12 15:07 由用户执行；门禁第 3 步首次真正生效
4. 按首次真跑结果处置违规（规则让步 / 代码让步）—— ✅ 09-12 见下"实测与处置"
5. 变异验证：故意写一段违规代码，确认门禁变红而非空转 —— ✅ 09-12

## 实测与处置（09-12）

首次真跑结果：**按已启用集 25 条违规；临时开启全部规则后实测 29 条**（临时改动已按 sha256 逐字节还原）：

| 规则 | 条数 | 位置 | 性质 | 处置 |
|---|---|---|---|---|
| `LeftCurly` | 24 | `model` 包单行 getter/setter | 风格偏好（非缺陷） | **放宽**：`tokens` 剔除 `METHOD_DEF`/`CTOR_DEF`（`LeftCurly` 无"放过单行块"的开关，官方文档核实） |
| `NeedBraces` | 2 | `DiagramService:206-207` 单行 if 无大括号 | 真 bug 苗子 | **暂缓**（修它要动 service，越界） |
| `MissingSwitchDefault` | 1 | `DiagramService:185` switch 无 default | 真缺陷信号 | **暂缓**（同上） |
| `AvoidStarImport` | 2 | `DiagramController:18`、`DiagramService:12` | 通配符 import | **暂缓**（同上） |

- **测试源码零违规**：v2-4 新写的 58 个测试用例经 39 条规则全过。
- 三条暂缓规则的启用条件统一为「M1 重建 service/controller 后」，写在 `checkstyle.xml` 末尾清单里（含理由与注意事项）。
- 左上角数字：`LeftCurly` 的合法 token 清单取自 jar 内元数据（10.12.4 为 24 个，**`SWITCH_RULE` 要到 10.15 才有**，按最新文档写会直接报 `unknown TokenTypes value`——已实测踩过并修正）。
- ⚠️ 方法论教训：本任务上一版验收口径 2 写的是"已用剥离注释/字符串字面量的精确扫描预验证"——**该自写预扫把 24 处单行访问器全部漏报**（只报了 2 处），由此得出过错误的"接上即绿"结论。**规则集是否干净只能以真跑 `checkstyle:check` 为准。**

## 验收口径
1. 门禁第 3 步不再打印"(跳过：未找到 checkstyle.xml)"，改为真实 checkstyle 输出，末行仍 `VERIFY PASS`、退出码 0 —— ✅ 第 3 步已真跑生效（用户 15:07 的真跑即证据）；`run-verify.ps1` 端到端复跑见下方说明
2. 干净代码零违规 —— ✅ 真跑结果 `You have 0 Checkstyle violations.` / `BUILD SUCCESS` / 退出码 0（**放宽 + 暂缓后**达到，非"原始规则集即干净"）
3. 故意写违规 → 门禁 FAIL、退出码非 0 —— ✅ 变异验证：临时加 `src/test/.../CheckstyleProbe.java`（一行 187 列）→ `LineLength` 违规、`BUILD FAILURE`、退出码 1（精准定位第 8 行）；删除后 → 0 违规、`BUILD SUCCESS`、退出码 0；临时文件无残留
4. `clean test` 仍 58 测试全绿 —— ✅ `Tests run: 58, Failures: 0, Errors: 0` / `BUILD SUCCESS` / 退出码 0

> 说明：本轮 Claude 侧 PowerShell 工具不返回输出（子进程输出无法捕获），`run-verify.ps1` 的端到端复跑改用**等价命令**完成（命令与脚本内部逐字一致：java 直启 Maven `clean test` + `checkstyle:check`，前端按 `-SkipFrontend` 语义跳过）。脚本端到端请用户在自己终端确认一次。

## 覆盖测试数据
| 场景 | 输入 | 期望 | 实测结果 |
|---|---|---|---|
| 正常 | 现有代码跑 `checkstyle:check` | 0 违规，BUILD SUCCESS | ✅ 0 违规 |
| 异常 | 单行超过 120 列 | LineLength 违规 → 门禁 FAIL | ✅ 187 列 → FAIL，退出码 1 |
| 异常 | 行尾加空格 | RegexpSingleline 违规 | 未单独构造（规则已启用，机制与 LineLength 同路径） |
| 异常 | 导入未使用的类 | UnusedImports 违规 | 未单独构造（同上） |
| 异常 | `if (x) y = 1;` 无大括号 | **本轮不报**（NeedBraces 暂缓） | ✅ 按预期不报，已成文记录为已知缺口 |
| 异常 | `import java.util.*;` | **本轮不报**（AvoidStarImport 暂缓） | ✅ 按预期不报，同上 |
| 边界 | 空行 / 少量 import 跨行 | 不得误报 | ✅ 零误报 |
| 边界 | Java 17 `record` 声明 | 不得因语法版本报解析错误 | ✅ 无解析错误（checkstyle 10.12.4 支持 record） |

## 完成后
- ✅ 移入 `state/completed/`
- ✅ 更新 `plans/masterPlan/m0-harness.md` 状态行
- ✅ `state/activeLog/2026-09-12.md` 追加带证据记录（命令 + 输出 / 退出码）
- ✅ `docs/Metadata.md` 变更记录登记
