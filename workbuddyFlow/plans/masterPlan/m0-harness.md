# 模块计划 · 工程底座（Harness / 阶段0）

> 状态：进行中
> 关联架构：`architecture.md` 2.4 包表、`coreFun.md` 能力量级
> 覆盖任务：v2-1 ~ v2-7（v2-1 / v2-2 / v2-3 / v2-4 / v2-6 / v2-7 已完成，仅剩 v2-5 CI）

## 元信息
- 模块编号：M0
- 状态：进行中
- 创建：2026-09-11 + AI

## 目标
先把工程底座（知识层 + 验证闭环 + 约束层 + 前端脚手架）钉死，功能长在底座上，不再"先跑通再打补丁"。

## 边界
- 允许新增：AGENTS.md、`docs/` 五份、`plans/masterPlan/` 计划、`run-verify.ps1`、单测、`ci.yml`、前端脚手架、`checkstyle`
- 允许修改：`pom.xml`、构建配置、`.github/workflows`
- 禁止改动：业务包功能实现（本阶段只搭底座，不动 `controller/service/llm/graph` 等）

## 分层边界
落在根目录（`AGENTS` / `docs` / `plans/masterPlan`）、构建与 CI 配置、测试目录；不触碰业务包代码。原因：底座属于 Harness 四层中的"约束层 + 反馈层"，与业务包正交。

## 前置
- 代码基线 `reframing`@`70813bb` 可编译（见 `architecture.md` 七）
- 本机 Maven 直启可用（Git Bash 的 `mvn` 坏，用 java 直启，见 architecture.md 七）

## 步骤
1. v2-1 AGENTS.md 索引 + 四拍状态机 —— ✅ 已落地 09-11
2. v2-2 docs/ 五份知识文档（元信息/核心功能/工程约束/架构/自反馈） —— ✅ 已落地 09-11
3. v2-3 `run-verify.ps1`：`compile + test + 前端 check` 一条命令，末行 `VERIFY PASS`、退出码 0
4. v2-4 后端单测：`service` 层首批（24 个，09-11 已落地）→ 09-12 第二批扩至 `controller` / `client` / `model`（新增 34 个，全量 58 个）—— ✅ 已落地 09-12
5. v2-5 GitHub Actions 修正并真跑（`ci.yml` 引 `mvn`，CI 环境应可直接用）
6. v2-6 前端脚手架重建：Vite + React + TS strict + ESLint 空壳可跑
7. v2-7 checkstyle 接入（最小规则集）—— ✅ 已落地 09-12：规则集 + `pom.xml` 插件落盘且**门禁第 3 步真生效**（首次真跑 25 条 / 全规则实测 29 条 → 规则让步 24 + 暂缓 5 后零违规），变异验证证明违规即 FAIL

## 验收口径
1. `powershell -File run-verify.ps1` 末行 `VERIFY PASS`、退出码 0
2. CI 每次 push 真跑且绿
3. 前端 `npm run dev` 空壳可起
4. checkstyle 违规即 run-verify 失败 —— ✅ 09-12 已验证（变异验证：加 187 列行即 `BUILD FAILURE`、退出码 1）

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 干净代码跑 run-verify | PASS，退出码 0 |
| 异常 | 故意改坏一个单测 | FAIL，退出码非 0，日志指出失败用例 |

## 状态跟踪
- [x] v2-1 / v2-2 已落地
- [x] v2-3 run-verify.ps1（09-12 本地验证绿：后端 24 测试 BUILD SUCCESS，末行 VERIFY PASS、退出码 0；另修两处：脚本加 UTF-8 BOM 以兼容 PowerShell 5.1 中文解析、Maven 输出改用 Out-Host 消费以免退出码被污染）
- [x] v2-4 后端单测（09-12 第二批补 `controller` / `client` / `model` 三处零覆盖区，新增 34 个：MockMvc 契约 15 + LLM 客户端桩 10 + 数据模型 9；全量 58 绿、BUILD SUCCESS、退出码 0；另做变异验证——错误码 400→401 后 `generateRejectsUnknownType` 变红、还原后 sha256 一致，证明测试真在守契约。零新依赖、未改 `pom.xml` 与业务主源码）
- [ ] v2-5 GitHub Actions
- [x] v2-6 前端脚手架（09-12 `frontend/` 空壳跑通 npm ci / build / lint / dev；门禁三步等价验证全绿，见 activeLog 2026-09-12）
- [x] v2-7 checkstyle（09-12 完成：`checkstyle.xml`（启用 38 条规则）+ `maven-checkstyle-plugin 3.3.1`（checkstyle 10.12.4）已落盘且**门禁第 3 步真生效**（从"永远跳过"变为真检查，用户 15:07 真跑即证据）；首次真跑 25 条、临时全开后实测 29 条违规 → 处置：`LeftCurly` **放宽**（tokens 剔除 METHOD_DEF/CTOR_DEF，允许单行访问器）、`MissingSwitchDefault`/`NeedBraces`/`AvoidStarImport` **暂缓**（启用条件：M1 重建 service/controller 后，理由见规则集末尾清单），**全程零源码改动**；复跑 `You have 0 Checkstyle violations.` + `BUILD SUCCESS` + 退出码 0；变异验证：临时加 187 列行 → `LineLength` FAIL（精准定位第 8 行）、退出码 1，删除后恢复绿且无残留；`clean test` 仍 58 测试全绿。另修两处配置错误：pom 的 `<encoding>` → `<inputEncoding>`（mojo 字段名）、`LeftCurly` tokens 去掉 10.12.4 尚不存在的 `SWITCH_RULE`。详见 `state/completed/v2-7-checkstyle.md`）
