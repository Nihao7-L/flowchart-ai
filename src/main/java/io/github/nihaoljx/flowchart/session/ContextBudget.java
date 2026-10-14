package io.github.nihaoljx.flowchart.session;

import io.github.nihaoljx.flowchart.llm.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 排序 + 预算裁剪
 *
 * rank 治 Lost in the Middle（中间遗忘：prompt 越长，
 * 模型越容易忽略**中段**内容，像人读长文忘了中间几段）。治法 = 把最高
 * 优先级的铁律钉在 system 消息里，不让它被挤到中段。
 *
 * 当前布局（头 → 尾）：
 *
 * [system] 角色 + 契约(schema 全文) + 作图硬约束
 * [user]   当前场景 + 用户需求 +（修正轮）上一轮校验未通过
 *
 * 契约与硬约束**永远在 system**，不进 user —— 这是本类的核心不变量。
 *
 * 本类刻意"留白"：M2(RAG) / M4(历史) 都还没接，MID / LOW / STAGE
 * 三档根本没有内容可压。现在编一套压缩算法只会是没人走的死代码，还容易在
 * 将来误压契约。规则"契约区永不降级"是**白名单**，不是"忘了写"。
 */
@Component
public class ContextBudget {

    /** 高优先级区：契约 + 安全边界 + 硬约束。预算再紧也**永不降级**（G2 决策） */
    public static final String ZONE_HIGH = "HIGH";

    /** 动态区：当前场景 + 用户需求 */
    public static final String ZONE_USER = "USER";

    /**
     * 把两块内容排成有序消息列表。
     *
 * 本期是固定布局：system 在前、user 在后。看起来简单，但它把
     * 「哪块永远在前面」这件事**收在一个地方**——将来接 RAG / 历史时，
     * 只需改这里，不必翻遍调用方。
     *
     * @param systemBlock 静态层正文（角色 + 契约 + 规则）
     * @param userBlock   动态层正文（场景 + 需求 [+ 修正块]）
     * @return 有序消息列表，首条必为 system
     */
    public List<Message> rank(String systemBlock, String userBlock) {
        return List.of(
                Message.system(fitTokenBudget(ZONE_HIGH, systemBlock)),
                Message.user(fitTokenBudget(ZONE_USER, userBlock)));
    }

    /**
     * 按预算裁剪（当前是**框架 + 契约豁免**）。
     *
 * 唯一现在必须写死的规则：契约区（HIGH）永不降级 ——
     * 契约被截断 = 字段漂移 = 后端解析失败，这个白名单是第一道保险。
     *
     * @param zone 内容所属档位
     * @param text 内容原文
     * @return 处理后的内容（当前两档都原样返回）
     */
    public String fitTokenBudget(String zone, String text) {
        if (ZONE_HIGH.equals(zone)) {
            // G2：契约区全量、不摘要、不截断。这里刻意不做任何处理，
            // 是"白名单"而不是"忘了写"。
            return text;
        }
        return text;
    }
}
