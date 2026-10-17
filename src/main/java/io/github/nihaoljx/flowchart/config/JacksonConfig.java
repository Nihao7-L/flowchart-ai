package io.github.nihaoljx.flowchart.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 共享 JSON 映射器装配（report 1.4 ObjectMapper 收敛）
 *
 * <p>修复前 src/main 里 {@code @Bean} 命中 0：{@code new ObjectMapper()}
 * 26 处 + {@code static final ObjectMapper} 13 处 = 39 个实例，每个
 * 各带一套序列化缓存、配置各自为政。这里提供一个全局共享的
 * {@code ObjectMapper}，交给 {@code @Component} 消费者按构造注入复用。
 *
 * <p>注意：{@link
 * io.github.nihaoljx.flowchart.graph.validate.SceneGate} 的
 * {@code STRICT_JSON} 刻意开启 {@code FAIL_ON_TRAILING_TOKENS}（对齐前端
 * {@code JSON.parse} 严格口径），与这里的共享 map 配置**不同**，不是本
 * bean —— 它必须保留独立实例。
 */
@Configuration
public class JacksonConfig {

    /** 共享 JSON 映射器：默认配置（读树/写文本），供组件构造注入复用 */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
