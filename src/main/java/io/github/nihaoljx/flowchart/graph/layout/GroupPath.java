package io.github.nihaoljx.flowchart.graph.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 模块路径（契约里的 `group`）：**从内到外**的模块名序列。
 *
 * <p>契约（`schemas/scene.schema.json` 的 `definitions.group`）是
 * `oneOf[string, string[]]`：单层模块写字符串，模块里还有子模块时写数组
 * —— `["处理中","订单"]` 读作「处理中，它属于订单」。数组读法在
 * `ScenePlanner` 里一直是对的（它能建出嵌套模块树），但 spec 这条路
 * 曾经用 `asText()` 读 group：**数组被 Jackson 的 `asText()` 静默变成
 * 空串**，层级在进布局之前就丢了，于是「处理中」和它的子状态被排成两个
 * 并排模块、中间连出上千像素的长线。这个类把「读 / 写 / 归一」收在一处，
 * 别再让任何一方用 `asText()` 读它。
 *
 * <p>宽容处理与 `ScenePlanner.groupPath` 一致（去空白、丢弃空串与非字符串、
 * 截断超长、同名去重），因为写错的成本应该是「少一次分区」而不是「生成失败」。
 */
public final class GroupPath {

    /** 模块名单个名字的长度上限（与 ScenePlanner.MAX_GROUP_NAME 同口径） */
    public static final int MAX_NAME = 24;

    private GroupPath() {
    }

    /**
     * 读一个节点/元素的 `group`，返回从内到外的模块名（可能为空表）。
     *
     * <p>数字 / 对象 / 布尔一律当作「没分组」，不抛异常。
     *
     * @param node 承载 `group` 字段的节点，可为 null
     * @return 归一后的路径（不可变，空表表示无分组）
     */
    public static List<String> parse(JsonNode node) {
        List<String> path = new ArrayList<>();
        if (node == null) {
            return List.of();
        }
        JsonNode group = node.get("group");
        if (group == null || group.isNull()) {
            return List.of();
        }
        if (group.isTextual()) {
            add(path, group.asText());
        } else if (group.isArray()) {
            for (JsonNode item : group) {
                if (item.isTextual()) {
                    add(path, item.asText());
                }
            }
        }
        return List.copyOf(path);
    }

    /** 单名路径（模型只写了扁平字符串时的形态） */
    public static List<String> of(String name) {
        List<String> path = new ArrayList<>();
        add(path, name);
        return List.copyOf(path);
    }

    /**
     * 把路径写进元素：单层写字符串，多层写数组。
     *
     * <p>单层仍写字符串是有意的 —— 契约允许两种形态，而既有单测与
     * 前端都在按字符串读，没必要为了统一而改动它们。
     *
     * @param target 目标元素
     * @param path   从内到外的模块名；空表时不写任何字段
     */
    public static void write(ObjectNode target, List<String> path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        if (path.size() == 1) {
            target.put("group", path.get(0));
            return;
        }
        ArrayNode array = target.putArray("group");
        path.forEach(array::add);
    }

    /** 最内层模块名（分区配色 / 泳道归属都按最内层算），无分组时返回 null */
    public static String innermost(List<String> path) {
        return path == null || path.isEmpty() ? null : path.get(0);
    }

    /**
     * 路径的稳定键：`a/b`。
     *
     * <p>用它做分组配色的键，于是**同一个容器连同它的直接成员拿到同一个
     * 颜色**，而更深的嵌套容器另起一色 —— 框套框的层次感来自这里。
     */
    public static String key(List<String> path) {
        return path == null ? "" : String.join("/", path);
    }

    /**
     * 人类可读写法（回灌给模型看当前场景时用）。
     *
     * @param node 承载 `group` 的节点
     * @return `处理中/订单`；无分组时返回空串
     */
    public static String display(JsonNode node) {
        return key(parse(node));
    }

    /**
     * 容器键：路径的第 {@code from} 段到最外层。
     *
     * <p>容器在树里的身份必须带上它的外层路径（`a/c` 与 `b/c` 是两个
     * 不同的容器，虽然都叫 c），所以取**后缀**而不是前缀。
     *
     * @param path 从内到外的完整路径
     * @param from 该容器在路径里的下标（0 = 最内层）
     * @return 形如 `处理中/订单` 的容器键
     */
    public static String containerKey(List<String> path, int from) {
        if (path == null || from < 0 || from >= path.size()) {
            return "";
        }
        return String.join("/", path.subList(from, path.size()));
    }

    private static void add(List<String> path, String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return;
        }
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME);
        }
        if (!path.contains(name)) {
            path.add(name);
        }
    }
}
