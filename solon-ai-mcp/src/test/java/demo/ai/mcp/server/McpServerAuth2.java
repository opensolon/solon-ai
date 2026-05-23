package demo.ai.mcp.server;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;

import java.util.List;
import java.util.Map;

/**
 * @author noear 2025/7/1 created
 */
@McpServerEndpoint(channel = McpChannel.STREAMABLE, sseEndpoint = "/auth2/sse")
public class McpServerAuth2 implements ServerTransportSecurityValidator {
    //@AuthRoles("1")
    @ToolMapping(description = "查询天气预报")
    public String getWeather(@Param(description = "城市位置") String location) {
        return "晴，14度";
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        if (headers.containsKey("role")) {
            if ("1".equals(headers.get("role").get(0))) {
                return;
            }
        }

        throw new ServerTransportSecurityException(401, "没有权限");
    }
}