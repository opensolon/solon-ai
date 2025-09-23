package demo.ai.mcp.server;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Mapping;
import org.noear.solon.annotation.Produces;
import org.noear.solon.core.util.MimeType;
import reactor.core.publisher.Flux;

/**
 * @author noear 2025/4/14 created
 */
@Slf4j
@Controller
public class ChatController {
    @Inject
    ChatModel chatModel;

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("/test/stream")
    public Flux<String> stream(String prompt) throws Exception {
        return Flux.from(chatModel.prompt(prompt).stream())
                //.subscribeOn(Schedulers.boundedElastic()) //加这个打印效果更好
                .filter(resp -> resp.hasContent())
                .map(resp -> resp.getContent())
                .concatWithValues("[DONE]"); //有些前端框架，需要 [DONE] 实识用作识别
    }
}