package demo.ai.mcp.client;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.mcp.client.McpClientProvider;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.util.MimeType;
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
    McpClientProvider mcpClient;

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("mcp/test")
    public Flux<String> mcpTest() {
        return chatModel
                .prompt("今天杭州的天气情况？")
                .options(options -> {
                    //转为工具集合用于绑定
                    options.toolAdd(mcpClient.getTools());
                })
                .stream()
                .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
                .map(ChatEvent::getText);

    }
}