package io.github.nihaoljx.flowchart.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class EmbeddingClientTest {

    @Test
    void embed_解析返回的向量() throws Exception {
        // 起一个本地假 embedding 服务，返回固定 3 维向量（不依赖真实 API）
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            String body = "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        EmbeddingClient client = new EmbeddingClient(
                "http://127.0.0.1:" + port + "/v1/embeddings", "sk-test", "BAAI/bge-m3");

        float[] vec = client.embed("测试文字");
        assertEquals(3, vec.length);
        assertEquals(0.1f, vec[0], 0.0001f);
        assertEquals(0.2f, vec[1], 0.0001f);
        assertEquals(0.3f, vec[2], 0.0001f);

        server.stop(0);
    }
}
