package demo.ai.mcp.server;

import demo.ai.mcp.llm.LlmUtil;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.annotation.Controller;
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

    @Produces(MimeType.TEXT_EVENT_STREAM_VALUE)
    @Mapping("/test/stream")
    public Flux<String> stream(String prompt) throws Exception {
        ChatModel chatModel = LlmUtil.getChatModel().build();

        return chatModel.prompt(prompt)
                .stream()
                .filter(e -> e.is(ChatEventType.TEXT_DELTA) && e.hasText())
                .map(ChatEvent::getText)
                //.subscribeOn(Schedulers.boundedElastic()) //加这个打印效果更好
                .concatWithValues("[DONE]"); //有些前端框架，需要 [DONE] 实识用作识别
    }
}