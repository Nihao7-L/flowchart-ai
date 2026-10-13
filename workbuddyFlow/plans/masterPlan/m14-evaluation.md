# 模块计划 · 评估与回归门禁（并行轨 M14 / javaguide L5）

> 状态：规划中
> 关联架构：`docs/复杂提示词测试集.md`（题源）、`tools/e2e/e2e_prompt_battery.py`（执行器）、`run-verify.ps1`（门禁）、`docs/workflow.md` 第六节（已知缺口）
> 所属阶段：**并行轨（不占 S 顺序槽位）**；建议 **S2 起激活**——上下文(M10) / 循环(M4) / 提示词(M11) 任何改动都需回归；可随时插入
> 覆盖任务：v2-53 ~ v2-54
> 方向来源：用户 2026-09-20 在交叉阅读 javaguide《llm-evaluation》后拍板「**Q1-A = 轻量 Golden Set + 回归门禁**」
> **决策落地（Q1-A，2026-09-20 用户拍板）**：轻量、无新框架。Golden Set = 取既有 9 题电池 + 边界 / 失败回填 至 **10~20 条**；**capability 子集（3~5 题代表图型）作回归门禁**（硬指标 NaN / 图形重叠 / frame 逃逸 + 成本阈值，PASS/FAIL）；全量集 nightly / 按需；`--seed` 复现 + 历史对比。不烧全量 token 每次提交。

## 元信息
- 模块编号：M14（评估轨：**不对应业务功能、不进 `coreFun.md` 功能清单**，与 M12 / M13 同属"底座"类）
- 状态：规划中
- 创建：2026-09-20 + AI

## 目标
补上 javaguide harness **L5 评估与观测**里的"评估"半边（观测归 M7）：建立一套**轻量、可复现、可对比**的出图质量评估，让"改了上下文 / 循环 / 提示词之后，质量有没有退化"从"人读报表"升级为"门禁自动判 PASS/FAIL"。
**不引入 LLM-as-Judge 等新框架**（规则式 is/否 硬指标即可，避开位置/冗长/自我增强/推理四种偏差）；复用现有电池执行器与题源。

## 边界
- **允许新增**：`run-eval.ps1`（薄封装 `e2e_prompt_battery.py --golden`，跑 capability 子集并判 PASS/FAIL）；`docs/eval-golden.md`（Golden Set 清单 + 每题硬指标阈值）；`e2e_prompt_battery.py` 加 `--golden` / `--seed` / `--baseline` 参数
- **允许修改**：`run-verify.ps1` 加可选 `-Eval` 开关（仅跑 capability 子集，不跑全量，避免每次提交烧真实 token）
- **禁止改动**：业务功能；题源 `复杂提示词测试集.md` 仅"引用"，不搬家
- **不做**：LLM-as-Judge 评分、在线灰度评测闭环（javaguide 三阶段闭环的线上段，单用户本地暂不需要）；多模型 A/B 平台

## 分层边界
落在**测试 / CI 侧**（不在 `service/` 任何业务包）。Golden Set 是"数据集"，`run-eval.ps1` 是"门禁执行器"，二者都不进运行时。
**与 M13 的衔接**：评估记录里的 `promptVersion`（M10 提供）/ `modelId` / gitCommit 作为 javaguide `EvalRecord` 的对应字段，使"哪版 prompt + 哪次提交 + 哪个模型"可回溯（javaguide：离线 → Trace replay → 在线灰度 三阶段，本期落地"离线 + 按版本对照"两段）。

## 前置
- 现有电池执行器 `tools/e2e/e2e_prompt_battery.py` 可用（已建 2026-09-18）
- 题源 `docs/复杂提示词测试集.md` 9 题（已建）
- M10 的 `promptVersion`（v2-43）落地后评估可按版本分组（不阻塞本期启动，缺则先按 gitCommit 分组）

## 步骤
1. **v2-53 Golden Set 建集 + 硬指标阈值**：
   - 取既有 9 题电池为基底，按 javaguide「生产日志边界采样 + 失败回填 + 分层而非总量」原则，补 **边界 / 已知失败** 题至 **10~20 条**（如：含时序图/甘特图这类"无图形原语"的退化题、自环箭头结构性非法题、超 40 元素题），形成 Golden Set。
   - 每条标 **capability 子集标记**（3~5 题覆盖主要图型：架构 / 流程 / 时序 / 状态机 / 部署，作为回归门禁必跑集）与 **全量集标记**。
   - 每题定**硬指标阈值**（规则式 is/否）：NaN=0、图形重叠=0、`shapesEscapingOwnFrame`=0（区分合法无框）、文字互叠 ≤ 阈值（复用 M8/M9 口径）；成本阈值（单题 token / 轮数上限，防回归悄悄涨价）。
   - 落 `docs/eval-golden.md`（清单 + 阈值 + capability/full 标记）。
2. **v2-54 回归门禁 + 复现 + 对比**：
   - `e2e_prompt_battery.py` 加 `--golden`（只跑 Golden Set）、`--seed`（复现）、`--baseline <json>`（与历史结果 diff，绝对 + 相对阈值，javaguide CI 回归）。
   - 新增 `run-eval.ps1`：薄封装，跑 **capability 子集** → 断言硬指标 → 输出 PASS/FAIL（退出码与 `run-verify.ps1` 一致）；全量集由 `--all` 或 nightly 跑，不进提交门禁。
   - `run-verify.ps1` 加 `-Eval` 开关：**仅**跑 capability 子集（秒级~分钟级、烧少量 token），让"提交前顺手回归"可行；默认不跑（保离线）。
   - 评估结果带 `promptVersion` + gitCommit，落 `battery-out/eval-<seed>.json`（已 gitignore，是证据非源码）。

## 验收口径
1. `run-eval.ps1` 跑 capability 子集 → 硬指标全过则 PASS（退出码 0），任一不过则 FAIL（退出码 1），与 `run-verify.ps1` 语义一致。
2. Golden Set 文档 `docs/eval-golden.md` 含 10~20 题 + 每题阈值 + capability/full 标记。
3. `--seed` 同种子复跑结果可复现（同 promptVersion + 同模型下指标一致）。
4. `--baseline` 对比能标出"相对阈值退化"（如文字互叠从 2→10、单题 token 涨 >30%），不靠人眼对报表。
5. `run-verify.ps1 -Eval` 不启 Spring 容器、只跑 capability 子集（轻量），默认 `run-verify` 仍离线不变。

## 覆盖测试数据
| 场景 | 输入 | 期望 |
|---|---|---|
| 回归门禁 | `run-eval.ps1` | capability 子集硬指标全过 → PASS；任意不过 → FAIL，指明哪题哪指标 |
| 复现 | 同 `--seed` 两次 | 指标一致（同 promptVersion + 模型） |
| 退化发现 | 故意改坏 prompt 后跑 | `--baseline` 标出相对退化（重叠/互叠/token 涨） |
| 全量 | `run-eval.ps1 --all` | 跑 10~20 题，出报表（nightly / 按需，不进提交门禁） |
| 版本分组 | M10 promptVersion 递增后 | 评估记录按版本分组，可对照前后两版质量 |

## 完成后
移入 `state/completed/masterPlan/`；`state/activeLog/<YYYY-MM-DD>.md` 追加一行带证据；`docs/workflow.md` 第六节"下一步（未做）"标记已落地（指向本模块）。

## 调研输入（2026-10-13，三源调研对账）

> 来源：`.workbuddy/reports/2026-10-13-*`。不改变已拍板的 Q1-A 决策（轻量、无新框架），只补设计输入。

1. **建集流程按 Hamel《Evals》**：评测的第一性原理是**错误分析先行**——先人工读 30~50 条真实生成轨迹（含失败），归纳**失败分类表**（如：绑定丢失 / frame 逃逸 / 文字溢出 / group 错贴 / 交白纸），再据此定二元硬指标与补题。不要先想指标再找数据。
2. **promptfoo 为候选方案（待拍板，与本模块"无新框架"决策有冲突点）**：现有守卫测试锁的是提示词**字符串**（如 `v580EssentialRulesKept` 检查 md 里有没有那句话），锁不了**模型行为**。promptfoo（23k+，OpenAI 收购）做 prompt × 厂商 × 测试用例的断言矩阵（`is-json`/`javascript`/`llm-rubric`），提示词每次升版让全部候选过一遍出成绩单，行为退化当场可见。若引入，与 M11（提示词工程）联动最紧。
3. **数据源衔接**：M4 的 run journal JSONL（若落地）每轮已带 `promptHash/verdict/tokens/durationMs`——`--baseline` 对比可直接消费它，不必重复采集；`e2e_prompt_battery.py` 的落盘格式向该字段集靠拢。
4. **指标口径补充**：除图形硬指标外，候选增补三条**过程曲线**（解析通过率 / 修正轮收敛率=几轮达标 / 每张图成本）——它们对"改提示词/改循环"比单帧硬指标更敏感。

## 状态跟踪
- [ ] v2-53 Golden Set 建集（10~20 题，含边界/失败回填）+ 硬指标阈值 + capability/full 标记（`docs/eval-golden.md`）
- [ ] v2-54 回归门禁（`run-eval.ps1` + `--golden/--seed/--baseline`）+ `run-verify.ps1 -Eval` 开关
