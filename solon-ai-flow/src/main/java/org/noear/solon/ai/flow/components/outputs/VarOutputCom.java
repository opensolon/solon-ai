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

import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.generate.GenerateResponse;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 变量输出组件
 *
 * @author noear
 * @since 3.3
 */
@Component("VarOutput")
public class VarOutputCom extends AbsAiComponent implements AiIoComponent {
    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node);

        data = getInputAsString(data);

        setOutput(context, node, data);
    }

    public static String getInputAsString(Object data) throws Throwable {
        final StringBuilder buf = new StringBuilder();

        if (data instanceof Publisher) {
            AtomicReference<Throwable> errReference = new AtomicReference<>();
            Flux.from((Publisher<ChatResponse>) data)
                    .filter(resp -> resp.hasChoices())
                    .doOnNext(resp -> {
                        buf.append(resp.getMessage().getContent());
                    })
                    .doOnError(err -> {
                        errReference.set(err);
                    })
                    .then()
                    .block();

            if (errReference.get() != null) {
                throw errReference.get();
            }
        } else if (data instanceof ChatResponse) {
            ChatResponse resp = (ChatResponse) data;

            buf.append(resp.getMessage().getContent());
        } else if (data instanceof GenerateResponse) {
            GenerateResponse resp = (GenerateResponse) data;

            buf.append(resp.getContent().getValue());
        } else {
            buf.append(data);
        }

        return buf.toString();
    }
}
