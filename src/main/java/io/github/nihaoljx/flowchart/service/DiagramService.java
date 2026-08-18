package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.FlowchartData;
import io.github.nihaoljx.flowchart.model.MindmapData;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
@Service
public class DiagramService {
    /**
     * PlantUML 源码 → SVG 字符串
     *
     * PlantUML 的 SourceStringReader 直接读字符串，
     * outputImage 渲染成图片输出到 OutputStream
     */
    public String renderToSvg(String plantUmlCode) throws IOException {
        // ① 用 PlantUML 的 SourceStringReader 读取源码
        SourceStringReader reader = new SourceStringReader(plantUmlCode);

        // ② 准备一个内存输出流（不用写文件）
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        // ③ 渲染为 SVG 格式，写入 os
        reader.outputImage(os, new FileFormatOption(FileFormat.SVG));

        // ④ 把输出流转成字符串返回
        return os.toString("UTF-8");
    }
    /**
     * 思维导图数据 → PlantUML 语法（入口方法）
     *
     * 思维导图的语法就是"缩进即层级"：
     * @startmindmap
     * * 根节点
     * ** 子节点1
     * *** 孙节点
     * ** 子节点2
     * @endmindmap
     *
     * 星号越多层级越深，和 JSON 的嵌套深度一一对应。
     * 这就是为什么 MindmapData 用递归结构——转换就是无脑递归，
     * 不需要像流程图那样建 nodeMap/edgeMap 查表。
     */
    public String buildMindMap(MindmapData data) {
        StringBuilder sb = new StringBuilder("@startmindmap\n");
        // 根节点层级是 1，从根开始递归
        appendMindMapNode(sb, data, 1);
        sb.append("@endmindmap\n");
        return sb.toString();
    }

    /**
     * 递归拼思维导图节点（和 flowMap 的 traverse 对比着看）
     *
     * 两者都是递归，区别只在"递归出口"和"每层做什么"：
     * - traverse：出口 = end 节点 / 已访问节点；每层查表找下一节点
     * - appendMindMapNode：出口 = children 为空；每层遍历子节点列表
     *
     * @param sb    输出缓冲
     * @param node  当前节点
     * @param level 当前层级（根 = 1，每深入一层 +1）
     */
    private void appendMindMapNode(StringBuilder sb, MindmapData node, int level) {
        // 一个 * 表示一级：level=1 是 "*"，level=2 是 "**"，依此类推
        sb.append("*".repeat(level)).append(" ").append(node.getLabel()).append("\n");

        // 递归出口：children 为空 → for 循环一次都不执行，自然结束
        for (MindmapData child : node.getChildren()) {
            appendMindMapNode(sb, child, level + 1);
        }
    }

    /**
     * 架构图数据 → PlantUML 语法
     *
     * 架构图用 PlantUML 的【组件图】语法：
     * - 组件用方括号 [名字] 表示
     * - 依赖关系用 --> 表示
     * - 冒号后是连接说明
     *
     * 示例：
     * @startuml
     * [Web前端] --> [订单服务] : HTTP
     * [订单服务] --> [MySQL数据库] : SQL
     * @enduml
     *
     * 注意：架构图复用 FlowchartData 类（nodes + edges 天然适合表达组件依赖），
     *       但只关心 label 和 from/to，不关心节点的 type。
     */
    public String buildArchitecture(FlowchartData data) {
        // 建 nodeMap：id → Node，方便根据边里的 from/to 查组件名
        Map<String, FlowchartData.Node> nodeMap = new HashMap<>();
        for (FlowchartData.Node node : data.getNodes()) {
            nodeMap.put(node.getId(), node);
        }

        StringBuilder sb = new StringBuilder("@startuml\n");

        // 遍历每条边，输出一条依赖关系
        for (FlowchartData.Edge edge : data.getEdges()) {
            FlowchartData.Node from = nodeMap.get(edge.getFrom());
            FlowchartData.Node to = nodeMap.get(edge.getTo());
            if (from == null || to == null) {
                continue;  // 防御：边引用了不存在的节点，跳过（ParserService 已校验过，这里只是双保险）
            }
            sb.append("[").append(from.getLabel()).append("] --> [").append(to.getLabel()).append("]");
            // 边有 label（如 HTTP、依赖）就加上，没有就不加
            if (edge.getLabel() != null && !edge.getLabel().isBlank()) {
                sb.append(" : ").append(edge.getLabel());
            }
            sb.append("\n");
        }

        sb.append("@enduml\n");
        return sb.toString();
    }

    /**
     * JSON 数据 → PlantUML 语法（入口方法）
     */
    public String buildPlantUml(FlowchartData data) {        // ① 建 nodeMap：id → Node
        Map<String, FlowchartData.Node> nodeMap = new HashMap<>();
        for (FlowchartData.Node node : data.getNodes()) {
            nodeMap.put(node.getId(), node);
        }

        // ② 建 edgeMap：from节点id → 它的出边列表
        Map<String, List<FlowchartData.Edge>> edgeMap = new HashMap<>();
        for (FlowchartData.Edge edge : data.getEdges()) {
            edgeMap
                    .computeIfAbsent(edge.getFrom(), k -> new ArrayList<>())
                    .add(edge);
        }

        // ③ 找 start 节点
        FlowchartData.Node startNode = null;
        for (FlowchartData.Node node : data.getNodes()) {
            if ("start".equals(node.getType())) {
                startNode = node;
                break;
            }
        }

        // ④ 开始递归
        StringBuilder sb = new StringBuilder("@startuml\n");
        traverse(startNode, nodeMap, edgeMap, sb, new HashSet<>());
        sb.append("@enduml\n");
        return sb.toString();
    }
    /**
     * 递归遍历节点，拼 PlantUML 语法
     *
     * @param node    当前节点
     * @param nodeMap 节点查找表
     * @param edgeMap 出边查找表
     * @param sb      输出的 StringBuilder
     * @param visited 已访问的节点 ID（防死循环）
     */
    private void traverse(FlowchartData.Node node,
                          Map<String, FlowchartData.Node> nodeMap,
                          Map<String, List<FlowchartData.Edge>> edgeMap,
                          StringBuilder sb,
                          Set<String> visited) {

        // ===== end 节点：终点，多条分支汇聚到 end 是正常的，不走 visited 检查 =====
        if ("end".equals(node.getType())) {
            sb.append("stop\n");
            return;
        }

        // ===== 防死循环：已经访问过的节点直接跳过 =====
        if (visited.contains(node.getId())) {
            sb.append("note right: [循环回到 ").append(node.getLabel()).append("]\n");
            sb.append("stop\n");
            return;
        }
        visited.add(node.getId());

        // ===== 根据节点类型输出不同语法 =====
        switch (node.getType()) {

            case "start":
                // start 节点：先写 start，再写标签
                sb.append("start\n");
                sb.append(":").append(node.getLabel()).append(";\n");
                break;

            case "process":
                // process 节点：写标签
                sb.append(":").append(node.getLabel()).append(";\n");
                break;

            case "decision":
                // decision 节点最复杂，有两条出边
                List<FlowchartData.Edge> edges = edgeMap.get(node.getId());

                // 找"是"分支和"否"分支
                FlowchartData.Edge yesEdge = null;
                FlowchartData.Edge noEdge = null;
                for (FlowchartData.Edge e : edges) {
                    if ("是".equals(e.getLabel())) yesEdge = e;
                    if ("否".equals(e.getLabel())) noEdge = e;
                }

                // 如果 label 已经以 ? 结尾，就别再加了
                String label = node.getLabel();
                if (!label.endsWith("?")) {
                    label = label + "?";
                }
                sb.append("if (").append(label).append(") then (是)\n");
                // 递归处理"是"分支
                if (yesEdge != null) {
                    traverse(nodeMap.get(yesEdge.getTo()), nodeMap, edgeMap, sb, visited);
                }

                sb.append("else (否)\n");
                // 递归处理"否"分支
                if (noEdge != null) {
                    traverse(nodeMap.get(noEdge.getTo()), nodeMap, edgeMap, sb, visited);
                }

                sb.append("endif\n");
                // decision 的两条分支都处理完了，不需要再往后走
                return;
        }
        // ===== 非 decision、非 end 节点：顺着唯一出边继续 =====
        List<FlowchartData.Edge> edges = edgeMap.get(node.getId());
        if (edges != null && !edges.isEmpty()) {
            FlowchartData.Node nextNode = nodeMap.get(edges.get(0).getTo());
            traverse(nextNode, nodeMap, edgeMap, sb, visited);
        }
    }
    /**
     * PlantUML 源码 → PNG 字节数组
     *
     * 和 renderToSvg 的区别：
     * - SVG 是文本（XML 字符串），用 String 返回
     * - PNG 是二进制图片，用 byte[] 返回
     * - 前端拿到后需要转成 base64 才能放进 JSON 传输
     */
    public byte[] renderToPng(String plantUmlCode) throws IOException {
        SourceStringReader reader = new SourceStringReader(plantUmlCode);

        ByteArrayOutputStream os = new ByteArrayOutputStream();

        // 唯一区别：FileFormat.SVG → FileFormat.PNG
        reader.outputImage(os, new FileFormatOption(FileFormat.PNG));

        return os.toByteArray();
    }


}
