package lab.ai.mcp.debug.server;

import org.noear.solon.Solon;
import org.noear.solon.annotation.Import;

@Import(profiles = "app-server.yml")
public class McpApp {
    public static void main(String[] args) {
        Solon.start(McpApp.class, args);
    }
}
