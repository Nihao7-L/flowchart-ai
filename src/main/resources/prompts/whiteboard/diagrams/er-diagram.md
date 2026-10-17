---
diagramType: er-diagram
version: 1.0.0
title: ER 图 / 实体关系图
keywords: ER图, ER 图, 实体关系图, 实体关系, 数据库表关系, E-R, entity relationship
layout: generic
---

ER 图 = 实体（表）+ 实体之间的关系（外键 / 基数）。

- **必须声明 `diagramType:"er-diagram"`**。
- 实体用矩形，名字写表名（用户 / 订单 / 商品）；**属性不要每个都建一个节点**，写进同一个节点的 label 里用换行分隔（订单：order_id / user_id / amount），否则一张表会炸成十几个节点。
- 连线上必须标基数：`1:1` / `1:N` / `N:M`，写在箭头标签里（「1」「N」「下单 N」）。
- 主键写 PK、外键写 FK，标在属性行后面（order_id PK、user_id FK）。
- `N:M` 的中间表要单独建一个实体节点（如「订单商品」），不要直接在两个实体间拉一根 N:M 线了事。
- 关系用 `relation:"one-to-many"` / `"one-to-one"` / `"many-to-many"`，让线型自动区分。
- 实体之间不要画成流程：ER 图没有方向感，不要给它排成从上到下的步骤链。
