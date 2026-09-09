package io.github.nihaoljx.flowchart.model;

/**
 * 文档片段（chunk）：RAG 入库的基本单位
 * - content：片段文字
 * - vector：bge-m3 生成的向量（指纹）
 * - source：来源文件名，方便回显/溯源
 * - titlePath：Markdown 标题路径（如 "Mermaid 语法 > 流程图 > 节点形状"），来自结构感知切分
 * - startPos / endPos：原文起止字符位置，便于追溯与前端展示"来自哪一段"
 */
public record DocumentChunk(String id, String content, float[] vector, String source,
                            String titlePath, int startPos, int endPos) {
    /** 兼容旧调用：无 titlePath 和位置信息的构造（向后兼容） */
    public DocumentChunk(String id, String content, float[] vector, String source) {
        this(id, content, vector, source, "", -1, -1);
    }
}
