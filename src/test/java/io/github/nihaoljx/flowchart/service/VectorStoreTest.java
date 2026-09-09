package io.github.nihaoljx.flowchart.service;

import io.github.nihaoljx.flowchart.model.DocumentChunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class VectorStoreTest {

    @Test
    void cosine_相同向量为1_相反为_1() {
        float[] a = {1, 0};
        float[] b = {1, 0};
        float[] c = {-1, 0};
        assertEquals(1.0, VectorStore.cosine(a, b), 1e-9);
        assertEquals(-1.0, VectorStore.cosine(a, c), 1e-9);
    }

    @Test
    void search_返回最相似的片段() {
        VectorStore store = new VectorStore();
        store.add(new DocumentChunk("1", "苹果是一种水果", new float[]{1, 0}, "doc"));
        store.add(new DocumentChunk("2", "汽车是交通工具", new float[]{0, 1}, "doc"));

        // 查询向量靠近"苹果"那条
        var top = store.search(new float[]{0.9f, 0.1f}, 1);
        assertEquals(1, top.size());
        assertEquals("1", top.get(0).id());
        assertTrue(top.get(0).content().contains("苹果"));
    }
}
