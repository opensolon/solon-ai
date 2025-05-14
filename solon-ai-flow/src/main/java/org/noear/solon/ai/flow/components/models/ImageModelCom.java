package org.noear.solon.ai.flow.components.models;

import java.util.List;
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
import org.noear.solon.ai.image.ImageConfig;
import org.noear.solon.ai.image.ImageModel;
import org.noear.solon.ai.image.ImageRequest;
import org.noear.solon.ai.image.ImageResponse;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 聊天模型组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ChatModel")
public class ImageModelCom extends AbsAiComponent implements AiIoComponent, AiPropertyComponent {
    //私有元信息
    static final String META_SYSTEM_PROMPT = "systemPrompt";
    static final String META_STREAM = "stream";
    static final String META_IMAGE_CONFIG = "imageConfig";
    static final String META_TOOL_PROVIDER = "toolProviders";


    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        //构建聊天模型（预热后，会缓存住）
        ImageModel imageModel = (ImageModel) node.attachment;
        if (imageModel == null) {
            ImageConfig imageConfig = ONode.load(node.getMeta(META_IMAGE_CONFIG)).toObject(ImageConfig.class);
            assert imageConfig != null;
            ImageModel.Builder imageModelBuilder = ImageModel.of(imageConfig);

            imageModel = imageModelBuilder.build();
            node.attachment = imageModel;
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
        Object data = getInput(context, node);
        Assert.notNull(data, "ImageModel input is null");
        if (data instanceof String) {
            chatSession.addMessage(ChatMessage.ofUser((String) data));
        } else if (data instanceof ChatMessage) {
            chatSession.addMessage((ChatMessage) data);
        } else if (data instanceof ChatPrompt) {
            chatSession.addMessage(((ChatPrompt) data).getMessages());
        } else {
            throw new IllegalArgumentException("Unsupported data type: " + data.getClass());
        }

        ImageResponse resp = imageModel.prompt((String)data).call();
        setOutput(context, node, resp);
    }
}