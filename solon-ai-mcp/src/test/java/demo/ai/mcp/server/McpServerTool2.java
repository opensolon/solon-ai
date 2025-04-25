package demo.ai.mcp.server;

import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;

/**
 * @author noear 2025/4/8 created
 */
@Mapping("/demo2/sse")
@Controller
@McpServerEndpoint(sseEndpoint = "/demo2/sse")
public class McpServerTool2 {
    //
    // 建议开启编译参数：-parameters （否则，要再配置参数的 name）
    //
    @Mapping(description = "查询天气预报")
    public String get_weather(@Param(description = "城市位置") String location) {
        return "晴，14度";
    }
}