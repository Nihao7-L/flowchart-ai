# v2-38 · 布局引擎可替换（自研 ↔ ELK）+ 量化基线

> 状态：**已交付**（引擎实现 + 量化基线 + 默认引擎拍板均已完成；剩余门禁复核与文档同步）
> 关联架构：`docs/architecture.md` ADR-2（几何唯一真相源在后端）、ADR-4（坐标由布局确定性算出）
> 关联前置：v2-36（自研分层布局，已归档 `state/completed/underway/v2-36-layout.md`）
> 触发来源：`plans/masterPlan/roadmap.md` 第六节"备份计划"（2026-09-18 用户改判后开工）

## 1. 要解决的问题

2026-09-18 的真实出图（43 元素登录流程图，用户实拍）暴露两层问题：

1. **节点摆放质量**：自研分层布局不做多轮交叉最小化、不做边路由 —— 菱形分支处线交叉、长斜线穿图；横向铺开时"主干横排 + 分支竖堆"混杂，读不出层次。
2. **标签落位**：同一层间通道里多条边的标签落在同一位置，红字成堆叠（实测 7 对）。

同时暴露一个**前提性约束**：换任何引擎都改不了"线怎么画" —— 本项目箭头是
Excalidraw 绑定箭头（只存 `{start:{id},end:{id}}`，由两端现算直线），
ELK 的正交路由与虚拟节点绕行在这套渲染契约下拿不到；要用就得放弃
"拖节点箭头自动跟随"。**收益上限是"节点怎么摆"。**

## 2. 方案（三件事，按序）

1. **抽 `LayoutEngine` 接口** —— `GenerationService` 只依赖接口，两个实现按配置切换
   （`chartflow.layout.engine=elk|legacy`，`matchIfMissing` 默认 **`elk`**）。
   ⚠️ 默认值必须落在**代码侧**：`application.yml` 被 `.gitignore` 忽略，
   只写那里换台机器（或 CI 干净检出）就退回无默认状态。
2. **引入 Java 版 ELK**（`org.eclipse.elk.core` + `org.eclipse.elk.alg.layered:0.12.0`，EPL-2.0）。
   **不引 elkjs / dagre**：JS 库只能在前端或 Node 侧算，会破坏"几何唯一真相源在后端"。
3. **建量化基线** —— 不靠肉眼和口味判断"哪个布局更好"。

## 3. 交付物

| 文件 | 说明 |
|---|---|
| `graph/LayoutEngine.java` | 引擎接口，两种模式的硬约束写进契约 |
| `graph/LayoutSpec.java` | 引擎无关的规格层：节点尺寸、网格、层间距、长宽比判据 |
| `graph/ElkLayoutEngine.java` | ELK 实现（两方向择优、编辑模式避让、失败原样返回） |
| `graph/SceneLayout.java` | 自研实现（改为 `implements LayoutEngine`，行为不变） |
| `service/GenerationService.java` | 依赖从具体类收窄为接口 |
| `ElkLayoutEngineTest`（5 例） | 只守契约：零重叠 / 冻结不动 / 失败降级 / 同输入同输出 |
| `workbuddyFlow/tools/layout-bench/` | fixture（`fixtures/login-flow.json`）+ `LayoutBench` 指标 + `render_compare.py` 渲染 |

## 4. 验收口径与实测结果

指标口径与实测表格见 `plans/masterPlan/roadmap.md` 第六节；三方渲染对比见
`workbuddyFlow/tools/layout-bench/compare.html`。

一句话：**标签落位与整体规整度 ELK 明显更好**（标签压节点 8→2、标签互叠 7→0），
自研在"长宽比"上更接近理想区间但视觉更乱；ELK 竖排最长（680x4820），
ELK 折行最"方"（1860x1760）但跨列长线变多。

## 5. 默认引擎（2026-09-18 用户拍板）

**取 `elk` + 竖排**（`WrappingStrategy.OFF`）。三个候选的取舍：

- `legacy`（自研）：长宽比 0.65 更"方"，但标签遮挡 8/7，视觉最乱 —— 降为备用；
- **`elk`（竖排）：观感最规整、标签遮挡 2/1，但 680x4820 极细长 —— 采纳**；
- `elk`（折行）：画布 1860x1760、标签遮挡 2/0，但跨列长线横穿整图，观感更差 —— 弃。

理由：这三项里"能不能读懂"权重高于"画布方不方"。已知代价是长链图会被
排成一条长竖条（深层链可达数千 px 高），前端需靠 fit 缩放看全 —— 这条
记在下面第 6 节，触发条件是"竖排导致 fit 缩放到读不清文字"。

## 6. 已知缺口

- 跨层长边仍是直线斜穿（受绑定箭头限制，见第 1 节）。
- 折行能改善画布比例，但跨列长线增多 —— 两者是同一枚硬币的两面。
- 标签落位仍由"边中点"决定，未做通道分散（ELK 只优化节点几何）。

## 7. 证据

- `workbuddyFlow/tools/layout-bench/compare.html`（三方渲染对比，可直接打开）
- `workbuddyFlow/tools/layout-bench/out/*.json`（三个方案的坐标快照）
- 真实出图与同源症状记录：`state/activeLog/2026-09-18.md`
