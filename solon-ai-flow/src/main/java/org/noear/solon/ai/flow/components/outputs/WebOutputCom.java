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

import org.noear.solon.Utils;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.generate.GenerateResponse;
import org.noear.solon.ai.image.ImageResponse;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.expression.snel.SnEL;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 文本输出组件
 *
 * @author noear
 * @since 3.3
 */
@Component("WebOutput")
public class WebOutputCom extends AbsAiComponent implements AiIoComponent {
    //私有元信息
    static final String META_FORMAT = "format";

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node);

        Context ctx = Context.current();

        //格式化输出
        String format = node.getMetaAsString(META_FORMAT);
        if (Utils.isEmpty(format)) {
            //没有格式符
            final StringBuilder buf = new StringBuilder();

            if (data instanceof Publisher) {
                AtomicReference<Throwable> errReference = new AtomicReference<>();
                Flux.from((Publisher<ChatResponse>) data)
                        .filter(resp -> resp.hasChoices())
                        .doOnNext(resp -> {
                            buf.append(resp.getMessage().getContent());

                            try {
                                ctx.render(resp.getMessage());
                                ctx.output("\n");
                                ctx.flush();
                            } catch (Throwable ex) {
                                throw new RuntimeException(ex);
                            }
                        }).doOnError(err -> {
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

                ctx.render(resp.getMessage());
                ctx.output("\n");
                ctx.flush();
            } else if (data instanceof ImageResponse) {
                ImageResponse resp = (ImageResponse) data;

                buf.append(resp.getImage().getUrl());

                ctx.render(ChatMessage.ofAssistant("![](" + resp.getImage().getUrl() + ")"));
                ctx.output("\n");
                ctx.flush();
            } else if (data instanceof GenerateResponse) {
                GenerateResponse resp = (GenerateResponse) data;

                buf.append(resp.getContent().getValue());

                ctx.render(ChatMessage.ofAssistant("![](" + resp.getContent().getValue() + ")"));
                ctx.output("\n");
                ctx.flush();
            } else {
                buf.append(data);

                ctx.render(data);
                ctx.output("\n");
                ctx.flush();
            }

            data = buf.toString();

            setOutput(context, node, data);
        } else {
            //有格式符
            data = VarOutputCom.getInputAsString(data);

            setOutput(context, node, data);

            String formatted = SnEL.evalTmpl(format, context.model());
            ctx.render(formatted);
            ctx.output("\n");
            ctx.flush();
        }
    }
}