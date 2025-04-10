package demo.ai.mcp;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.mcp.client.McpClientWrapper;

import java.util.Map;

/**
 * @author noear 2025/4/10 created
 */
@Slf4j
public class McpClientDemo {
    public static void main(String[] args) throws Exception {
        McpClientWrapper mcpClient = new McpClientWrapper("http://localhost:8080", "/mcp/sse");

        String response = mcpClient.callToolAsText("getWeather", Map.of("location", "杭州"));

        System.out.println("------------------");
        System.out.println(response);
    }
}