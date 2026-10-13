package io.github.nihaoljx.flowchart.agent;

import java.util.List;
import java.util.Map;

/**
 * agent 路径的最小故障转移客户端（v2-55 步骤 3 实测补）
 *
 * <p><b>为什么必须有它</b>：真实 E2E 实测 glm-5.3-flash 画大图时中转站
 * 直接回 HTTP 502（"请求暂时未能完成"），单候选实现一步失败 = 整次生成
 * 直接兜底收场 —— 而 legacy 管线遇到同样情况会切下一家候选继续。没有
 * 这个件，agent 模式的可用性显著差于 legacy，属于上线即踩的坑。
 *
 * <p><b>刻意最小</b>（与 legacy {@code FailoverLlmProvider} 的差距是有界的）：
 * <ul>
 *   <li>无冷却表 / 无错误分类：一步内把候选按序试完，全挂才抛；</li>
 *   <li>粘性主选：记住上一次成功的候选，后续步骤从它开始 —— 正常情况下
 *       不会因为某次 502 就每步都重付一遍前序候选的失败代价；</li>
 *   <li>预算不放大：所有重试共享调用方传入的同一个 {@code timeoutSeconds}
 *       窗口（它是按剩余总预算折算的，见 AgentLoop）—— 前一个候选烧掉的
 *       时间天然挤占后一个，不会突破总预算。</li>
 * </ul>
 * 正式的冷却 / 分类 / 多实例共享归 M13 网关。
 */
public final class FailoverAgentClient implements AgentLlmClient {

    private final List<AgentLlmClient> candidates;

    /** 上一次成功的候选下标（粘性主选；初始 0 = tier 最小者） */
    private volatile int preferred = 0;

    public FailoverAgentClient(List<AgentLlmClient> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "agent 故障转移至少需要一个候选客户端");
        }
        this.candidates = List.copyOf(candidates);
    }

    @Override
    public AgentTurn respond(List<AgentMessage> messages,
                             List<Map<String, Object>> tools,
                             int timeoutSeconds) throws Exception {
        Exception last = null;
        for (int i = 0; i < candidates.size(); i++) {
            int idx = (preferred + i) % candidates.size();
            try {
                AgentTurn turn = candidates.get(idx)
                        .respond(messages, tools, timeoutSeconds);
                preferred = idx;
                return turn;
            } catch (Exception e) {
                last = e;
                // 有意吞掉继续下一个候选：与 legacy "谁不可用切谁"同口径；
                // 最后一个也挂时把最后的异常抛给循环（走兜底语义）
            }
        }
        throw last;
    }
}
