---
diagramType: class-diagram
version: 1.0.0
title: UML 类图
keywords: 类图, UML 类, 类结构, class diagram, 面向对象设计
layout: generic
---

类图 = 类（含属性与方法）+ 类之间的静态关系（继承 / 实现 / 关联 / 依赖）。

- **必须声明 `diagramType:"class-diagram"`**。
- 每个类一个矩形节点，label 里分三段写：**类名 / 属性 / 方法**，用换行分隔（User / -name: String / +login()）。三段顺序固定，别把方法写在属性上面。
- 继承用 `relation:"inherit"`，实现接口也走它并在标签里写「implements」；关联（持有引用）用 `"one-to-many"` / `"one-to-one"`；临时使用（参数、局部变量）用 `"dependency"`。
- 关联线上标基数与角色名（「1 拥有 N」「order」），没有标基数的关联等于没说清关系。
- 箭头方向遵循 UML：继承**从子类指向父类**。
- 不要画成架构图：类图里没有「服务」「数据库」这类运行时组件，只有类型。
