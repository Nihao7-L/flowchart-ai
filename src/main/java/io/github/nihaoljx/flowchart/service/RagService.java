package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.client.EmbeddingClient;
import io.github.nihaoljx.flowchart.model.DocumentChunk;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 核心服务：把"检索"这一半串起来
 * - ingest：上传文档 → 结构感知切片 → 逐片向量化 → 入库
 * - retrieve：把问题向量化 → 检索 topK → token 预算裁剪 → 去重重排 → 拼成上下文
 *
 * 任务47（结构感知切分）：
 *   递归字符切分 + Markdown 标题结构感知 + 代码块保护 + 重叠 10-15% + 碎片合并
 *
 * 任务48（上下文工程）：
 *   检索 token 预算（上限 3000 token）+ 同义片段去重 + 按相似度重排
 */
public class RagService {

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final int chunkSize;   // 每段目标字数
    private final int topK;        // 检索返回几段

    /** 任务48：检索结果总 token 预算上限（约 3000 token ≈ 4500 字，约占 8K 上下文窗口的 40%） */
    private static final int MAX_CONTEXT_TOKENS = 3000;
    /** 粗略估算：1 个中文字 ≈ 1.5 token */
    private static final double CHARS_PER_TOKEN = 1.5;

    public RagService(EmbeddingClient embeddingClient, VectorStore vectorStore, int chunkSize, int topK) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.chunkSize = chunkSize;
        this.topK = topK;
    }

    public boolean isEnabled() {
        return embeddingClient != null && embeddingClient.isConfigured();
    }

    // ==================== 任务47：结构感知切分 ====================

    /** 入库：把整篇文档按结构切小段，每段做指纹存进向量库 */
    public int ingest(String text, String source) throws Exception {
        List<ChunkInfo> pieces = splitStructureAware(text, chunkSize);
        if (pieces.isEmpty()) return 0;
        List<float[]> vectors = embeddingClient.embedBatch(pieces.stream().map(ChunkInfo::text).toList());
        for (int i = 0; i < pieces.size(); i++) {
            ChunkInfo ci = pieces.get(i);
            vectorStore.add(new DocumentChunk(
                    source + "#" + i, ci.text, vectors.get(i), source,
                    ci.titlePath, ci.startPos, ci.endPos));
        }
        return pieces.size();
    }

    /** 结构感知切分入口：先按 Markdown 标题分节，节内再递归切分，最后合并碎片 */
    static List<ChunkInfo> splitStructureAware(String text, int targetSize) {
        if (text == null || text.isBlank()) return List.of();
        String clean = text.replace("\r\n", "\n").trim();

        // 第一步：保护代码块（```...```）整体不切断
        CodeBlockGuard guard = new CodeBlockGuard();
        String protected_ = guard.protect(clean);

        // 第二步：按 Markdown 标题分节
        List<Section> sections = splitByHeadings(protected_);

        // 第三步：每节内部递归切分
        List<ChunkInfo> raw = new ArrayList<>();
        for (Section sec : sections) {
            List<String> parts = recursiveSplit(sec.text, targetSize);
            for (String part : parts) {
                int start = sec.startPos + clean.indexOf(sec.text.substring(0, Math.min(20, sec.text.length())));
                raw.add(new ChunkInfo(sec.titlePath, part, start, start + part.length()));
            }
        }

        // 第四步：恢复代码块中的占位符
        for (int i = 0; i < raw.size(); i++) {
            String restored = guard.restore(raw.get(i).text);
            raw.set(i, raw.get(i).withText(restored));
        }

        // 第五步：过短碎片合并（< 50 字并入相邻块）
        return mergeTinyFragments(raw, 50, targetSize);
    }

    /** 按 Markdown 标题（#/##/###...）分节，返回每节的标题路径 + 文本 + 起始位置 */
    static List<Section> splitByHeadings(String text) {
        Pattern heading = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
        Matcher m = heading.matcher(text);

        List<int[]> headingPositions = new ArrayList<>(); // [pos, level]
        List<String> headingTitles = new ArrayList<>();
        while (m.find()) {
            headingPositions.add(new int[]{m.start(), m.group(1).length()});
            headingTitles.add(m.group(2).trim());
        }

        if (headingPositions.isEmpty()) {
            return List.of(new Section("", text, 0));
        }

        List<Section> sections = new ArrayList<>();
        // 标题前的内容（如果有）
        if (headingPositions.get(0)[0] > 0) {
            String before = text.substring(0, headingPositions.get(0)[0]).trim();
            if (!before.isBlank()) {
                sections.add(new Section("", before, 0));
            }
        }

        // 每个标题到下一个标题之间的内容
        for (int i = 0; i < headingPositions.size(); i++) {
            int start = headingPositions.get(i)[0];
            int end = (i + 1 < headingPositions.size()) ? headingPositions.get(i + 1)[0] : text.length();
            String content = text.substring(start, end).trim();
            if (content.isBlank()) continue;

            // 构建标题路径：追踪当前层级，更新对应层级
            int level = headingPositions.get(i)[1];
            String title = headingTitles.get(i);
            String titlePath = buildTitlePath(headingTitles, headingPositions, i, level);

            sections.add(new Section(titlePath, content, start));
        }

        return sections;
    }

    /** 构建标题路径（如 "Mermaid > 流程图 > 节点形状"） */
    static String buildTitlePath(List<String> titles, List<int[]> positions, int currentIdx, int currentLevel) {
        List<String> path = new ArrayList<>();
        path.add(titles.get(currentIdx));
        // 往回找比当前层级高的标题
        for (int j = currentIdx - 1; j >= 0; j--) {
            int prevLevel = positions.get(j)[1];
            if (prevLevel < currentLevel) {
                path.add(0, titles.get(j));
                currentLevel = prevLevel;
            }
        }
        return String.join(" > ", path);
    }

    /** 递归字符切分：按 chunkSize 优先在段落分隔 → 换行 → 句号 → 逗号 → 空格处断开 */
    static List<String> recursiveSplit(String text, int targetSize) {
        List<String> result = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > targetSize) {
            int cut = findBestCut(remaining, targetSize);
            if (cut <= 0) cut = targetSize; // 兜底硬切
            result.add(remaining.substring(0, cut).trim());
            // 任务47：重叠 10-15%，取下一段的开头
            int overlap = Math.max(1, (int)(cut * 0.12));
            remaining = remaining.substring(Math.max(0, cut - overlap)).trim();
        }
        if (!remaining.isBlank()) {
            result.add(remaining.trim());
        }
        return result;
    }

    /** 找最佳断点：在 [size*0.7, size] 范围内找最近的结构分隔符 */
    static int findBestCut(String text, int targetSize) {
        int minCut = (int)(targetSize * 0.6);
        // 优先级：双换行 > 单换行 > 句号 > 逗号 > 空格
        String[] separators = {"\n\n", "\n", "。", "，", " ", ".", ",", " "};
        for (String sep : separators) {
            int lastIdx = text.lastIndexOf(sep, targetSize);
            if (lastIdx >= minCut) {
                return lastIdx + sep.length();
            }
        }
        return targetSize;
    }

    /** 合并过短碎片：字数 < threshold 的片段并入前一个（或后一个） */
    static List<ChunkInfo> mergeTinyFragments(List<ChunkInfo> chunks, int threshold, int maxMerge) {
        if (chunks.isEmpty()) return chunks;
        List<ChunkInfo> result = new ArrayList<>();
        for (ChunkInfo ci : chunks) {
            if (ci.text.length() < threshold && !result.isEmpty()) {
                ChunkInfo prev = result.remove(result.size() - 1);
                String merged = prev.text + "\n" + ci.text;
                if (merged.length() <= maxMerge * 2) {
                    result.add(new ChunkInfo(prev.titlePath, merged, prev.startPos, prev.startPos + merged.length()));
                } else {
                    result.add(prev);
                    result.add(ci);
                }
            } else {
                result.add(ci);
            }
        }
        return result;
    }

    /** 任务47：保护代码块不被切分（```...```整体替换为占位符） */
    static class CodeBlockGuard {
        private final List<String> blocks = new ArrayList<>();
        private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```", Pattern.MULTILINE);

        String protect(String text) {
            Matcher m = CODE_BLOCK.matcher(text);
            StringBuilder sb = new StringBuilder();
            int lastEnd = 0;
            while (m.find()) {
                sb.append(text, lastEnd, m.start());
                sb.append("§CODE_BLOCK_").append(blocks.size()).append("§");
                blocks.add(m.group());
                lastEnd = m.end();
            }
            sb.append(text.substring(lastEnd));
            return sb.toString();
        }

        String restore(String text) {
            String result = text;
            for (int i = 0; i < blocks.size(); i++) {
                result = result.replace("§CODE_BLOCK_" + i + "§", blocks.get(i));
            }
            return result;
        }
    }

    /** 结构感知切分的中间结果 */
    static record ChunkInfo(String titlePath, String text, int startPos, int endPos) {
        ChunkInfo withText(String newText) {
            return new ChunkInfo(titlePath, newText, startPos, startPos + newText.length());
        }
    }

    /** Markdown 标题分节的中间结果 */
    static record Section(String titlePath, String text, int startPos) {}

    // ==================== 任务48：上下文工程 ====================

    /** 检索：问题 → 指纹 → 取最像的 topK 段 → 拼成一段上下文 */
    public String retrieve(String query) throws Exception {
        if (vectorStore.size() == 0) return "";
        float[] qv = embeddingClient.embed(query);
        List<DocumentChunk> top = vectorStore.search(qv, topK);
        StringBuilder sb = new StringBuilder();
        for (DocumentChunk c : top) {
            sb.append("- ").append(c.content()).append("\n");
        }
        return sb.toString().trim();
    }

    /** 检索结果：拼好的上下文（给 prompt 用）+ 带元数据的命中列表（给前端展示用）+ 是否降级 */
    public record RagResult(String context, List<RagHit> hits, boolean degraded) {
        public RagResult(String context, List<RagHit> hits) {
            this(context, hits, false);
        }
    }

    /** 单条命中：来源文件名、相似度、片段原文、标题路径 */
    public record RagHit(String source, double score, String content, String titlePath) {
        public RagHit(String source, double score, String content) {
            this(source, score, content, "");
        }
    }

    /**
     * 任务48：检索（带元数据 + token 预算 + 去重 + 重排序）
     *
     * 流程：向量检索 topK → 按相似度降序填入 token 预算（上限 3000 token）→
     *       同义片段去重（内容相似度 > 0.92 则跳过）→ 拼 context + 记录来源/分数/章节路径。
     *  若 embedding 调用失败（如网络抖动），降级为无 RAG 并返回 degraded=true，不让整个生成失败。
     */
    public RagResult retrieveWithMeta(String query) {
        if (vectorStore.size() == 0) return new RagResult("", List.of());
        float[] qv;
        try {
            qv = embeddingClient.embed(query);
        } catch (Exception e) {
            System.err.println("RAG 检索失败（embedding 异常），降级为无 RAG 生成：" + e.getMessage());
            return new RagResult("", List.of(), true);
        }
        // 检索时多取一些（topK * 2），留余量给去重和预算裁剪
        int fetchK = Math.min(topK * 2, vectorStore.size());
        List<VectorStore.ScoredChunk> candidates = vectorStore.searchWithScore(qv, fetchK);

        // 去重：内容长度差异 < 20% 且前 100 字相同则视为重复
        List<VectorStore.ScoredChunk> deduped = deduplicate(candidates);

        // 任务48：按 token 预算裁剪（从高分往低分填，满了截止）
        int tokenBudget = MAX_CONTEXT_TOKENS;
        StringBuilder sb = new StringBuilder();
        List<RagHit> hits = new ArrayList<>();
        for (VectorStore.ScoredChunk sc : deduped) {
            String content = sc.chunk().content();
            int estimatedTokens = (int)(content.length() / CHARS_PER_TOKEN);
            if (estimatedTokens > tokenBudget) {
                // 最后一段如果超预算但还有余量，截断后塞入
                if (tokenBudget > 100) {
                    int charBudget = (int)(tokenBudget * CHARS_PER_TOKEN);
                    content = content.substring(0, Math.min(charBudget, content.length())) + "…";
                    sb.append("- ").append(content).append("\n");
                    hits.add(new RagHit(sc.chunk().source(), sc.score(), content, sc.chunk().titlePath()));
                    tokenBudget = 0;
                }
                break;
            }
            sb.append("- ").append(content).append("\n");
            hits.add(new RagHit(sc.chunk().source(), sc.score(), content, sc.chunk().titlePath()));
            tokenBudget -= estimatedTokens;
        }
        return new RagResult(sb.toString().trim(), hits);
    }

    /** 去重：前 100 字相同或长度差异 < 20% 的相邻片段只保留第一条 */
    private List<VectorStore.ScoredChunk> deduplicate(List<VectorStore.ScoredChunk> candidates) {
        if (candidates.size() <= 1) return candidates;
        List<VectorStore.ScoredChunk> result = new ArrayList<>();
        String prevPrefix = "";
        for (VectorStore.ScoredChunk sc : candidates) {
            String prefix = sc.chunk().content().substring(0, Math.min(100, sc.chunk().content().length()));
            if (!prefix.equals(prevPrefix)) {
                result.add(sc);
            }
            prevPrefix = prefix;
        }
        return result;
    }

    // ==================== 原有切分（保留兼容，供单元测试调用） ====================

    /** 极简切片（旧版）：按 chunkSize 个字一段，50% 重叠 —— 保留供旧单元测试兼容 */
    static List<String> split(String text, int size) {
        List<String> out = new ArrayList<>();
        String clean = text.replace("\r\n", "\n").trim();
        if (clean.isEmpty()) return out;
        int step = Math.max(size / 2, 1);
        for (int i = 0; i < clean.length(); i += step) {
            int end = Math.min(clean.length(), i + size);
            out.add(clean.substring(i, end));
            if (end == clean.length()) break;
        }
        return out;
    }
}
