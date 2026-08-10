package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.FlowchartData;
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
     * JSON 数据 → PlantUML 语法（入口方法）
     */
    public String buildPlantUml(FlowchartData data) {
        // ① 建 nodeMap：id → Node
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

        // ===== 防死循环：已经访问过的节点直接跳过 =====
        if (visited.contains(node.getId())) {
            sb.append("note right: [循环回到 ").append(node.getLabel()).append("]\n");
            sb.append("stop\n");  // ← 显式结束这条分支，避免悬空线
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

            case "end":
                // end 节点：写 stop，然后直接返回（没有出边）
                sb.append("stop\n");
                return;

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

}
