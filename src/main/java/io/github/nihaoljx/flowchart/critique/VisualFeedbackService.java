package io.github.nihaoljx.flowchart.critique;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 视觉自纠闭环的编排器：把「渲染后的图」交给若干批判器（几何/视觉），汇总成
 * **结构化**修正报告，并给出可直接回喂给 LLM 的引导文本。
 *
 * <p>设计铁律（D6）：引导文本只点名元素 + 给 spec 级改法（间距/加宽/走向），
 * 绝不要求做像素修补。
 *
 * <p>默认 disabled（{@code enabled=false}）：此时 {@link #critique} 直接返回空报告，
 * 管线行为与改造前完全一致 —— 开关在 {@code AgentLoopService} 注入。解析失败也
 * 按「无问题」处理，绝不打断生成。
 */
public final class VisualFeedbackService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VisualFeedbackConfig config;
    private final DiagramCritic geometric;
    private final VisionCritic vision;

    public VisualFeedbackService(VisualFeedbackConfig config,
                                 DiagramCritic geometric, VisionCritic vision) {
        this.config = config;
        this.geometric = geometric;
        this.vision = vision;
    }

    public CritiqueReport critique(String sceneJson, String userIntent) {
        if (!config.enabled() || sceneJson == null || sceneJson.isBlank()) {
            return CritiqueReport.empty();
        }
        List<Map<String, Object>> elements;
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(sceneJson);
            if (root.isArray()) {
                elements = MAPPER.convertValue(root,
                        new TypeReference<List<Map<String, Object>>>() {});
            } else if (root.isObject() && root.has("elements")
                    && root.get("elements").isArray()) {
                // 场景 JSON 是 {"elements":[...]}（Excalidraw 信封）——取内层数组
                elements = MAPPER.convertValue(root.get("elements"),
                        new TypeReference<List<Map<String, Object>>>() {});
            } else {
                return CritiqueReport.empty();
            }
        } catch (Exception e) {
            // 解析失败绝不能打断生成：当无问题处理
            return CritiqueReport.empty();
        }
        List<CritiqueIssue> issues = new ArrayList<>();
        if (config.geometric() && geometric != null) {
            issues.addAll(geometric.critique(elements, userIntent));
        }
        if (config.vision() && vision != null) {
            issues.addAll(vision.critique(elements, userIntent));
        }
        return new CritiqueReport(issues, buildGuidance(issues));
    }

    private static String buildGuidance(List<CritiqueIssue> issues) {
        if (issues.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【视觉自检】渲染后发现 ").append(issues.size())
          .append(" 处可结构性修正（请改 spec 重排/加宽/拉开，不要像素修补）：\n");
        for (CritiqueIssue it : issues) {
            sb.append("- [").append(label(it.category())).append("] ")
              .append(it.message()).append(" → ").append(it.suggestion()).append('\n');
        }
        return sb.toString();
    }

    private static String label(CritiqueIssue.Category c) {
        return switch (c) {
            case OVERLAP -> "重叠";
            case OFF_CANVAS -> "出界";
            case LABEL_OVERFLOW -> "标签溢出";
            case EDGE_CROSSING -> "连线交叉";
            case TINY -> "过小";
            default -> "其他";
        };
    }
}
