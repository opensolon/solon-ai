package lab.ai.mcp.client;

import org.noear.liquor.eval.Maps;
import org.noear.solon.ai.mcp.client.McpClientToolProvider;
import org.noear.solon.core.util.RunUtil;

/**
 * @author noear 2025/4/22 created
 */
public class McpSseClientRetryTest {
    public static void main(String[] args) throws Exception {
        McpClientToolProvider toolProvider = McpClientToolProvider.builder()
                .apiUrl("http://localhost:8081/sse")
                .build();


        call(toolProvider);


        RunUtil.delayAndRepeat(() -> {
            call(toolProvider);
        }, 1000);

        System.in.read();
    }

    private static void call(McpClientToolProvider toolProvider) {
        try {
            String response = toolProvider.callToolAsText("getWeather", Maps.of("location", "杭州")).getContent();
            assert response != null;
            System.err.println(response);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}