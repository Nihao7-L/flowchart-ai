package io.github.nihaoljx.flowchart.agent.tools;

import io.github.nihaoljx.flowchart.llm.AgentTool;
import io.github.nihaoljx.flowchart.agent.ToolRegistry;
import io.github.nihaoljx.flowchart.graph.layout.LayoutFamilyRegistry;
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
     * 默认注册表：语义提交工具（submit_spec，唯一生成路径：坐标全交布局引擎）+
     * 增量编辑工具（update_elements）+ 读取工具（read_scene / read_contract）。
     *
     * <p>submit_scene（让 LLM 直出 x/y 坐标）已在 v10.12.0 冻结：它是
     * 文章点名的反模式（让 LLM 当几何排版引擎），cat 超时 / 坐标漂移的根。
     * 不再注册 = LLM 看不到也调不到该工具，所有画图强制走 submit_spec。
     *
     * <p>刻意没有校验工具（2026-10-15 用户拍板「那些不是工具」）：
     * 校验是管线闸门不是模型动作 —— 每次提交在工具内部自动过
     * SceneGate 全管线，模型只收到「通过」或「机读修复单」，它
     * 既不能也无需单独调用校验。曾暴露的 validate_scene 已撤下
     * （模型调它什么都改不了，纯浪费步数的噪音）。
     *
     * @param binder        场景绑定器（现有 bean）
     * @param deduper       场景去重器（现有 bean）
     * @param sanitizer     宽容归一化器（现有 bean）
     * @param planner       场景规划器（分区排版 + claimFramesOnly，现有 bean）
     * @param validator     结构校验器（现有 bean）
     * @param audit         几何体检（现有 bean）
     * @param drift         改图保真（现有 bean；编辑模式首次提交用）
     * @param promptService 提示词服务（契约片段派生）
     * @param families      布局家族注册表（多图型路由：generic/sequence）
     */
    public static ToolRegistry defaultRegistry(
            SceneBinder binder, SceneDeduper deduper,
            SceneSanitizer sanitizer, ScenePlanner planner,
            GraphValidator validator, GeometryAudit audit,
            SceneDrift drift, PromptService promptService,
            LayoutFamilyRegistry families) {
        SceneGate gate = new SceneGate(
                binder, deduper, sanitizer, planner,
                validator, audit, drift);
        List<AgentTool> tools = List.of(
                new SubmitSpecTool(gate, families),
                new UpdateElementsTool(gate),
                new ReadSceneTool(),
                new ReadContractTool(promptService));
        return new ToolRegistry(tools);
    }
}
