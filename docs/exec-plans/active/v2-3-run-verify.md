# v2-3 run-verify.ps1

> 状态：待审
> 计划先落盘是 workflow 第 ② 拍，这一拍的作用是让你审 Plan 而不是审 diff。

## 目标

一条命令跑完后端编译 + 单测 + 前端检查，**退出码 0 = 全绿，非 0 = 有失败**；输出最后一行固定为 `VERIFY PASS` 或 `VERIFY FAIL`。

## 边界

- 允许新增：`run-verify.ps1`（仓库根）
- 允许修改：`.gitignore`（忽略 `run-verify.log`）、`docs/agents/engineering.md`（第四节补实际用法）
- **不允许**改任何业务代码（`src/main/java/**` 本次一行不动）

## 分层边界

只落在"工具/脚本层"与"知识层"：不触碰 `controller/ service/ llm/ graph/` 任何一层，不改变任何调用关系（见 `architecture.md` 第一节）。

## 前置

无（阶段 0 第一个可执行任务；`flowchart-frontend/` 尚不存在，脚本需能优雅跳过前端段）

## 步骤

1. 后端编译：调用 `engineering.md` 第二节那条 java 直启 Maven 的命令，失败即中断
2. 后端单测：同命令换 `test`（当前无测试文件属正常，需能通过）
3. 前端段（仅当 `flowchart-frontend/package.json` 存在时执行）：
   - `node_modules/.bin/tsc --noEmit`
   - `node_modules/.bin/vite build --emptyOutDir false`
4. 汇总：打印每一步的耗时与结果，最后一行输出 `VERIFY PASS` / `VERIFY FAIL`
5. 全过程写入 `run-verify.log`（不入库）

## 验收口径

1. 正常状态下运行 → 退出码 0，最后一行 `VERIFY PASS`
2. **故意在某个 Java 文件里写一个不存在的符号** → 运行后退出码 1、最后一行 `VERIFY FAIL`、日志里能看到编译错误原文
3. 前端目录不存在时 → 打印"跳过前端检查"，其余步骤正常执行，不报错
4. 人工核对：脚本里不含任何 `rm -rf` / `del /s` / `Remove-Item -Recurse` 类删除动作（对齐 engineering.md 红线 1）

## 覆盖测试数据

| 场景 | 输入 | 期望 |
|---|---|---|
| 正常 | 当前代码 | exit 0 / VERIFY PASS |
| 编译错 | 注入一个未定义符号 | exit 1 / VERIFY FAIL |
| 无前端 | 删除或重命名 `flowchart-frontend/`（临时） | 跳过前端段，仍 PASS |

## 完成后

移入 `completed/`、勾掉 `docs/架构规划-v2.md` 的 v2-3、`activeLog.md` 追加一行带证据记录。
