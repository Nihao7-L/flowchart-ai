package io.github.nihaoljx.flowchart.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具派发表（v2-55，learn-claude-code s02 的 TOOL_HANDLERS）
 *
 * <p>启动时装配、不可变；运行期只做三件事：按名字派发、
 * 兜执行异常、产出进请求体的工具定义清单。未知工具名不算事故
 * —— 模型迷路时给它一条能看懂的 observation，循环继续。
 */
public final class ToolRegistry {

    private final Map<String, AgentTool> handlers;

    /**
     * @param tools 工具清单；重名视为装配错误，启动即失败（fail-fast）
     */
    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            AgentTool prev = map.put(tool.name(), tool);
            if (prev != null) {
                throw new IllegalStateException(
                        "工具名重复注册: " + tool.name());
            }
        }
        this.handlers = Map.copyOf(map);
    }

    /** 已注册工具名（顺序与注册一致，日志/探针用） */
    public Set<String> names() {
        return handlers.keySet();
    }

    /**
     * 派发一次调用。任何执行异常都兜成失败 observation ——
     * 循环的存活优先于单次工具的成败。
     */
    public Observation dispatch(ToolCall call, ToolContext ctx) {
        AgentTool tool = handlers.get(call.name());
        if (tool == null) {
            return Observation.fail(
                    "未知工具: " + call.name() + "，可用工具: " + names(),
                    null, null);
        }
        try {
            return tool.execute(call.arguments(), ctx);
        } catch (Exception e) {
            return Observation.fail(
                    "工具 " + call.name() + " 执行异常: " + e, null, null);
        }
    }

    /**
     * 工具定义清单（OpenAI function-calling 口径）：
     * {@code [{"type":"function","function":{"name":..,"description":..,
     * "parameters":{...}}}, ...]}
     */
    public List<Map<String, Object>> schemas() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentTool tool : handlers.values()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.put("parameters", tool.parametersSchema());
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", fn);
            out.add(wrapper);
        }
        return out;
    }
}
