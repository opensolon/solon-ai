package org.noear.solon.ai.flow.components.outputs;

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.image.ImageResponse;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.concurrent.CountDownLatch;

/**
 * 文本输出组件
 *
 * @author noear
 * @since 3.3
 */
@Component("TextOutput")
public class TextOutputCom extends AbsAiComponent implements AiIoComponent {
    //私有元信息
    static final String META_PREFIX = "prefix";

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node);

        final StringBuilder buf = new StringBuilder();

        if (data instanceof Publisher) {
            CountDownLatch latch = new CountDownLatch(1);
            Flux.from((Publisher<ChatResponse>) data)
                    .filter(resp -> resp.hasChoices())
                    .map(resp -> resp.getMessage())
                    .doOnNext(msg -> {
                        buf.append(msg.getContent());
                    })
                    .doOnComplete(() -> {
                        latch.countDown();
                    })
                    .doOnError(err -> {
                        err.printStackTrace();
                        latch.countDown();
                    })
                    .subscribe();

            latch.await();
        } else if (data instanceof ChatResponse) {
            ChatResponse resp = (ChatResponse) data;
            data = resp.getMessage().getContent();

            buf.append(data);
        } else if (data instanceof ImageResponse) {
            ImageResponse resp = (ImageResponse) data;
            data = resp.getImage().getUrl();

            buf.append(data);
        } else {
            buf.append(data);
        }

        data = buf.toString();
        String prefix = node.getMeta(META_PREFIX);
        if (Utils.isEmpty(prefix)) {
            System.out.println(data);
        } else {
            System.out.println(prefix + data);
        }

        setOutput(context, node, data);
    }
}
