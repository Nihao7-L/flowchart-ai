package io.github.nihaoljx.flowchart.context;

import io.github.nihaoljx.flowchart.client.Message;
import io.github.nihaoljx.flowchart.service.PromptService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * 上下文组装器（v2-41 核心）
 *
 * <p>把"每次调模型前拼 prompt"从 {@code PromptService} 的 StringBuilder
 * 抽出来，统一按 <b>system + user 双消息</b> 组装：
 * <ul>
 *   <li>{@code system} —— 固定不变的（角色 / 契约 / 作图规则），每轮一模一样，
 *       因此可作为 KV-cache 的稳定前缀；</li>
 *   <li>{@code user} —— 每轮变化的（当前场景 / 用户需求 / 校验 issues）。</li>
 * </ul>
 * 这样模型能分清"哪些是规则、哪些是本次的内容"，而不是所有东西糊成一坨。
 *
 * <p><b>v7.0.0 已回到单一静态层</b>（用户拍板「不分两个系统提示词」）：
 * system 块 = 一份 {@code system-prompt.md} + 契约段，不再按任务规模分支。
 * 好处是 KV-cache 稳定前缀彻底固定 —— 旧分层方案下「切层级」会让整份
 * system 的尾部变化，缓存命中率随请求内容抖动。
 *
 * <p><b>只改组装方式，不改语义</b>：system + user 的文本拼起来与旧
 * {@code buildPrompt} 逐字节一致，因此出图质量零漂移。
 *
 * <p>⚠️ 已知技术债：本类位于 {@code context} 包却注入了 {@code service.PromptService}，
 * 属包级反向依赖（设计稿原本期望 context 只依赖 client）。当前无 Spring bean 循环
 * （PromptService 不依赖 context），且代码最简，故先这样；M10 收尾时应把
 * PromptService 迁入 context 包、或改为由调用方传入内容块。
 *
 * <p><b>本类不做的事</b>（避免与相邻职责重叠）：不读会话、不落库、不判断
 * 是否新建图 —— 入参由调用方（{@code GenerationService}）备齐。
 */
@Service
public class ContextAssembler {

    private final PromptService promptService;
    private final ContextBudget budget;
    private final ContextReplay replay;

    public ContextAssembler(
            PromptService promptService,
            ContextBudget budget,
            ContextReplay replay) {
        this.promptService = promptService;
        this.budget = budget;
        this.replay = replay;
    }

    /**
     * 组装本轮要发给模型的消息。
     *
     * @param input 组装入参
     * @return 有序消息列表（system 在前）
     * @throws IOException 读契约文件失败（仅静态层需要）
     */
    public List<Message> assemble(AssembleInput input) throws IOException {
        // 1) 取内容块 —— 语义与旧 buildPrompt 完全一致，只是拆成两块。
        //    v7.0.0：静态层已合并为单份，不再按任务规模分支。
        String systemBlock = promptService.buildSystemBlock();
        String userBlock = promptService.buildUserBlock(
                input.userText(), input.currentSceneJson());

        // 2) 修正轮：把上一轮的校验问题追加到 user 块末尾
        //    （与旧 buildFixPrompt 的追加位置一致 —— 都在"用户需求"之后）
        //    判据用"本轮次的 round > 1"：round 表示这组消息**将要用于**的轮次，
        //    所以第 1 轮之后的每一组都要带上修正块。
        if (input.round() > 1
                && input.issues() != null
                && !input.issues().isEmpty()) {
            userBlock = userBlock + promptService.buildFixBlock(input.issues());
        }

        // 3) rank：固定"契约与硬约束在 system"的布局
        List<Message> messages = budget.rank(systemBlock, userBlock);

        // 4) 回放落盘（含 promptVersion，供 M14 逐版本对照）
        replay.dump(input, messages);

        return messages;
    }
}
