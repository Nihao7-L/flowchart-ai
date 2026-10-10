package io.github.nihaoljx.flowchart.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 需求规模预估测试：用例全部取自**真实跑过**的用户输入。
 */
class RequestScaleTest {

    @Test
    @DisplayName("三步小流程 → 小活（不该为它动强模型）")
    void smallFlow() {
        assertEquals(RequestScale.SMALL,
                RequestScale.estimate(
                        "画一个三步流程图：开始 -> 处理 -> 结束"));
    }

    @Test
    @DisplayName("六七个节点 → 中活")
    void mediumFlow() {
        assertEquals(RequestScale.MEDIUM,
                RequestScale.estimate(
                        "画一个电商下单流程：用户下单、校验库存、"
                                + "扣减库存、生成订单、支付、发货，"
                                + "用箭头连起来"));
    }

    @Test
    @DisplayName("十几个模块的架构图 → 大活")
    void largeArchitecture() {
        assertEquals(RequestScale.LARGE,
                RequestScale.estimate(
                        "画一个微服务架构图，包含用户服务、订单服务、"
                                + "库存服务、支付服务、通知服务、网关、"
                                + "注册中心、配置中心、日志服务、"
                                + "监控服务、消息队列、缓存、数据库、"
                                + "对象存储、CDN 共十五个模块，"
                                + "标明调用关系"));
    }

    @Test
    @DisplayName("显式报数能单独判出大活（哪怕不列举）")
    void declaredCountAlone() {
        assertEquals(RequestScale.LARGE,
                RequestScale.estimate(
                        "给我画 20 个模块的架构图"));
        assertEquals(RequestScale.LARGE,
                RequestScale.estimate("画十五个模块的架构图"));
    }

    @Test
    @DisplayName("中文数字换算：三 / 十五 / 二十五 / 十二")
    void chineseNumbers() {
        assertEquals(3, RequestScale.toNumber("三"));
        assertEquals(15, RequestScale.toNumber("十五"));
        assertEquals(25, RequestScale.toNumber("二十五"));
        assertEquals(12, RequestScale.toNumber("十二"));
        assertEquals(20, RequestScale.toNumber("二十"));
        assertEquals(2, RequestScale.toNumber("两"));
        assertEquals(15, RequestScale.toNumber("15"));
    }

    @Test
    @DisplayName("空输入 / 单句话 → 小活，绝不炸")
    void blankAndTiny() {
        assertEquals(RequestScale.SMALL, RequestScale.estimate(null));
        assertEquals(RequestScale.SMALL, RequestScale.estimate("   "));
        assertEquals(RequestScale.SMALL, RequestScale.estimate("画个图"));
    }

    @Test
    @DisplayName("超长但没列举 → 至少中活")
    void longTextWithoutList() {
        String longText = "帮我画一张能体现公司整体业务运转情况的示意图，"
                + "把上下游之间的关系都体现出来，尽量清晰好看一些，"
                + "颜色不要太多也不要太少，注意留白和层级关系，"
                + "另外文字别太小，标签要能看清";
        assertEquals(RequestScale.MEDIUM,
                RequestScale.estimate(longText));
    }

    @Test
    @DisplayName("通用量词'个'不该被当成规模（'画一个图'≈1）")
    void genericCounterIgnored() {
        assertEquals(0, RequestScale.maxDeclaredCount("画一个图"));
        assertEquals(0, RequestScale.maxDeclaredCount("画一张示意图"));
    }
}
