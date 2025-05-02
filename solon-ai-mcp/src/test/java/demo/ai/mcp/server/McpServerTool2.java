package demo.ai.mcp.server;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.annotation.PromptMapping;
import org.noear.solon.ai.annotation.ResourceMapping;
import org.noear.solon.ai.mcp.server.McpServerContext;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Param;
import org.noear.solon.core.handle.Context;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * @author noear 2025/4/8 created
 */
@Mapping("/demo2/sse")
@Controller
@McpServerEndpoint(sseEndpoint = "/demo2/sse")
public class McpServerTool2 {
    @ToolMapping(description = "查询天气预报")
    public String get_weather(@Param(description = "城市位置") String location, Context ctx) {
        System.out.println("------------: sessionId: " + ctx.sessionId());

        return "晴，14度";
    }

    @ResourceMapping(uri = "config://app-version", description = "获取应用版本号")
    public String get_app_version() {
        return "v3.2.0";
    }

    @ResourceMapping(uri = "db://users/{user_id}/email", description = "根据用户ID查询邮箱")
    public String get_email(@Param(description = "用户Id") String user_id) {
        return user_id + "@example.com";
    }

    @PromptMapping(description = "生成关于某个主题的提问")
    public Collection<ChatMessage> ask_question(@Param(description = "主题") String topic) {
        return Arrays.asList(
                ChatMessage.ofUser("请解释一下'" + topic + "'的概念？")
        );
    }

    @PromptMapping(description = "初始化错误调试会话")
    public Collection<ChatMessage> debug_session(@Param(description = "错误信息") String error) {
        return Arrays.asList(
                ChatMessage.ofUser("遇到错误：" + error),
                ChatMessage.ofAssistant("正在排查，请描述复现步骤。")
        );
    }
}