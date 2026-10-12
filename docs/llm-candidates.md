# LLM 候选模型路由与顺序

> 维护说明：本文件记录 `application.yml` 里 `llm.candidates` 的选型决策。
> 注意 `application.yml` 含明文 API key，被 `.gitignore` 屏蔽、**不入库**；
> 实际候选表以本地 `application.yml` 为准，本文件只记决策与理由，便于回看。

## 2026-10-12 用户最终拍板：候选顺序

路由规则：`FailoverLlmProvider` 按 `tier` 升序排序、`pick()` 取 `order.get(0)`，
即 **tier 越小越先试**。

| 顺序 | 候选名 | tier | 备注 |
|---|---|---|---|
| 1（主力） | glm-5.3-flash | 1 | 累计 5/5 成功（100%）；慢（314~570s）、贵（≈17× deepseek） |
| 2（备用） | deepseek-v4.1-flash | 2 | 快 3 倍、便宜 17 倍；但 ~40% 概率"白纸"（思维链失控 + out 顶格 65536） |
| 3（备用） | grok-4.7 | 3 | 稳但贵（≈170× deepseek） |
| 4（末位） | gpt-6.1-sol | 4 | ⚠️ 当前 key 欠费（HTTP 402），仍按用户要求排末位 |

四家同属 ailanglang 中转站，但 key 不同：国内 key 只通 glm / deepseek；
grok、gpt 各用独立 key（否则 503）。`max-tokens` 统一 65536，防长提示词被截断白烧。

## 历史

- 2026-10-11 15:55：曾对调为 deepseek 主力 / glm 兜底（基于当时单轮实测）。
- 2026-10-12：用户撤回，改回 **glm 主力**，并新增 grok / gpt-6.1-sol 两个候选，
  顺序定为 glm → deepseek → grok → gpt-6.1-sol。

## 已知稳定性数据（10-11 ~ 10-12 实测）

| 模型 | 成功率 | 单次成本相对 |
|---|---|---|
| glm-5.3-flash | 5/5 = 100% | ~17× |
| grok-4.7 | 5/5 = 100% | ~170× |
| deepseek-v4.1-flash | 3/5 = 60% | 1×（最便宜） |
| gpt-6.1-sol | 0/3（欠费 402） | — |

⚠️ 中转站页面显示的"延迟"数字与实际生成耗时无关，不能用来排序。
