package io.github.nihaoljx.flowchart.graph.layout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 布局家族注册表：diagramType → LayoutFamily 的分发与降级
 *
 * <p>「按需加载」在 JVM 里的落点不是类加载器级别的懒加载，而是
 * **按图型路由**：每个家族一个 bean/实例，启动时收进一张表，
 * SubmitSpecTool 拿到 diagramType 后查表分发。加新图型 = 加一个
 * 家族类 + 注册一行，老代码零改动（开闭原则）。
 *
 * <p>降级纪律：未注册的图型 / null / 空白 ⇒ 一律落到 generic 家族
 * （ELK 通用图），绝不 error —— 与产品红线「任何输入都落到 elements」
 * 同源。真正的非法值拦截在 SubmitSpecTool 的 spec 校验层（那里
 * 给模型机读修复单），注册表这层只保证运行时绝不炸。
 */
public final class LayoutFamilyRegistry {

    private final Map<String, LayoutFamily> families;
    private final LayoutFamily generic;

    public LayoutFamilyRegistry(List<LayoutFamily> familyList) {
        if (familyList == null || familyList.isEmpty()) {
            throw new IllegalArgumentException(
                    "至少需要一个布局家族（generic）");
        }
        Map<String, LayoutFamily> map = new LinkedHashMap<>();
        for (LayoutFamily family : familyList) {
            map.put(family.type(), family);
        }
        this.families = Map.copyOf(map);
        this.generic = map.get("generic");
        if (this.generic == null) {
            throw new IllegalArgumentException(
                    "布局家族表里必须有一个 generic 兜底家族");
        }
    }

    /** 按图型取家族；未注册 / null / 空白 ⇒ generic 兜底 */
    public LayoutFamily forType(String diagramType) {
        if (diagramType == null || diagramType.isBlank()) {
            return generic;
        }
        return families.getOrDefault(diagramType.trim(), generic);
    }

    /** 是否注册了该图型（spec 校验层用：未注册 ⇒ 给模型修复单） */
    public boolean has(String diagramType) {
        return diagramType != null
                && families.containsKey(diagramType.trim());
    }

    /** 已注册的图型名（spec schema 的 enum 来源） */
    public List<String> types() {
        return List.copyOf(families.keySet());
    }
}
