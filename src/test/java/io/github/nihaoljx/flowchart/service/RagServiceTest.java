package io.github.nihaoljx.flowchart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.nihaoljx.flowchart.client.EmbeddingClient;
import io.github.nihaoljx.flowchart.model.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class RagServiceTest {

    @Test
    void ingest_then_retrieve_能找到相关片段() throws Exception {
        // 假 embeddings 服务：按 input 数量返回同等数量的 [1,0] 向量
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                byte[] reqBytes = exchange.getRequestBody().readAllBytes();
                int count = new ObjectMapper().readTree(reqBytes).get("input").size();
                StringBuilder sb = new StringBuilder("{\"data\":[");
                for (int i = 0; i < count; i++) {
                    sb.append("{\"embedding\":[1,0]}");
                    if (i < count - 1) sb.append(",");
                }
                sb.append("]}");
                byte[] resp = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                exchange.getResponseBody().write(resp);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        EmbeddingClient client = new EmbeddingClient(
                "http://127.0.0.1:" + port + "/v1/embeddings", "sk-test", "BAAI/bge-m3");
        VectorStore store = new VectorStore();
        RagService rag = new RagService(client, store, 50, 3);

        int n = rag.ingest("苹果是一种水果。香蕉也是水果。汽车是交通工具。", "test.md");
        assertTrue(n > 0);
        assertTrue(store.size() > 0);

        String ctx = rag.retrieve("水果有哪些");
        assertNotNull(ctx);
        assertTrue(ctx.contains("苹果")); // 所有片段向量都是 [1,0]，topK=3 至少含"苹果"

        server.stop(0);
    }
}
