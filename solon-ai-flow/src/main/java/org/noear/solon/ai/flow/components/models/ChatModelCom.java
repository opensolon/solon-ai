/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.flow.components.models;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.ChatSessionDefault;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.ChatPrompt;
import org.noear.solon.ai.chat.tool.MethodToolProvider;
import org.noear.solon.ai.chat.tool.ToolProvider;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.flow.components.AiPropertyComponent;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

import java.util.List;

/**
 * 聊天模型组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ChatModel")
public class ChatModelCom extends AbsAiComponent implements AiIoComponent, AiPropertyComponent {
    //私有元信息
    static final String META_SYSTEM_PROMPT = "systemPrompt";
    static final String META_STREAM = "stream";
    static final String META_CHAT_CONFIG = "chatConfig";
    static final String META_TOOL_PROVIDER = "toolProviders";


    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        //构建聊天模型（预热后，会缓存住）
        ChatModel chatModel = (ChatModel) node.attachment;
        if (chatModel == null) {
            ChatConfig chatConfig = ONode.load(node.getMeta(META_CHAT_CONFIG)).toObject(ChatConfig.class);
            assert chatConfig != null;
            ChatModel.Builder chatModelBuilder = ChatModel.of(chatConfig);


            List<String> toolProviders = node.getMeta(META_TOOL_PROVIDER);
            if (Utils.isNotEmpty(toolProviders)) {
                for (String toolProvider : toolProviders) {
                    Object tmp = ClassUtil.tryInstance(toolProvider);
                    if (tmp != null) {
                        chatModelBuilder.defaultToolsAdd(new MethodToolProvider(tmp));
                    }
                }
            }

            List toolProps = getPropertyAll(context, Attrs.PROP_TOOLS);
            if (Utils.isNotEmpty(toolProps)) {
                for (Object tmp : toolProps) {
                    if (tmp instanceof ToolProvider) {
                        chatModelBuilder.defaultToolsAdd((ToolProvider) tmp);
                    }
                }
            }

            chatModel = chatModelBuilder.build();
            node.attachment = chatModel;
        }

        //构建会话（可在发起流程时传递）
        String chatSessionKey = node.getMetaOrDefault(Attrs.META_CHAT_SESSION, Attrs.CTX_CHAT_SESSION);
        ChatSession chatSession = context.computeIfAbsent(chatSessionKey, k -> new ChatSessionDefault());

        if (Utils.isEmpty(chatSession.getMessages())) {
            String systemPrompt = node.getMeta(META_SYSTEM_PROMPT);
            if (Utils.isNotEmpty(systemPrompt)) {
                chatSession.addMessage(ChatMessage.ofSystem(systemPrompt));
            }
        }

        boolean isStream = "true".equals(node.getMeta(META_STREAM));

        //获取数据
        Object data = getInput(context, node);

        Assert.notNull(data, "ChatModel input is null");

        if (data instanceof String) {
            //字符串
            chatSession.addMessage(ChatMessage.ofUser((String) data));
        } else if (data instanceof ChatMessage) {
            //消息
            chatSession.addMessage((ChatMessage) data);
        } else if (data instanceof ChatPrompt) {
            //提示语
            chatSession.addMessage(((ChatPrompt) data).getMessages());
        } else {
            throw new IllegalArgumentException("Unsupported data type: " + data.getClass());
        }

        //替换数据
        if (isStream) {
            data = chatModel.prompt(chatSession).stream();
        } else {
            data = chatModel.prompt(chatSession).call();
        }

        setOutput(context, node, data);
    }
}