package demo.ai.mcp.client;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.mcp.client.McpClientWrapper;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.web.sse.SseEvent;
import reactor.core.publisher.Flux;

/**
 * @author noear 2025/4/10 created
 */
@Slf4j
@Controller
public class McpClientDemo {
    @Inject
    ChatModel chatModel;

    @Inject
    McpClientWrapper mcpClient;

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("mcp/test")
    public Flux<ChatMessage> mcpTest() {
        return Flux.from(chatModel
                        .prompt("今天杭州的天气情况？")
                        .options(options -> {
                            //转为工具集合用于绑定
                            options.toolsAdd(mcpClient.toTools());
                        })
                        .stream())
                .map(resp -> resp.getMessage());
    }
}