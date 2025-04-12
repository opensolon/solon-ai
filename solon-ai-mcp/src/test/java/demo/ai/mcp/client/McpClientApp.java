package demo.ai.mcp.client;

import org.noear.solon.Solon;
import org.noear.solon.annotation.Import;

/**
 * @author noear 2025/4/12 created
 */
@Import(profiles = "app-client.yml")
public class McpClientApp {
    public static void main(String[] args) {
        Solon.start(McpClientApp.class, args);
    }
}
