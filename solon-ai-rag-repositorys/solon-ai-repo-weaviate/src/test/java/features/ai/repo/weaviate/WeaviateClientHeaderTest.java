package features.ai.repo.weaviate;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.weaviate.WeaviateClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WeaviateClient 集成标识请求头测试（使用本地 HTTP 服务捕获请求头，无需 Weaviate 实例）
 */
public class WeaviateClientHeaderTest {
    private HttpServer server;
    private final AtomicReference<Headers> captured = new AtomicReference<>();

    @BeforeEach
    public void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            captured.set(exchange.getRequestHeaders());
            byte[] body = "{\"classes\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    public void teardown() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    public void sendsIntegrationHeader() throws Exception {
        new WeaviateClient(baseUrl()).getSchema();

        List<String> values = captured.get().get(WeaviateClient.INTEGRATION_HEADER);
        assertNotNull(values);
        assertEquals(1, values.size());
        assertTrue(values.get(0).matches("solon-ai/\\S+"), values.get(0));
    }

    @Test
    public void keepsAuthHeader() throws Exception {
        new WeaviateClient(baseUrl(), "test-token").getSchema();

        assertEquals("Bearer test-token", captured.get().getFirst("Authorization"));
        assertNotNull(captured.get().getFirst(WeaviateClient.INTEGRATION_HEADER));
    }
}
