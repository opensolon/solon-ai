package lab.ai.mcp.debug.server;

import org.noear.solon.ai.annotation.PromptMapping;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.mcp.McpChannel;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.DateUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

@McpServerEndpoint(channel = McpChannel.STREAMABLE, mcpEndpoint = "/mcp/")
public class McpServerEndpointDemo {
    @ToolMapping(description = "查询天气预报", returnDirect = true)
    public String getWeather(@Param(description = "城市位置") String location, Context ctx) {
        System.out.println("------------: sessionId: " + ctx.sessionId());

        ctx.realIp();

        return "晴，14度";
    }

    @ToolMapping(description = "查询天气预报异步", returnDirect = true)
    public CompletableFuture<String> getWeatherAsync(@Param(description = "城市位置") String location, Context ctx) {
        System.out.println("------------: sessionId: " + ctx.sessionId());

        ctx.realIp();

        return CompletableFuture.completedFuture("晴，14度");
    }

    @ToolMapping(description = "获取订单")
    public CompletableFuture<OrderInfo> getOrderInfo(@Param(description = "订单Id") long orderId) throws Exception{
        OrderInfo orderInfo = new OrderInfo();
        orderInfo.setId(orderId);
        orderInfo.setTitle("order-" +orderId);
        orderInfo.setCreated(DateUtil.parse("2030-01-01 01:01"));

        return CompletableFuture.completedFuture(orderInfo);
    }




    @ResourceMapping(uri = "config://app-version", description = "获取应用版本号", mimeType = "text/config")
    public String getAppVersion() {
        return "v3.2.0";
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