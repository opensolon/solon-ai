package org.noear.solon.ai.codecli;

import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Inject;

@Controller
public class AgentConfig {
    public ChatModel chatModel(@Inject("${solon.ai.chat.cli}") ChatConfig config) {
        return ChatModel.of(config).build();
    }
}
