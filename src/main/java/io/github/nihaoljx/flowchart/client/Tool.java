package io.github.nihaoljx.flowchart.client;

import java.util.List;
import java.util.Map;

/**
 * 任务39 能力版：工具抽象（仿 MCP 的 tool 定义）
 * 每个真实能力工具实现这个接口，注册进 ToolRegistry 即被 LLM 调用。
 * 注意：execute() 永远在【后端】跑，LLM 永远看不到也跑不了这段代码，
 *       它只通过 tools JSON 看到 name/description/parameters 这三样"能力菜单"。
 */
public interface Tool {
    String name();                    // 工具名，如 "read_file"
    String description();            // 给 LLM 看的自然语言说明（决定它何时调）
    String parametersJson();         // OpenAI function calling 的 parameters schema（JSON 字符串）
    String execute(Map<String, Object> args) throws Exception; // 后端真去干，返回结果文本

    /**
     * 任务39 UI 改版：结构化结果（供前端"搜索结果面板"等可视化展示）。
     * 默认返回空列表 = 该工具没有结构化输出（如 read_file 只有纯文本）。
     * web_search 覆盖此方法，返回 [{title,url,snippet,site,date}, ...]。
     */
    default List<Map<String, Object>> structuredResults(Map<String, Object> args, String result) {
        return List.of();
    }

    /**
     * 任务39 容错：工具是否可用（如 code_execute 依赖沙箱服务）。
     * 默认 true；返回 false 的工具不会进入 tools 菜单，模型也不会误调。
     */
    default boolean available() {
        return true;
    }
}
