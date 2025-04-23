package demo.ai.mcp.server_resume;

import org.noear.solon.Solon;
import org.noear.solon.annotation.Import;
import org.noear.solon.scheduling.annotation.EnableScheduling;

/**
 * @author noear 2025/4/23 created
 */
@EnableScheduling
@Import(profiles = "app-server.yml")
public class McpServerApp {
    public static void main(String[] args) {
        Solon.start(McpServerApp.class, args);
    }
}
