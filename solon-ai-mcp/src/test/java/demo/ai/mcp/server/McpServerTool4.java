package demo.ai.mcp.server;

import org.noear.solon.ai.annotation.PromptMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.ai.media.Image;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;

import java.util.Arrays;
import java.util.Collection;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(sseEndpoint = "/demo4/sse")
public class McpServerTool4 {
    @ToolMapping(description = "查询城市降雨量")
    public String getRainfall(@Param(name = "location", description = "城市位置") String location) {
        if (location == null) {
            throw new IllegalStateException("arguments location is null (Assistant recognition failure)");
        }

        return "555毫米";
    }

    @ToolMapping(description = "杭州的假日景点介绍")
    public String spotIntro() {
        return "西湖，良渚遗址";
    }

    @PromptMapping(description = "拆解测试")
    public Collection<ChatMessage> splitMessage() {
        String imageUrl = "https://solon.noear.org/img/369a9093918747df8ab0a5ccc314306a.png";

        return Arrays.asList(
                ChatMessage.ofUser("这图里有方块吗？", Image.ofUrl(imageUrl))
        );
    }
}