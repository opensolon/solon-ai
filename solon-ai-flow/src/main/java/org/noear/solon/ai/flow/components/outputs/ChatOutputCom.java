package org.noear.solon.ai.flow.components.outputs;

import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.image.ImageResponse;
import org.noear.solon.ai.media.Image;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * 聊天输出组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ChatOutput")
public class ChatOutputCom extends AbsAiComponent implements AiIoComponent {

    @Override
    public void setOutput(FlowContext context, Node node, Object data) throws Throwable {
        final Context ctx = Context.current();

        if (data instanceof Publisher) {
            data = Flux.from((Publisher<ChatResponse>) data)
                    .filter(resp -> resp.hasChoices())
                    .map(resp -> resp.getMessage());

            ctx.contentType(MimeType.TEXT_EVENT_STREAM_VALUE);
            ctx.returnValue(data);
        } else if (data instanceof ChatResponse) {
            ChatResponse resp = (ChatResponse) data;
            data = resp.getMessage();

            ctx.contentType(MimeType.TEXT_EVENT_STREAM_VALUE);
            ctx.returnValue(data);
        } else if (data instanceof ImageResponse) {
            ImageResponse resp = (ImageResponse) data;
            data = ChatMessage.ofAssistant("![](" + resp.getImage().getUrl() + ")");

            ctx.contentType(MimeType.TEXT_EVENT_STREAM_VALUE);
            ctx.returnValue(data);
        } else {
            if (data instanceof String) {
                ctx.output((String) data);
            } else {
                ctx.render(data);
            }
        }
    }

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node);

        setOutput(context, node, data);
    }
}