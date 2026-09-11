# activeLog.md — 执行流水（外置记忆，只追加）

> 谁在什么时候做了什么、证据是什么。**每条必须有证据**（命令 + 结果），否则等于没记。
> 用途：会话恢复时只读本文件尾部几行即可续上上下文；人复盘时只看这里。

## 格式

```
- MM-DD HH:mm vX-N 做了什么 → 证据（命令 / 输出 / 退出码）
```

## 记录

- 09-11 11:01 阶段0 知识层落盘：AGENTS.md + docs/agents 三件套 + docs/exec-plans 骨架 → 文件清单核对 9 项齐、`git status` 无异常（commit `docs: 建立知识层…`）
- 09-11 11:01 产出第一份 exec-plan `active/v2-3-run-verify.md` → 状态：待审（未动任何业务代码）
- 09-11 11:20 知识层按"每份只回答一类问题"重构：AGENTS.md 瘦身为纯索引（25 行）、docs/agents 拆成五份（新增 architecture / features）、workflow 补状态机约定与知识归口表 → 文件树核对 11 项、行数核对通过
