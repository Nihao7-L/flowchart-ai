package io.github.nihaoljx.flowchart.model;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 返回的思维导图结构化数据
 *
 * 这个类的核心是【递归结构】：
 * 一个节点 = label（显示文字）+ children（子节点列表）
 * 而 children 的元素类型就是 MindmapData 自己！
 *
 * 对应 JSON 格式（嵌套的，和流程图的平铺结构完全不同）：
 * {
 *   "label": "电商系统",
 *   "children": [
 *     { "label": "前端", "children": [
 *       { "label": "Web 商城", "children": [] }
 *     ] }
 *   ]
 * }
 *
 * 为什么不用 nodes + edges 平铺结构？
 * - 树用嵌套天然表达：父子关系"长在"对象里，不需要靠边的 from/to 连接
 * - LLM 生成嵌套 JSON 几乎不会错，生成一堆边反而容易出错
 * - 转换代码也更简单：递归遍历，不需要建 nodeMap/edgeMap 查表
 */
public class MindmapData {

    /** 节点显示文字（根节点的 label 就是整张图的标题） */
    private String label;

    /**
     * 子节点列表 ← 递归核心！
     *
     * 初始化成 new ArrayList<>() 的原因：
     * 如果 LLM 漏返回了 children 字段，Jackson 不会给 List 字段自动初始化，
     * 默认就是 null。后面遍历时 child.getChildren() 会 NPE。
     * 先初始化好，就算 LLM 漏了，也只是一个空列表。
     */
    private List<MindmapData> children = new ArrayList<>();

    // ===== Getter / Setter：Jackson 必需 =====
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public List<MindmapData> getChildren() { return children; }
    public void setChildren(List<MindmapData> children) {
        // 防御：就算 JSON 里 children 为 null，也兜底成空列表
        this.children = children != null ? children : new ArrayList<>();
    }
}
