package demo.ai.core;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;

public class RoleDemo {
    @Test
    public void case1() throws Exception {
        ChatModel chatModel = ChatModel.of("")
                .provider("aaa")
                .model("bbb")
                .role("ccc")
                .instruction("ddd")
                .build();

        chatModel.prompt("xxx")
                .options(o -> {
                    o.role("ccc-2");
                    o.instruction("ddd-2");
                })
                .call();
    }
}