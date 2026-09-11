# engineering.md — 工程约束：怎么编译、怎么验证、红线在哪

> 只回答一个问题：**怎么把这台机器上的代码跑起来、验证它是对的、哪些操作被禁。**
> 读它的时机：动手前读红线段，交付前读验证段。架构类不变式在 `architecture.md`，不在这里。

## 一、本机环境（实测值，别猜）

| 项 | 值 |
|---|---|
| JDK | `C:\Users\22719\.jdks\ms-17.0.17` |
| Maven | `D:\maven-home\apache-maven-3.9.5-bin\apache-maven-3.9.5` |
| Redis | `F:\Program Files\Redis-x64-3.0.504`（需手动启动，未注册为服务） |
| 项目根 | `F:\ProgramData\IDEA\flowchart` |
| 前端目录 | `flowchart-frontend/`（阶段 0 v2-6 重建） |

## 二、编译后端（唯一稳妥方式）

Git Bash 里的 `mvn` 是坏的（classworlds 找不到）；PowerShell 走管道调 `mvn.cmd` 会报"无法在管道中间运行文档"。
**用 java 直启 Maven**（PowerShell 执行，不要走管道）：

```powershell
& "C:\Users\22719\.jdks\ms-17.0.17\bin\java.exe" -classpath "D:\maven-home\apache-maven-3.9.5-bin\apache-maven-3.9.5\boot\plexus-classworlds-2.7.0.jar" "-Dclassworlds.conf=D:\maven-home\apache-maven-3.9.5-bin\apache-maven-3.9.5\bin\m2.conf" "-Dmaven.home=D:\maven-home\apache-maven-3.9.5-bin\apache-maven-3.9.5" "-Dmaven.multiModuleProjectDirectory=F:\ProgramData\IDEA\flowchart" org.codehaus.plexus.classworlds.launcher.Launcher -B -f "F:\ProgramData\IDEA\flowchart\pom.xml" compile
```

退出码 0 = 编译通过。换 `test` 即为跑单测。

## 三、前端验证（`flowchart-frontend/` 存在时）

```
node_modules/.bin/tsc --noEmit
node_modules/.bin/vite build --emptyOutDir false
```

`--emptyOutDir false` 是必需的，理由见红线 4。

## 四、一键验证

```
powershell -File run-verify.ps1
```

（阶段 0 的 v2-3 落地后可用；成功时最后一行输出 `VERIFY PASS`，退出码 0。）

## 五、红线（每一条都是踩过的坑）

1. **禁止批量递归删除**：`rm -rf` / `del /s` / `shutil.rmtree` / `git clean -fd` 作用在任何目录前都必须先告知用户（哪怕目标是自建缓存目录，曾因 `rm -rf node_modules/.vite` 越界被叫停）
2. **禁止改动环境变量与系统设置**：包括增删用户级 env var（曾因自作主张删 `LLM_API_KEY` 越界）
3. **`application.yml` 永不入库**：含明文 MiMo key；用户明确接受明文本地保留，不要再提议改成环境变量读取
4. **`vite build` 必须带 `--emptyOutDir false`**：否则触发 safe-delete 批量删除拦截
5. **新增 record 字段后，必须全库搜索该 record 的所有构造调用点**（`endPos` 未同步导致编译失败）
6. **没编译验证的代码不得交付**（RagService 未编译即交付的教训）

## 六、错误 → 约束（本节如何生长）

任何人或 Agent 犯错并被验证抓到，就在第五节追加一行，格式：

```
- **禁止 / 必须 X**：原因（哪次事故）
```

这条机制比文档本身重要：让约束来自真实事故，而不是想象。
