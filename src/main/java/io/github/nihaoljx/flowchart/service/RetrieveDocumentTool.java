package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 真实工具：检索知识库（包装现有 RagService.retrieve）。
 * 让 LLM 自己决定"要不要查知识库"，替代前端 useRag 勾选框的硬控制。
 */
@Component
public class RetrieveDocumentTool implements Tool {

    private final RagService ragService;

    public RetrieveDocumentTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override public String name() { return "retrieve_document"; }

    @Override public String description() {
        return "从已上传的知识库（用户上传的文档）中检索与问题相关的内容，作为画图参考。当用户提到参考文档/资料时使用。";
    }

    @Override public String parametersJson() {
        return "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"检索问题或关键词\"}},\"required\":[\"query\"]}";
    }

    @Override
    public String execute(Map<String, Object> args) {
        if (!ragService.isEnabled()) return "ERROR: 知识库未配置（缺 embedding key），跳过";
        try {
            String q = String.valueOf(args.getOrDefault("query", ""));
            String ctx = ragService.retrieve(q);
            return ctx.isBlank() ? "知识库无相关内容" : "【知识库检索结果】\n" + ctx;
        } catch (Exception e) {
            return "ERROR: 检索失败 " + e.getMessage();
        }
    }
}
