package org.noear.solon.ai.flow.components.outputs;

import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.flow.components.AbstractDataCom;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.PrintStream;

/**
 * 文本输出组件
 *
 * @author noear
 * @since 3.3
 */
@Component("TextOutput")
public class TextOutputCom extends AbstractDataCom {
    @Override
    public void setDataOutput(FlowContext context, Node node, Object data) throws Throwable {
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
        } else {
            out.println(data);
        }
    }

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        Object data = getDataInput(context, node);

        setDataOutput(context, node, data);
    }
}
