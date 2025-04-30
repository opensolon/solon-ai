package demo.ai.mcp.server;

import org.noear.solon.ai.chat.annotation.ToolMapping;
import org.noear.solon.ai.chat.annotation.ToolParam;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.mcp.annotation.PromptMapping;
import org.noear.solon.ai.mcp.annotation.ResourceMapping;
import org.noear.solon.ai.mcp.server.annotation.McpServerEndpoint;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;

import java.util.Arrays;
import java.util.List;

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
    @ToolMapping(description = "查询天气预报")
    public String get_weather(@ToolParam(description = "城市位置") String location) {
        return "晴，14度";
    }

    @ResourceMapping(uri = "config://app-version", description = "获取应用版本号")
    public String get_app_version() {
        return "v2.1.0";
    }

    @ResourceMapping(uri = "db://users/{user_id}/email", description = "根据用户ID查询邮箱")
    public String get_email(String user_id) {
        return user_id + "@example.com";
    }

    @PromptMapping(description = "生成关于某个主题的提问")
    public String ask_question(String topic) {
        return "请解释一下'" + topic + "'的概念？";
    }

    @PromptMapping(description = "初始化错误调试会话")
    public List<ChatMessage> debug_session(String error) {
        return Arrays.asList(
                ChatMessage.ofUser("遇到错误：" + error),
                ChatMessage.ofAssistant("正在排查，请描述复现步骤。")
        );
    }
}