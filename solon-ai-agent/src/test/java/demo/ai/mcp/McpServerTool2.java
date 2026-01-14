package demo.ai.mcp;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import org.noear.solon.Solon;
import org.noear.solon.ai.annotation.PromptMapping;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * @author noear 2025/4/8 created
 */
@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/mcp")
public class McpServerTool2 {
    public static void main(String[] args){
        Solon.start(McpServerTool2.class, args);
    }

    @ToolMapping(description = "查询天气预报", returnDirect = true)
    public CompletableFuture<String> getWeather(@Param(description = "城市位置") String location, String userId, Context ctx) {
        System.out.println("------------: sessionId: " + ctx.sessionId());

        ctx.realIp();

        if ("aaa".equals(userId)) {
            return CompletableFuture.completedFuture("晴，14度! 风很大! " + userId);
        } else {
            return CompletableFuture.completedFuture("晴，14度! ");
        }
    }

    @ResourceMapping(uri = "config://app-version", description = "获取应用版本号", mimeType = "text/config")
    public Mono<String> getAppVersion(McpAsyncServerExchange exchange) {
        //测试 exchange 能不能注入，和 Publisher<X> 返回
        if (exchange == null) {
            return Mono.just("1.0");
        } else {
            return Mono.just("v3.2.0");
        }
    }

    @ResourceMapping(uri = "db://users/{user_id}/email", description = "根据用户ID查询邮箱")
    public String getEmail(@Param(description = "用户Id") String user_id) {
        return user_id + "@example.com";
    }

    @PromptMapping(description = "生成关于某个主题的提问")
    public Collection<ChatMessage> askQuestion(@Param(description = "主题") String topic) {
        return Arrays.asList(
                ChatMessage.ofUser("请解释一下'" + topic + "'的概念？")
        );
    }

    @PromptMapping(description = "初始化错误调试会话")
    public Collection<ChatMessage> debugSession(@Param(description = "错误信息") String error) {
        return Arrays.asList(
                ChatMessage.ofUser("遇到错误：" + error),
                ChatMessage.ofAssistant("正在排查，请描述复现步骤。")
        );
    }
}