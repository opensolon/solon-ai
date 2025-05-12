package org.noear.solon.ai.flow.components.models;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.ChatSessionDefault;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.flow.components.AbstractCom;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 聊天模型组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ChatModel")
public class ChatModelCom extends AbstractCom {
    //私有元信息
    static final String META_SYSTEM_PROMPT = "systemPrompt";
    static final String META_STREAM = "stream";


    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        //构建模板（已缓存）
        ChatModel chatModel = (ChatModel) node.attachment;
        if (chatModel == null) {
            ChatConfig chatConfig = ONode.load(node.getMeta(Attrs.META_CONFIG)).toObject(ChatConfig.class);
            assert chatConfig != null;
            chatModel = ChatModel.of(chatConfig).build();
            node.attachment = chatModel;
        }

        //构建会话（可在发起流程时传递）
        ChatSession chatSession = context.computeIfAbsent(Attrs.CTX_SESSION, k -> new ChatSessionDefault());

        if (Utils.isEmpty(chatSession.getMessages())) {
            String systemPrompt = node.getMeta(META_SYSTEM_PROMPT);
            if (Utils.isNotEmpty(systemPrompt)) {
                chatSession.addMessage(ChatMessage.ofSystem(systemPrompt));
            }
        }

        //获取数据
        Object data = getInput(context, node, null);
        if (data instanceof String) {
            chatSession.addMessage(ChatMessage.ofUser((String) data));
        } else if (data instanceof ChatMessage) {
            chatSession.addMessage((ChatMessage) data);
        } else if (data instanceof ChatPrompt) {
            chatSession.addMessage(((ChatPrompt) data).getMessages());
        } else {
            throw new IllegalArgumentException("Unsupported data type: " + data.getClass());
        }

        //替换数据
        data = chatModel.prompt(chatSession).call()
                .getMessage()
                .getContent();

        setOutput(context, node, data, null);
    }
}