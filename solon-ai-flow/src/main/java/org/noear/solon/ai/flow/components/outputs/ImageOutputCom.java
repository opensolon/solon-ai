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
