package io.github.nihaoljx.flowchart.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户那句自然语言里粗略判断"这活有多大"（2026-10-07）。
 *
 * <p>为什么需要它：路由本来只按**轮次**升档 —— 第 1 轮用便宜模型，
 * 首轮没过校验才在第 2 轮放开限量强模型。可是"要画十几个模块"这种
 * 需求在第 1 轮就已经注定难做，等于白白浪费一轮（实测 15 模块大图
 * 首轮四个便宜候选全超时）。有了规模预估，"大活"在第 1 轮就能
 * 直接上强模型。
 *
 * <p>判据只有两个，都是"能从字面看出来"的：
 * <ul>
 *   <li><b>列举段数</b>：顿号 / 逗号 / 箭头切出来的非空段数 ——
 *       用户一口气点了多少样东西</li>
 *   <li><b>声明数量</b>："{15|十五}个模块"这类显式报数，取最大值</li>
 * </ul>
 *
 * <p>刻意不做 NLP / 不调模型：这是**节流阀**不是精度器，
 * 判错一档只是多花或省一次强模型调用，不值得为它引入不确定性。
 */
public final class RequestScale {

    /** 小活：三五个元素，便宜模型够用 */
    public static final int SMALL = 1;

    /** 中活：六到十二个元素，仍先走便宜模型，靠第 2 轮升档兜 */
    public static final int MEDIUM = 2;

    /** 大活：十三个以上，第 1 轮就直接上强模型 */
    public static final int LARGE = 3;

    /** 段数 / 声明数到这个量级就是大活 */
    private static final int LARGE_THRESHOLD = 13;

    /** 段数到这个量级算中活 */
    private static final int MEDIUM_THRESHOLD = 6;

    /** 输入长到这个字数，即便没有列举也至少算中活 */
    private static final int LONG_TEXT_CHARS = 120;

    /**
     * 列举分隔符：顿号 / 逗号 / 分号 / 换行 / 加号 / 竖线 / 箭头。
     *
     * <p>箭头是关键 —— "A -> B -> C" 正是最常见的流程画法。
     * 刻意不收单个 "&gt;"：它会把 "A-&gt;B" 切成 "A-" 和 "B"，
     * 凭空多算一段。
     */
    private static final Pattern SEPARATOR = Pattern.compile(
            "、|，|,|;|；|\n|\\+|＋|\\||｜|->|→");

    /**
     * "{数量}{个?}{可数实体}"：阿拉伯数字与中文数字都认。
     *
     * <p>量词必须是"模块 / 服务 / 节点"这类**实体**，不能是通用的"个"，
     * 否则"画一个图"里的"一个"会被当成规模 1 混进来；中间那个
     * 可选"个"是为了接住"十五**个**模块"这种最常见的说法。
     */
    private static final Pattern COUNT_PATTERN = Pattern.compile(
            "([0-9]{1,3}|[零一二两三四五六七八九十]{1,3})\\s*个?\\s*"
                    + "(模块|服务|节点|流程|步骤|阶段|部分|层|步|角色|"
                    + "主题|方向|页面|栏目)");

    /** 中文数字表：索引即数值 */
    private static final String ZH_DIGITS = "零一二三四五六七八九";

    /** 单次报数超过它就当噪声（"999 个"这种夸张说法） */
    private static final int MAX_SENSIBLE_COUNT = 999;

    private RequestScale() {
    }

    /**
     * 估一档规模。
     *
     * @param text 用户原文，可为 null / 空白
     * @return {@link #SMALL} / {@link #MEDIUM} / {@link #LARGE}
     */
    public static int estimate(String text) {
        if (text == null || text.isBlank()) {
            return SMALL;
        }
        int score = Math.max(
                countSegments(text), maxDeclaredCount(text));
        if (score >= LARGE_THRESHOLD) {
            return LARGE;
        }
        if (score >= MEDIUM_THRESHOLD) {
            return MEDIUM;
        }
        return text.length() >= LONG_TEXT_CHARS ? MEDIUM : SMALL;
    }

    /**
     * 数"列举了几样东西"。
     *
     * <p>长度 &lt; 2 的碎片不计：分隔符连着写（"，，"）或行尾标点
     * 会造出一堆空段，计进去会把小图误判成大图。
     */
    static int countSegments(String text) {
        String[] parts = SEPARATOR.split(text);
        int n = 0;
        for (String p : parts) {
            if (p.strip().length() >= 2) {
                n++;
            }
        }
        return n;
    }

    /** 取文中所有"报数"里的最大值；一个也没有就返回 0 */
    static int maxDeclaredCount(String text) {
        Matcher m = COUNT_PATTERN.matcher(text);
        int max = 0;
        while (m.find()) {
            int v = toNumber(m.group(1));
            if (v > max && v <= MAX_SENSIBLE_COUNT) {
                max = v;
            }
        }
        return max;
    }

    /**
     * 中文数字 / 阿拉伯数字 → int。
     *
     * <p>支持"三""十五""二十五"这类常见写法即可；更罕见的组合
     * （如"一百二十三"）会退化成个位数 —— 这也是故意的：那种句子
     * 的规模早已被**段数**判成大活，这里算错不影响结论。
     */
    static int toNumber(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        boolean allDigits = true;
        for (char c : s.toCharArray()) {
            if (c < '0' || c > '9') {
                allDigits = false;
                break;
            }
        }
        if (allDigits) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        int total = 0;
        int section = 0;
        for (char c : s.toCharArray()) {
            if (c == '两') {
                section = 2;
                continue;
            }
            int d = ZH_DIGITS.indexOf(c);
            if (d >= 0) {
                section = d;
            } else if (c == '十') {
                total += (section == 0 ? 1 : section) * 10;
                section = 0;
            }
        }
        return total + section;
    }
}
