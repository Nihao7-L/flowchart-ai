package io.github.nihaoljx.flowchart.graph;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * relation（连线关系语义）的单一真相源。
 *
 * <p>v2-40 之前合法值散落在 GraphValidator.RELATIONS、ScenePlanner.RELATION_STYLE、
 * schema.arrow.relation 三处各抄一份，扩值时须四处同步（第四处是提示词），
 * 极易漏改导致"有的环节认、有的环节不认"却静默不一致。本类把「合法值清单」与
 * 「关系 → 视觉样式映射」合并为<b>代码唯一来源</b>：GraphValidator / ScenePlanner
 * 都引用它；schema 与提示词仍是手抄（JSON / 自然语言无法由 Java 生成），
 * 但注释统一指向本类。
 *
 * <p><b>扩值规则（改一处不够）：</b>本类 {@link #STYLE} 加一项 →
 * schema.arrow.relation 加枚举 → 提示词补说明。三者必须一致。
 */
public final class RelationCatalog {

    /** 关系 → 视觉样式（strokeStyle / 起点端点 / 终点端点） */
    public record RelationStyle(
            String strokeStyle,
            String startArrowhead,
            String endArrowhead) {}

    /** 合法 relation 值（与 schema.arrow.relation 枚举、提示词三方一致） */
    public static final Set<String> ALL_RELATIONS = Set.of(
            "flow", "call", "data", "async", "dependency",
            "one-to-one", "one-to-many", "many-to-many", "inherit", "block");

    /** relation → 视觉样式映射（唯一的样式来源） */
    public static final Map<String, RelationStyle> STYLE;

    static {
        Map<String, RelationStyle> m = new LinkedHashMap<>();
        m.put("flow", new RelationStyle("solid", null, "triangle"));
        m.put("call", new RelationStyle("solid", null, "triangle"));
        m.put("data", new RelationStyle("dotted", null, null));
        m.put("async", new RelationStyle("dashed", null, "triangle_outline"));
        m.put("dependency", new RelationStyle("dashed", null, null));
        m.put("one-to-one",
                new RelationStyle(null, "crowfoot_one", "crowfoot_one"));
        m.put("one-to-many",
                new RelationStyle(null, "crowfoot_one", "crowfoot_many"));
        m.put("many-to-many",
                new RelationStyle(null, "crowfoot_many", "crowfoot_many"));
        m.put("inherit",
                new RelationStyle("solid", null, "triangle_outline"));
        m.put("block", new RelationStyle(null, "bar", null));
        STYLE = Map.copyOf(m);
    }

    private RelationCatalog() {
    }
}
