package demo.ai.core;

import org.noear.solon.ai.chat.ChatModel;

/**
 *
 * @author noear 2026/5/29 created
 *
 */
public class LlmDemo {
    public void demo() throws Throwable {
        ChatModel chatModel = ChatModel.of("").userAgent("xxx").build();

        chatModel.prompt("xxx")
                .options(o -> {
                    o.httpCustomize(http -> {
                        http.userAgent("");
                    });
                }).call();
    }
}