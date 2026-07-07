package features.ai.talent.openapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.gateway.OpenApiGatewayTalent;
import org.noear.solon.ai.talents.gateway.openapi.ApiResolver;
import org.noear.solon.ai.talents.gateway.openapi.ApiTool;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OpenApiGatewayTalentTest {
    @Test
    @DisplayName("OpenAPI 调用：替换并编码 Path 参数")
    void testReplacePathParams() throws Exception {
        ApiTool tool = new ApiTool();
        tool.setPath("/users/{id}/files/{fileName}");
        tool.setPathSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},\"fileName\":{\"type\":\"string\"}},\"required\":[\"id\",\"fileName\"]}");

        Map<String, Object> pathParams = new HashMap<>();
        pathParams.put("id", "张 三");
        pathParams.put("fileName", "a/b.txt");

        String finalPath = replacePathParams(tool, pathParams);

        assertEquals("/users/%E5%BC%A0%20%E4%B8%89/files/a%2Fb.txt", finalPath);
    }

    @Test
    @DisplayName("OpenAPI 调用：缺少 Path 参数时保留占位符")
    void testReplacePathParamsWithMissingValue() throws Exception {
        ApiTool tool = new ApiTool();
        tool.setPath("/users/{id}");
        tool.setPathSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}");

        String finalPath = replacePathParams(tool, new HashMap<>());

        assertEquals("/users/{id}", finalPath);
    }

    @Test
    @DisplayName("OpenAPI 调用：替换多类型 Path 参数")
    void testReplacePathParamsWithMultipleValueTypes() throws Exception {
        ApiTool tool = new ApiTool();
        tool.setPath("/tenants/{tenantId}/enabled/{enabled}/orders/{orderId}");
        tool.setPathSchema("{\"type\":\"object\",\"properties\":{\"tenantId\":{\"type\":\"integer\"},\"enabled\":{\"type\":\"boolean\"},\"orderId\":{\"type\":\"integer\"}},\"required\":[\"tenantId\",\"enabled\",\"orderId\"]}");

        Map<String, Object> pathParams = new HashMap<>();
        pathParams.put("tenantId", 12);
        pathParams.put("enabled", true);
        pathParams.put("orderId", 9876543210L);

        String finalPath = replacePathParams(tool, pathParams);

        assertEquals("/tenants/12/enabled/true/orders/9876543210", finalPath);
    }

    @Test
    @DisplayName("OpenAPI 调用：空字符串 Path 参数会替换为空路径段")
    void testReplacePathParamsWithEmptyString() throws Exception {
        ApiTool tool = new ApiTool();
        tool.setPath("/users/{id}/profile");
        tool.setPathSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}");

        Map<String, Object> pathParams = new HashMap<>();
        pathParams.put("id", "");

        String finalPath = replacePathParams(tool, pathParams);

        assertEquals("/users//profile", finalPath);
    }

    @Test
    @DisplayName("OpenAPI 调用：无 Path Schema 时不替换路径")
    void testReplacePathParamsWithoutPathSchema() throws Exception {
        ApiTool tool = new ApiTool();
        tool.setPath("/users/{id}");

        Map<String, Object> pathParams = new HashMap<>();
        pathParams.put("id", "100");

        String finalPath = replacePathParams(tool, pathParams);

        assertEquals("/users/{id}", finalPath);
    }

    @Test
    @DisplayName("OpenAPI 调用：缺少 Path 参数时在 call_api 阶段拦截")
    void testCallApiReturnsErrorWhenPathParamMissing() throws Exception {
        HttpServer server = startServer(exchange -> writeJson(exchange, "{}"));
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            OpenApiGatewayTalent talent = new OpenApiGatewayTalent().retryConfig(1)
                    .resolver(new TestApiResolver(baseUrl))
                    .addApi(baseUrl + "/openapi.json", baseUrl);

            String result = talent.callApi("getUser", null, null, null, null);

            assertTrue(result.contains("缺少必要的路径参数"));
            assertTrue(result.contains("/users/{id}"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OpenAPI 调用：端到端使用替换后的 Path 且不混入 Query")
    void testCallApiUsesResolvedPathEndToEnd() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestQuery = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            requestPath.set(exchange.getRequestURI().getRawPath());
            requestQuery.set(exchange.getRequestURI().getRawQuery());
            writeJson(exchange, "{\"ok\":true}");
        });
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            OpenApiGatewayTalent talent = new OpenApiGatewayTalent().retryConfig(1)
                    .resolver(new TestApiResolver(baseUrl))
                    .addApi(baseUrl + "/openapi.json", baseUrl);
            Map<String, Object> pathParams = new HashMap<>();
            pathParams.put("id", "张 三/a");
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("verbose", true);

            String result = talent.callApi("getUser", null, pathParams, queryParams, null);

            assertEquals("{\"ok\":true}", result);
            assertEquals("/users/%E5%BC%A0%20%E4%B8%89%2Fa", requestPath.get());
            assertEquals("verbose=true", requestQuery.get());
        } finally {
            server.stop(0);
        }
    }

    private String replacePathParams(ApiTool tool, Map<String, Object> pathParams) throws Exception {
        Method method = OpenApiGatewayTalent.class.getDeclaredMethod("replacePathParams", ApiTool.class, Map.class);
        method.setAccessible(true);
        return (String) method.invoke(new OpenApiGatewayTalent(), tool, pathParams);
    }

    private HttpServer startServer(ExchangeHandler apiHandler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openapi.json", exchange -> writeJson(exchange, openApiJson()));
        server.createContext("/users/%E5%BC%A0%20%E4%B8%89%2Fa", apiHandler::handle);
        server.createContext("/users", apiHandler::handle);
        server.start();
        return server;
    }

    private static class TestApiResolver implements ApiResolver {
        private final String baseUrl;
        
        private TestApiResolver(String baseUrl) {
            this.baseUrl = baseUrl;
        }
        
        @Override
        public String getName() {
            return "TestApiResolver";
        }
    
        @Override
        public java.util.List<ApiTool> resolve(String definitionUrl, String source) {
            ApiTool tool = new ApiTool();
            tool.setBaseUrl(baseUrl);
            tool.setName("getUser");
            tool.setPath("/users/{id}");
            tool.setMethod("GET");
            tool.setPathSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}");
            tool.setQuerySchema("{\"type\":\"object\",\"properties\":{\"verbose\":{\"type\":\"boolean\"}}}");
            return Collections.singletonList(tool);
        }
    }
                
    private String openApiJson() {
        return "{}";
    }

    private void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
