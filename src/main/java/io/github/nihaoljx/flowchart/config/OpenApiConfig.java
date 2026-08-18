package io.github.nihaoljx.flowchart.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /**
     * 自定义 Swagger 文档的标题/描述/版本
     * 不加这个类也能用，只是文档头部是默认的 springdoc 占位信息
     */
    @Bean
    public OpenAPI flowchartOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI 流程图生成器 API")
                        .description("文字 → LLM → 图表（流程图 / 思维导图 / 架构图）的 REST 接口文档")
                        .version("v1.0"));
    }
}
