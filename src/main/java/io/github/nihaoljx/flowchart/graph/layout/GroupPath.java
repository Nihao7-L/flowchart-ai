package io.github.nihaoljx.flowchart.graph.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * 容器键：从第 {@code from} 段到最外层这条**祖先链**（容器自己 + 它的外层祖先）。
     *
     * <p>为什么键里带外层祖先、却**不带内层祖先**：
     * <ul>
     *   <li>`["已完成","订单"]` 与 `["处理中","订单"]` 的 `订单` 是同一个人 ——
     *       键必须相等（都是 `订单`），否则共同外层会被拆成两个框。这正是
     *       {@code GroupFrameDedupTest.synthesisUnchangedWhenNothingDrawn}
     *       钉住的语义。</li>
     *   <li>`["c","a"]` 与 `["c","b"]` 的 `c` 是两个不同容器 —— 键必须不同
     *       （`c/a` 与 `c/b`），否则 processing/payment 与
     *       processing/shipping 两段并行区会被合成一个框、横跨整张画布。</li>
     * </ul>
     * 两条合起来就是「容器身份 = 自己 + 外层祖先」，等价于取后缀。
     *
     * <p>曾试过改成前缀（`subList(0, from + 1)`）想修 2026-10-19 data-pipeline
     * 里「大数据处理系统」被画三遍的问题，**方向是错的**：那里的三套框不是
     * 容器身份算错，而是模型把 `数据采集层` 既当节点 label 又当分组名（见
     * {@link #stripNodeLikeLayers}），前缀改动反而砸掉了上面两条既有语义，
     * 令 `GroupFrameDedupTest` 两例失败。修正留在
     * {@link #stripNodeLikeLayers}，本方法是几何工具，不该为输入语义兜底。
     *
     * @param path 从内到外的完整路径
     * @param from 该容器在路径里的下标（0 = 最内层）
     * @return 形如 `订单/处理中` 的容器键
     */
    public static String containerKey(List<String> path, int from) {
        if (path == null || from < 0 || from >= path.size()) {
            return "";
        }
        return String.join("/", path.subList(from, path.size()));
    }

    /**
     * 去掉「其实是某个**别的**节点自己」的分组层。
     *
     * <p>2026-10-19 data-pipeline 实跑的真实输入：
     * <pre>
     *   data_collection  label=数据采集层  group=["架构","大数据处理系统"]
     *   flume            label=Flume      group=["架构","大数据处理系统","数据采集层"]
     * </pre>
     * 模型把 `数据采集层` **同时**写成了节点 label 和一层 group。后果不是
     * 排版难看，而是结构错乱：`data_collection` 与 `flume` 本该是同一个框里的
     * 兄弟（采集层的代表 + 采集层的组件），现在一个直接挂在大数据处理系统下、
     * 一个挂在额外的「数据采集层」框里，于是「大数据处理系统」「架构」被拆成
     * 多套同名框互相错位、线条横穿整张画布。
     *
     * <p>判据是「这个同名节点**自己就在**这一层里吗」，不能只看名字重合 ——
     * 组合态的标准写法天生就重名：
     * <pre>
     *   order       label=订单    group=["订单"]
     *   processing  label=处理中  group=["处理中","订单"]
     *   paying      label=支付中  group=["支付中","处理中","订单"]
     * </pre>
     * 这里 `订单` 层装着 `order` 自己，`处理中` 层装着 `processing` 自己 ——
     * 节点就是这一层的代表，该出框。dataflow 的 `data_collection` 恰好相反：
     * 它的 group 是 `["架构","大数据处理系统"]`，**不在** `数据采集层` 层里，
     * 那一层装的是 flume 等组件 ⇒ 那个同名层是模型多写的，剔掉。
     *
     * @param path       从内到外的分组路径
     * @param selfPath   当前元素自己的完整路径（用于判断「同名层里有我」）
     * @param labelOwners 场景里 label → 那个节点自己的 group 路径
     * @return 剔除后的新路径（不改入参）
     */
    public static List<String> stripNodeLikeLayers(
            List<String> path,
            List<String> selfPath,
            Map<String, List<String>> labelOwners) {
        if (path == null || path.isEmpty() || labelOwners == null) {
            return path;
        }
        List<String> kept = new ArrayList<>(path.size());
        for (String name : path) {
            List<String> owner = labelOwners.get(name);
            if (owner == null) {
                kept.add(name);
                continue;
            }
            // 同名节点自己就在这一层里（组合态代表节点）⇒ 合法，保留
            if (owner.contains(name)) {
                kept.add(name);
                continue;
            }
            // 同名节点在别处 ⇒ 这一层是模型多写的，剔掉
        }
        if (kept.isEmpty()) {
            // 整条路径都被剔光 ⇒ 原样保留，否则元素会彻底失去分组
            return path;
        }
        return List.copyOf(kept);
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
