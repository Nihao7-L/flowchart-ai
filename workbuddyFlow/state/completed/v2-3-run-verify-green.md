# 进行中计划 · v2-3 让 run-verify 真正变绿

> 来源：`plans/masterPlan/m0-harness.md` 的 v2-3 子计划（工作台副本）

## 元信息
- 任务编号：v2-3（与 masterPlan 对应）
- 状态：完成（09-12 本地验证绿：VERIFY PASS / 退出码 0）
- 来源模块：M0 工程底座（harness）
- 创建：2026-09-12 + AI

## 目标
本地运行 `run-verify.ps1` 末行打印 `VERIFY PASS` 且退出码为 0，使 M1 的硬前置（run-verify 可用）成立，主干得以往下走。

## 边界
- 允许新增：为定位代码根所需的最小脚本修正；若 mvn 损坏则修复/补 Maven Wrapper。
- 允许修改：`run-verify.ps1`（仅限"定位代码根 / 调用 mvn"相关逻辑）。
- 禁止改动：业务包功能实现（`controller/service/llm/graph` 等），本次只验证底座。

## 分层边界
落在 Harness 层（约束层 + 反馈层）：脚本与构建配置，不触碰业务包代码。

## 前置
- 代码基线 `reframing`@`70813bb` 可编译（见 architecture.md 七）。
- 本机存在可工作的 Maven 调用方式（PowerShell 下用 `mvn.cmd`；Git Bash 的 `mvn` 已知损坏）。
- 关键修正：`run-verify.ps1` 当前 `$PSScriptRoot` = `workbuddyFlow/`，但 `pom.xml` 在项目根 `flowchart/`；脚本必须先定位到代码根再查 pom。

## 步骤
1. 确认代码根：`flowchart/`（含 `pom.xml`），`workbuddyFlow/` 是知识层子目录。
2. 修正 `run-verify.ps1`：增加"代码根解析"——`$PSScriptRoot` 无 `pom.xml` 时上溯到父目录；`pom.xml` / `frontend/` / `checkstyle.xml` 均相对代码根判定。
3. PowerShell 执行 `powershell -File run-verify.ps1 -SkipFrontend`（先跳过前端，确认后端 `mvn clean test` 一关）。
4. 若 `mvn` 调用失败：定位是 PATH 缺失还是 Maven 安装损坏；优先用项目级 Maven Wrapper（`mvnw.cmd`），无则修复 PATH 指向可用 Maven。
5. 后端关通过后，去掉 `-SkipFrontend` 再跑一次（此时若仍无 `frontend/`，该步自动跳过，不影响 PASS）。

## 验收口径
1. 执行 `powershell -File run-verify.ps1` 末行为 `VERIFY PASS`，退出码 0。
2. 输出中可见 `[1/3] Maven compile + test` 真实执行且通过（非因找不到 pom 直接 FAIL）。
3. `m0-harness.md` 状态跟踪里 v2-3 由"待本地执行验证绿"改为已验证绿。

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 干净代码跑 run-verify | 末行 VERIFY PASS，退出码 0 |
| 异常 | 故意让某单测失败 | 末行 VERIFY FAIL，退出码 1，日志指出失败用例 |

## 完成后
- 状态行改「完成」；
- 移入 `state/completed/`；
- 更新 `plans/masterPlan/m0-harness.md` 对应 v2-3 子计划状态行（去掉"待验证绿"）；
- `state/activeLog/2026-09-12.md` 追加一行带证据记录（命令 + 退出码 + 末行）。
