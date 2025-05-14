package org.noear.solon.ai.flow.components.outputs;

import java.io.PrintStream;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.image.ImageResponse;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * 文本输出组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ImageOutput")
public class ImageOutputCom extends AbsAiComponent implements AiIoComponent {
    @Override
    public void setOutput(FlowContext context, Node node, Object data) throws Throwable {
        final PrintStream out = System.out;

        if (data instanceof Publisher) {
            Flux.from((Publisher<ChatResponse>) data)
                    .filter(resp -> resp.hasChoices())
                    .map(resp -> resp.getMessage())
                    .doOnNext(msg -> {
                        out.println(msg);
                    })
                    .subscribe();

        } else if (data instanceof ChatResponse) {
            ChatResponse resp = (ChatResponse) data;
            data = resp.getMessage();

            out.println(data);
        } else if (data instanceof ImageResponse) {
            ImageResponse resp = (ImageResponse) data;
            data = resp.getImage();
        } else {
            out.println(data);
        }
    }

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node);

        setOutput(context, node, data);
    }
}
