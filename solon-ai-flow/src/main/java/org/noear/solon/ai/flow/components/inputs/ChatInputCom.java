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
package org.noear.solon.ai.flow.components.inputs;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.media.Image;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.UploadedFile;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 聊天输入组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ChatInput")
public class ChatInputCom extends AbsAiComponent implements AiIoComponent {
    @Override
    public Object getInput(FlowContext context, Node node) throws Exception {
        String input_name = getInputName(node);
        Context ctx = Context.current();

        if (ctx == null) {
            return null;
        } else {
            return ctx.param(input_name);
        }
    }

    @Override
    public Object getAttachment(FlowContext context, Node node) throws Throwable {
        String attachment_name = getAttachmentName(node);
        Context ctx = Context.current();

        if (ctx == null) {
            return null;
        } else {
            return ctx.file(attachment_name);
        }
    }

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        //配置优先；上下文次之
        Object input = AiIoComponent.super.getInput(context, node);
        if (input == null) {
            input = this.getInput(context, node);
        }

        Object attachment = AiIoComponent.super.getAttachment(context, node);
        if (attachment == null) {
            attachment = getAttachment(context, node);
        }

        if (input == null && attachment != null) {
            throw new IllegalArgumentException("The input and attachment is null");
        }

        Object output = build(input, attachment);

        setOutput(context, node, output);
    }

    private Object build(Object promptObj, Object attachmentObj) throws Throwable {
        String prompt = (String) promptObj;
        UploadedFile attachment = (UploadedFile) attachmentObj;

        if (attachment == null) {
            return ChatMessage.ofUser(prompt);
        } else if (prompt == null) {
            return ChatMessage.ofUser(Image.ofBase64(
                    attachment.getContentAsBytes(),
                    attachment.getContentType()));
        } else {
            return ChatMessage.ofUser(prompt, Image.ofBase64(
                    attachment.getContentAsBytes(),
                    attachment.getContentType()));
        }
    }
}