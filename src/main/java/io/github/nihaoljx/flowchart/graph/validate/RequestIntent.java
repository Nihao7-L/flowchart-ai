package io.github.nihaoljx.flowchart.graph.validate;

/**
 * 需求类别判定（2026-10-16 ref_cat 事故后新增）。
 *
 * <p>事故现场：需求「画一只可爱的卡通猫，用最简单的几何形状拼……」——
 * 模型交出 15 个椭圆/菱形用 9 条箭头串起来的「零件关系图」（body→head→
 * ears→…），系统报「校验通过、生成完成」，而打分器还给了 0.6 分。
 * 根因不是模型不会画猫，而是**没有任何机器判据区分「图表需求」与
 * 「绘画/构图需求」**：提示词第 48 行的「非画图需求走兜底」只写在
 * md 里，没有代码拦，等于不存在。
 *
 * <p>本类把那个区分做成**确定性规则**（不调模型、可复现、可单测）：
 *
 * <ul>
 *   <li>{@link #DIAGRAM} —— 图表类（流程/架构/时序/导图/状态机/关系图…），
 *       走 submit_spec 语义路径，位置交给布局引擎；</li>
 *   <li>{@link #NON_DIAGRAM} —— 绘画/构图类（写实物体、人物动物、风景、
 *       卡通形象、logo/图标，以及「用几何形状拼出一个形象」这类构图需求）。
 *       当前唯一提交路径（submit_spec → ELK 排版）**结构上不可能满足**
 *       这类需求（位置本身就是语义，而坐标全交布局引擎），产品必须
 *       如实说明能力边界，不能交一张图表冒充；</li>
 *   <li>{@link #UNKNOWN} —— 判不出来。**不拦**（宁可放过，不可误伤）：
 *       判错的代价是"本来能画的需求被拒绝"，比多跑一轮更糟。</li>
 * </ul>
 *
 * <p>判定顺序刻意是「先看有没有图表词」：图表词命中即判 DIAGRAM，
 * 于是「猫的生命周期」（含 猫 + 生命周期）正确落到 DIAGRAM，
 * 而「画一只卡通猫」落到 NON_DIAGRAM。
 */
public enum RequestIntent {

    /** 图表类需求：节点/连线语义可表达，走 submit_spec */
    DIAGRAM,

    /** 绘画/构图类需求：当前的语义提交路径无法表达 */
    NON_DIAGRAM,

    /** 判不出来：不拦，按老路径走 */
    UNKNOWN;

    /**
     * 图表词：**刻意不收单字「图」**——「画一张猫图」也含「图」，
     * 收了会把绘画需求误判成图表需求（正好放掉我们要拦的那一类）。
     */
    private static final String[] DIAGRAM_WORDS = {
        "流程图", "架构图", "结构图", "示意图", "关系图", "类图", "用例图",
        "部署图", "拓扑图", "框图", "时序图", "状态图", "思维导图", "脑图",
        "鱼骨", "甘特", "时间线", "里程碑", "泳道", "决策树",
        "流程", "架构", "时序", "状态机", "拓扑", "调用链", "依赖关系",
        "数据流", "生命周期", "组织架构", "分层", "模块", "体系", "机制",
        "原理", "步骤", "阶段", "矩阵", "对比", "分类", "目录", "清单",
        "生态", "审批", "工单", "订单", "支付", "认证", "鉴权", "网关",
        "微服务", "中台", "监控", "告警", "算法", "数据库", "接口",
        "系统", "服务", "网络", "安全", "流水线", "迭代", "部署", "编排",
        "UML", "ER图", "CI/CD", "CICD"
    };

    /** 媒介/风格词：出现即说明用户要的是"一张画"，不是一张图 */
    private static final String[] MEDIUM_WORDS = {
        "写实", "照片", "摄影", "素描", "速写", "插画", "插图", "绘本",
        "漫画", "卡通", "拟人", "拟物", "油画", "水彩", "海报", "头像",
        "图标", "logo", "LOGO", "Logo", "标志", "贴纸", "表情包", "壁纸"
    };

    /** 绘制动词：单独出现不足以判（"画一张流程图"含"画"），要与实体词同现 */
    private static final String[] DRAW_VERBS = {
        "画", "绘制", "描", "涂鸦", "涂", "临摹", "拼", "绘"
    };

    /** 实体/题材词：写实物体、人物、动物、风景、静物 */
    private static final String[] ENTITY_NOUNS = {
        "猫", "狗", "动物", "人物", "人像", "肖像", "女孩", "男孩", "女人",
        "男人", "宝宝", "小孩", "孩子", "美女", "风景", "山水", "山", "海",
        "沙滩", "树", "花", "鸟", "鱼", "蝴蝶", "熊猫", "老虎", "狮子",
        "大象", "恐龙", "兔子", "马", "牛", "羊", "猪", "鸡", "鸭", "怪兽",
        "机器人", "汽车", "车", "卡车", "飞机", "船", "自行车", "摩托",
        "火箭", "房子", "小屋", "城堡", "桥", "桌子", "椅子", "花瓶",
        "咖啡", "蛋糕", "苹果", "西瓜", "水果", "披萨", "冰淇淋", "星空",
        "银河", "月亮", "太阳", "云朵", "彩虹", "雪人", "海浪"
    };

    /**
     * 判一条需求的类别。
     *
     * @param request 用户需求原文；null / 空白判 UNKNOWN
     * @return 类别；判不出来返回 {@link #UNKNOWN}（调用方不得据此拦截）
     */
    public static RequestIntent classify(String request) {
        if (request == null || request.isBlank()) {
            return UNKNOWN;
        }
        String text = request.trim();
        // 1) 图表词优先：图表的表达方式明确时，体裁就是图表
        if (containsAny(text, DIAGRAM_WORDS)) {
            return DIAGRAM;
        }
        // 2) 媒介词：写实/卡通/插画/logo…＝要一张画
        if (containsAny(text, MEDIUM_WORDS)) {
            return NON_DIAGRAM;
        }
        // 3) 绘制动词 + 实体题材：（"画" + "猫"）
        if (containsAny(text, DRAW_VERBS)
                && containsAny(text, ENTITY_NOUNS)) {
            return NON_DIAGRAM;
        }
        return UNKNOWN;
    }

    /** 是否"当前语义路径表达不了、必须如实说明能力边界"的一类需求 */
    public boolean isNonDiagram() {
        return this == NON_DIAGRAM;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
