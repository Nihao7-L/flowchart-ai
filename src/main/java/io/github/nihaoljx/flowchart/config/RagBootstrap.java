package io.github.nihaoljx.flowchart.config;

import io.github.nihaoljx.flowchart.service.RagService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

/**
 * 任务38：RAG 内存库启动自举。
 *
 * 内存向量库（VectorStore）重启即丢，每次重启都得手动 curl /api/upload 才能再用 RAG。
 * 这里在 Spring 容器就绪后自动把内置的 mermaid_cheatsheet.md 载入向量库，
 * 让"启用知识库"开箱即用，无需每次手动上传。
 *
 * 边界：
 * - 仅当 RAG 已配置（llm.embedding.api-key 存在，ragService.isEnabled()=true）才执行；
 *   未配置时静默跳过，不影响启动，也不影响用户手动上传。
 * - 入库失败（如硅基流动抖动）只打告警日志，不阻断应用启动；运行时仍可手动上传。
 */
@Configuration
public class RagBootstrap {

    @Bean
    public CommandLineRunner loadBuiltinRagDocs(RagService ragService) {
        return args -> {
            if (!ragService.isEnabled()) {
                return;
            }
            try {
                ClassPathResource res = new ClassPathResource("mermaid_cheatsheet.md");
                if (!res.exists()) {
                    return;
                }
                String text = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int chunks = ragService.ingest(text, "mermaid_cheatsheet.md");
                System.out.println("[RAG] 已自动载入 mermaid_cheatsheet.md，共 " + chunks + " 个片段");
            } catch (Exception e) {
                System.err.println("[RAG] 自动载入 mermaid_cheatsheet.md 失败（不影响启动，可手动 /api/upload）：" + e.getMessage());
            }
        };
    }
}
