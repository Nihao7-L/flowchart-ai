package io.github.nihaoljx.flowchart.prompt;

import java.util.regex.Pattern;

/**
 * 提示词文本的小工具（剥离给人看的注释）。
 *
 * <p>md 源文件里的 HTML 注释是**治理头**（L0 变更史、写给人 / CI 看的），
 * 明写着「不属模型指令」。它按字节算进文件、却不该按 token 付费发给模型
 * —— 实测占 md 的 14%（约 245 token / 轮），且每改一次提示词就膨胀一点。
 *
 * <p>放在这里而不是留在 {@code PromptService} 里：图型模板（{@code diagrams/*.md}）
 * 与内核 md 是同一类资产，剥离口径必须一致，否则会出现「内核剥了、模板没剥」
 * 这种只对了一半的整洁。
 */
public final class PromptText {

    /**
     * HTML 注释，`DOTALL` + 非贪婪：注释可能跨多行，但绝不能一口吞到
     * 下一个注释的结尾。
     */
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?s)<!--.*?-->");

    private PromptText() {
    }

    /**
     * 去掉 md 里的 HTML 注释并去掉首尾空白。
     *
     * @param md md 源文件全文
     * @return 只留模型该看的正文
     */
    public static String stripComments(String md) {
        return COMMENT_PATTERN.matcher(md).replaceAll("").strip();
    }
}
