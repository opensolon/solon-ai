package demo.ai.mcp.server_context_path;

import org.noear.solon.Solon;
import org.noear.solon.annotation.Import;
import org.noear.solon.server.http.HttpServerConfigure;

/**
 * @author noear 2025/4/8 created
 */
@Import(profiles = "app-server2.yml")
public class McpServerApp {
    public static void main(String[] args) {
        if (Solon.app() != null) {
            if (Solon.app().source() != McpServerApp.class) {
                Solon.stopBlock();
            }
        }

        Solon.start(McpServerApp.class, args, app -> {
            app.onEvent(HttpServerConfigure.class, e -> {
                e.enableDebug(true);
            });
        });
    }
}
