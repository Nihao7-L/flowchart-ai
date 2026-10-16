package io.github.nihaoljx.flowchart.agent;

import io.github.nihaoljx.flowchart.agent.tools.AgentTools;
import io.github.nihaoljx.flowchart.llm.LlmProperties;
import io.github.nihaoljx.flowchart.graph.layout.LayoutEngine;
import io.github.nihaoljx.flowchart.graph.validate.GeometryAudit;
import io.github.nihaoljx.flowchart.graph.validate.GraphValidator;
import io.github.nihaoljx.flowchart.graph.transform.SceneBinder;
import io.github.nihaoljx.flowchart.graph.transform.SceneDeduper;
import io.github.nihaoljx.flowchart.graph.transform.SceneDrift;
import io.github.nihaoljx.flowchart.graph.transform.ScenePlanner;
import io.github.nihaoljx.flowchart.graph.transform.SceneSanitizer;
import io.github.nihaoljx.flowchart.service.PromptService;
import io.github.nihaoljx.flowchart.critique.GeometricCritic;
import io.github.nihaoljx.flowchart.critique.NoopVisionCritic;
import io.github.nihaoljx.flowchart.critique.VisualFeedbackConfig;
import io.github.nihaoljx.flowchart.critique.VisualFeedbackService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import io.github.nihaoljx.flowchart.llm.AgentLlmClient;
import io.github.nihaoljx.flowchart.llm.FailoverAgentClient;
import io.github.nihaoljx.flowchart.llm.OpenAiToolClient;
import io.github.nihaoljx.flowchart.llm.AgentResult;

/**
 * Agent Loop 的 Spring 装配与运行入口
 *
 * 这是**唯一**的生成路径：bean 恒被创建、由 GenerationService
 * 直接注入，没有开关可退。
 *
 * 候选解析：密钥不在这里复制一份，从 LlmProperties 候选表里按
 * model id 挑（探针实测 glm / deepseek / grok 三家 tool_calls
 * 全通过，表内随便选都安全），配置留空则取 tier 最小者。
 * 全部可用候选按 tier 升序包进 FailoverAgentClient 做最小
 * 故障转移 —— 实测单候选一步失败（如画大图遇 502）会拖垮
 * 整次生成，可用性差。
 */
@Service
public class AgentLoopService {

    private static final Logger LOG =
            LoggerFactory.getLogger(AgentLoopService.class);

    private final AgentLoop loop;

    /** 解析出的候选逻辑名（SSE/日志溯源用） */
    private final String candidateName;

    /** 视觉自纠闭环开关（D2）。默认全关，开启需显式配置且接好渲染+视觉模型 */
    @Value("${chartflow.visual-feedback.enabled:false}")
    private boolean vfEnabled;
    @Value("${chartflow.visual-feedback.geometric:true}")
    private boolean vfGeometric;
    @Value("${chartflow.visual-feedback.vision:false}")
    private boolean vfVision;

    public AgentLoopService(
            SceneBinder binder,
            SceneDeduper deduper,
            SceneSanitizer sanitizer,
            ScenePlanner planner,
            GraphValidator validator,
            GeometryAudit audit,
            SceneDrift drift,
            PromptService promptService,
            LayoutEngine layoutEngine,
            LlmProperties llmProperties,
            @Value("${chartflow.agent-loop.model:}")
            String preferredModel) {
        LlmProperties.Candidate candidate =
                pickCandidate(llmProperties.getCandidates(),
                        preferredModel);
        if (candidate == null) {
            throw new IllegalStateException(
                    "候选表里解析不出可用候选（preferredModel="
                            + preferredModel + "），请检查"
                            + " llm.candidates 配置");
        }
        this.candidateName = candidate.getName();
        ToolRegistry registry = AgentTools.defaultRegistry(
                binder, deduper, sanitizer, planner,
                validator, audit, drift, promptService,
                layoutEngine);
        // 最小故障转移（实测补）：全部候选按 tier 包进
        // FailoverAgentClient —— 实测 glm 画大图 502，单候选
        // 一步失败 = 整次兜底，可用性差
        List<LlmProperties.Candidate> usable =
                new ArrayList<>(llmProperties.getCandidates() == null
                        ? List.of() : llmProperties.getCandidates());
        usable.removeIf(c -> c.getBaseUrl() == null
                || c.getApiKey() == null || c.getModel() == null);
        usable.sort(Comparator.comparingInt(
                LlmProperties.Candidate::getTier));
        List<AgentLlmClient> clients = new ArrayList<>();
        for (LlmProperties.Candidate c : usable) {
            clients.add(new OpenAiToolClient(
                    c.getBaseUrl(), c.getApiKey(),
                    c.getModel(), c.getMaxTokens()));
        }
        this.loop = new AgentLoop(
                new FailoverAgentClient(clients),
                registry, promptService);
        LOG.info("Agent Loop 已启用：主选 {}（model={}），故障转移候选 {} 个，"
                        + "MAX_STEPS={}，契约按 read_contract 按需取",
                candidateName, candidate.getModel(),
                clients.size(), AgentLoop.MAX_STEPS);
    }

    /**
     * 构造后注入视觉自纠服务（D2）。字段注入在构造后完成，故放 @PostConstruct。
     * 默认开关全关，{@link AgentLoop} 行为与改造前完全一致；开启后由
     * {@link VisualFeedbackService} 在每轮校验通过后多做一次结构化几何/视觉批判。
     */
    @PostConstruct
    void wireVisualFeedback() {
        this.loop.setVisualFeedback(new VisualFeedbackService(
                new VisualFeedbackConfig(vfEnabled, vfGeometric, vfVision),
                new GeometricCritic(), new NoopVisionCritic()));
        if (vfEnabled) {
            LOG.info("视觉自纠闭环已启用（geometric={}, vision={}）",
                    vfGeometric, vfVision);
        }
    }

    /** 供 `GenerationService` 驱动一次 agent run（含编辑基线） */
    public AgentResult run(String systemBlock, String userText,
                           List<Map<String, Object>> userElements,
                           String previousJson,
                           long deadlineMs,
                           AgentLoopListener listener) throws Exception {
        return loop.run(systemBlock, userText, userElements,
                previousJson, deadlineMs, listener);
    }

    /** 候选逻辑名（SSE 启动事件与日志溯源用） */
    public String candidateName() {
        return candidateName;
    }

    /**
     * 候选解析（包级可见以便单测）：
     * 先按 model id 精确匹配（tier 升序取第一个命中），配置留空则
     * 取 tier 最小者；全表无匹配返回 null（构造器据此 fail-fast）。
     */
    static LlmProperties.Candidate pickCandidate(
            List<LlmProperties.Candidate> candidates,
            String preferredModel) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<LlmProperties.Candidate> sorted = candidates.stream()
                .filter(c -> c.getBaseUrl() != null
                        && c.getApiKey() != null
                        && c.getModel() != null)
                .sorted(Comparator.comparingInt(
                        LlmProperties.Candidate::getTier))
                .toList();
        if (preferredModel == null || preferredModel.isBlank()) {
            return sorted.isEmpty() ? null : sorted.get(0);
        }
        return sorted.stream()
                .filter(c -> preferredModel.equals(c.getModel()))
                .findFirst()
                .orElse(null);
    }
}
