package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.DocumentChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存向量库（学习版 RAG）
 * - 用内存 List 存所有片段（重启清零；生产可换 pgvector）
 * - 检索靠"余弦相似度"：两段文字越像，向量夹角越小、分数越接近 1
 */
public class VectorStore {

    private final List<DocumentChunk> chunks = new CopyOnWriteArrayList<>();

    public void add(DocumentChunk chunk) {
        chunks.add(chunk);
    }

    public int size() {
        return chunks.size();
    }

    /** 返回与 queryVec 最像的 topK 个片段 */
    public List<DocumentChunk> search(float[] queryVec, int topK) {
        List<Scored> scored = new ArrayList<>();
        for (DocumentChunk c : chunks) {
            scored.add(new Scored(c, cosine(queryVec, c.vector())));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score)); // 高分在前
        int n = Math.min(topK, scored.size());
        List<DocumentChunk> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(scored.get(i).chunk);
        }
        return result;
    }

    /** 返回与 queryVec 最像的 topK 个片段，并带上相似度分数（供前端展示用） */
    public List<ScoredChunk> searchWithScore(float[] queryVec, int topK) {
        List<Scored> scored = new ArrayList<>();
        for (DocumentChunk c : chunks) {
            scored.add(new Scored(c, cosine(queryVec, c.vector())));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int n = Math.min(topK, scored.size());
        List<ScoredChunk> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(new ScoredChunk(scored.get(i).chunk, scored.get(i).score));
        }
        return result;
    }

    /** 带分数的检索结果（score 越接近 1 越相似） */
    public record ScoredChunk(DocumentChunk chunk, double score) {}

    /** 余弦相似度：点积 / (模长A * 模长B) */
    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Scored(DocumentChunk chunk, double score) {}
}
