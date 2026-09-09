package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具注册表：Spring 自动把所有 @Component 的 Tool 实现注入进来，
 * 拼成 OpenAI 要求的 tools 数组 JSON（发给 LLM 的"能力菜单"）。
 * 新增工具 = 写一个实现 Tool 的类 + 加 @Component，无需改这里。
 */
@Component
public class ToolRegistry {

    private final List<Tool> tools;

    public ToolRegistry(List<Tool> toolBeans) { // Spring 自动收集所有 Tool bean
        this.tools = toolBeans;
    }

    /** 拼成 OpenAI tools 数组 JSON 字符串 */
    public String toolsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tools.size(); i++) {
            Tool t = tools.get(i);
            if (!t.available()) continue;   // Fix3：不可用的工具（如沙箱未配）不进菜单，模型不会误调
            sb.append("{\"type\":\"function\",\"function\":{")
               .append("\"name\":\"").append(t.name()).append("\",")
               .append("\"description\":\"").append(t.description()).append("\",")
               .append("\"parameters\":").append(t.parametersJson())
               .append("}}");
            if (i < tools.size() - 1) sb.append(",");
        }
        return sb.append("]").toString();
    }

    /** 按名字取工具（执行时用） */
    public Tool get(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
    }
}
