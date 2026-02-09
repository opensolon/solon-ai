package demo.ai.mcp.server;

import lombok.Getter;
import org.noear.solon.ai.annotation.PromptMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.ai.chat.media.ImageBlock;
import org.noear.solon.annotation.Header;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.Result;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collection;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/demo4/sse")
public class McpServerTool4 {
    @ToolMapping(description = "查询城市降雨量")
    public Mono<String> getRainfall(@Param(name = "location", description = "城市位置") String location) {
        if (location == null) {
            throw new IllegalStateException("arguments location is null (Assistant recognition failure)");
        }

        return Mono.just("555毫米");
    }

    @ToolMapping(description = "获取一个城市的特产介绍")
    public String getCity(@Param String city, String userName) {
        return city + ":" + userName;
    }

    @ToolMapping(description = "获取连接请求头")
    public String getHeader(@Header("user") String user) {
        return user;
    }

    @ToolMapping(description = "获取连接请参数")
    public String getParam(Context ctx) {
        return ctx.param("token");
    }

    @ToolMapping(description = "杭州的假日景点介绍")
    public String spotIntro() {
        return "西湖，良渚遗址";
    }

    @PromptMapping(description = "拆解测试")
    public Collection<ChatMessage> splitMessage() {
        String imageUrl = "https://solon.noear.org/img/369a9093918747df8ab0a5ccc314306a.png";

        return Arrays.asList(
                ChatMessage.ofUser("这图里有方块吗？", ImageBlock.ofUrl(imageUrl))
        );
    }

    @ToolMapping(description = "获取活动详情")
    public ActivityInfoDTO getDetails(@Param(description = "根据活动id查询", name = "activityInfo") ActivityInfoDTO activityInfo) {
        return activityInfo;
    }

    @ToolMapping(description = "获取活动详情结果")
    public Result<ActivityInfoDTO> getDetailsResult(@Param(description = "根据活动id查询", name = "activityInfo") ActivityInfoDTO activityInfo) {
        return Result.succeed(activityInfo);
    }

    @Getter
    public static class ActivityInfoDTO {
        @Param(description = "活动id")
        private String activityId;

        @Override
        public String toString() {
            return "ActivityInfoDTO{" +
                    "activityId='" + activityId + '\'' +
                    '}';
        }
    }
}