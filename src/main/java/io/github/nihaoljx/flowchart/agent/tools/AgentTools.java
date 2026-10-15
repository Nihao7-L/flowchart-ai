package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.AgentTool;
import io.github.nihaoljx.flowchart.agent.ToolRegistry;
import io.github.nihaoljx.flowchart.graph.layout.LayoutEngine;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.SceneGate;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.SceneDrift;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.service.PromptService;

import java.util.List;

/**
 * agent 工具组的装配入口
 *
 * 生产装配（由配置类调用）：把 Spring 容器里
 * 既有的校验链 bean 传进来，产出 ToolRegistry ——
 * "复用现有件、降格为工具与观察"的落点。
 */
public final class AgentTools {

    private AgentTools() {
    }

    /**
     * 默认注册表：语义工具（submit_spec）+ 场景工具（submit_scene /
     * update_elements / validate_scene）+ 读取工具（read_scene /
     * read_contract）。
     *
     * @param binder        场景绑定器（现有 bean）
     * @param deduper       场景去重器（现有 bean）
     * @param sanitizer     宽容归一化器（现有 bean）
     * @param planner       场景规划器（分区排版 + claimFramesOnly，现有 bean）
     * @param validator     结构校验器（现有 bean）
     * @param audit         几何体检（现有 bean）
     * @param drift         改图保真（现有 bean；编辑模式首次提交用）
     * @param promptService 提示词服务（契约片段派生）
     * @param layoutEngine  布局引擎（submit_spec 的坐标来源）
     */
    public static ToolRegistry defaultRegistry(
            SceneBinder binder, SceneDeduper deduper,
            SceneSanitizer sanitizer, ScenePlanner planner,
            GraphValidator validator, GeometryAudit audit,
            SceneDrift drift, PromptService promptService,
            LayoutEngine layoutEngine) {
        SceneGate gate = new SceneGate(
                binder, deduper, sanitizer, planner,
                validator, audit, drift);
        List<AgentTool> tools = List.of(
                new SubmitSpecTool(gate, layoutEngine, planner),
                new SubmitSceneTool(gate),
                new UpdateElementsTool(gate),
                new ValidateSceneTool(gate),
                new ReadSceneTool(),
                new ReadContractTool(promptService));
        return new ToolRegistry(tools);
    }
}
