package io.github.nihaoljx.flowchart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用上下文冒烟测试。
 *
 * <p>为什么需要它：本仓库原先**没有任何 `@SpringBootTest`** —— 412 个用例
 * 全是纯单测（手工 `new`、或 MockMvc 直连控制器），因此「Spring 能不能把
 * 全部 bean 装配起来」这件事从来没被自动验证过。装配失败只会在人工启动时
 * 才暴露，而那时已经上线了。
 *
 * <p>本次引入它的直接动因是 `springdoc-openapi-starter-webmvc-ui`
 * 2.3.0 → 2.6.0 升级（配合 Boot 3.3.2）：springdoc 走的是自动装配，
 * 版本不配套时表现为启动期 bean 缺失或 `spring.factories` 钩子静默不生效，
 * **单测一个都不会红**。因此这里断言上下文能起来，并顺带确认 OpenAPI
 * 文档 bean 真的被注册了（否则升级等于白做）。
 *
 * <p>不启动真实 HTTP 端口（`webEnvironment = NONE` 会跳过 Web 层，
 * 用 MOCK 保 Web 层但不占端口），也不读 `.env`/真实 LLM key ——
 * LLM 客户端在构造期不发起调用，缺 key 只影响运行期请求。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("应用上下文装配")
class ApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("上下文能装配起来且 springdoc 自动装配已生效")
    void contextLoadsWithOpenApi() {
        // 上下文能起来本身就是断言（装配失败会让本用例报错而不是失败）
        assertThat(context.getBeanDefinitionCount()).isPositive();

        // springdoc 自动装配的落点。/v3/api-docs 由 openApiResource 提供服务，
        // /swagger-ui 由 swaggerWelcome 提供服务 —— 这两个 bean 存在，就说明
        // spring.factories 里的钩子被 Boot 3.3 认了（这正是 2.3.0 升级到 2.6.0
        // 要修的东西）。412 个纯单测一个都不会察觉此事，只有这里会红。
        //
        // 注意不要断言 OpenAPI bean：springdoc 是在请求 /v3/api-docs 时才由
        // OpenAPIService 现场构建文档，它不是单例 bean。本项目也没有显式声明
        // GroupedOpenApi（那要先做分组），同样不能拿来当判据。
        assertThat(context.getBeansOfType(org.springdoc.webmvc.api.OpenApiWebMvcResource.class))
                .isNotEmpty();
        assertThat(context.getBeansOfType(org.springdoc.webmvc.ui.SwaggerWelcomeWebMvc.class))
                .isNotEmpty();
        assertThat(context.getBeansOfType(org.springdoc.core.service.OpenAPIService.class))
                .isNotEmpty();
    }
}
