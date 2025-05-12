package org.noear.solon.ai.flow.models;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.ChatSessionDefault;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.flow.AiTaskComponent;
import org.noear.solon.ai.flow.Attrs;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * @author noear 2025/5/12 created
 */
@Component("Llm")
public class LlmCom implements AiTaskComponent {
    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        String input_name = node.getMetaOrDefault(Attrs.ATTR_INPUT, Attrs.ATTR_IO_DEFAULT);
        String output_name = node.getMetaOrDefault(Attrs.ATTR_OUTPUT, Attrs.ATTR_IO_DEFAULT);

        ChatConfig chatConfig = ONode.load(node.getMeta("config")).toObject(ChatConfig.class);
        assert chatConfig != null;

        ChatModel chatModel = (ChatModel) node.attachment;
        if (chatModel == null) {
            chatModel = ChatModel.of(chatConfig).build();
            node.attachment = chatModel;
        }

        ChatSession chatSession = context.computeIfAbsent("Session", k -> new ChatSessionDefault());

        if (Utils.isEmpty(chatSession.getMessages())) {
            String systemPrompt = node.getMeta("systemPrompt");
            if (Utils.isNotEmpty(systemPrompt)) {
                chatSession.addMessage(ChatMessage.ofSystem(systemPrompt));
            }
        }

        String message = context.get(input_name);
        chatSession.addMessage(ChatMessage.ofUser(message));

        String rst = chatModel.prompt(chatSession).call().getMessage().getContent();
        context.put(output_name, rst);
    }

    @Override
    public String getDescription() {
        return "";
    }
}