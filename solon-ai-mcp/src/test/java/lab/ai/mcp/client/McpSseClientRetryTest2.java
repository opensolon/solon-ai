package lab.ai.mcp.client;

import org.noear.solon.ai.mcp.client.McpClientProvider;

import java.util.Collections;

/**
 * @author noear 2025/4/22 created
 */
public class McpSseClientRetryTest2 {
    public static void main(String[] args) throws Exception {
        McpClientProvider toolProvider = McpClientProvider.builder()
                .url("http://localhost:8081/sse")
                .build();


        call(toolProvider);

        System.in.read();
    }

    private static void call(McpClientProvider toolProvider) {
        try {
            String response = toolProvider.callToolAsText("getWeather", Collections.singletonMap("location", "杭州")).getContent();
            assert response != null;
            System.err.println(response);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}