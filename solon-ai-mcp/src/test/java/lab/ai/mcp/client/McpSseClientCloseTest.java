package lab.ai.mcp.client;

import org.noear.liquor.eval.Maps;
import org.noear.solon.ai.mcp.client.McpClientProvider;

/**
 * @author noear 2025/4/22 created
 */
public class McpSseClientCloseTest {
    public static void main(String[] args) throws Exception {
        McpClientProvider toolProvider = McpClientProvider.builder()
                .apiUrl("http://localhost:8081/sse")
                .build();


        call(toolProvider);

        //关闭后即退出
        toolProvider.close();
    }

    private static void call(McpClientProvider toolProvider) {
        try {
            String response = toolProvider.callToolAsText("getWeather", Maps.of("location", "杭州")).getContent();
            assert response != null;
            System.err.println(response);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}