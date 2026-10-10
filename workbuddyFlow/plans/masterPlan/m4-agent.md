# M4 已重定位

> 本文件（m4-agent.md）已被 **m4-loop-engineering.md** 取代，仅作指针。

M4 的最终口径（2026-09-20 **G1 已拍板 = 真 ReAct**）：落地「LLM 持工具、Thought→Action(`tool_calls`)→Observation 循环」的真 Agent 范式，见 `m4-loop-engineering.md`（覆盖 v2-55 ~ v2-56，阶段 S4）。

口径演变（仅留痕，不作为执行口径）：
- 早期草稿：真 ReAct（LLM 调工具），任务号 v2-18~20。
- 2026-09-20 中段：一度重定位为「状态化 Graph Loop（LLM 只出 scene JSON，循环由后端编排）」，并把 v2-18~20 标记作废。
- 2026-09-20 末（G1 拍板）：**还原为真 ReAct**——"状态化 Graph Loop" 草稿作废，v2-18~20 随之作废，执行口径以 `m4-loop-engineering.md`（v2-55~56）为准。
