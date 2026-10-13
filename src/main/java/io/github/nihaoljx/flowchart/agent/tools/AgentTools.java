package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.agent.AgentTool;
import io.github.nihaoljx.flowchart.agent.ToolRegistry;
import io.github.nihaoljx.flowchart.graph.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.GraphValidator;
import io.github.nihaoljx.flowchart.graph.SceneBinder;
import io.github.nihaoljx.flowchart.graph.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.SceneSanitizer;
import io.github.nihaoljx.flowchart.service.PromptService;

import java.util.List;

/**
 * agent 工具组的装配入口（v2-55）
 *
 * <p>生产装配（step 3 接线时在配置类里调用）：把 Spring 容器里
 * 既有的校验链 bean 传进来，产出 {@link ToolRegistry} ——
 * "复用现有件、降格为工具与观察"的落点。
 */
public final class AgentTools {

    private AgentTools() {
    }

    /**
     * 首批 4 工具的默认注册表。
     *
     * @param binder        场景绑定器（现有 bean）
     * @param deduper       场景去重器（现有 bean）
     * @param sanitizer     宽容归一化器（现有 bean）
     * @param planner       场景规划器（只用 claimFramesOnly，现有 bean）
     * @param validator     结构校验器（现有 bean）
     * @param audit         几何体检（现有 bean）
     * @param promptService 提示词服务（契约片段派生）
     */
    public static ToolRegistry defaultRegistry(
            SceneBinder binder, SceneDeduper deduper,
            SceneSanitizer sanitizer, ScenePlanner planner,
            GraphValidator validator, GeometryAudit audit,
            PromptService promptService) {
        SceneGate gate = new SceneGate(
                binder, deduper, sanitizer, planner, validator, audit);
        List<AgentTool> tools = List.of(
                new SubmitSceneTool(gate),
                new ValidateSceneTool(gate),
                new ReadSceneTool(),
                new ReadContractTool(promptService));
        return new ToolRegistry(tools);
    }
}
