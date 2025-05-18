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

import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.handle.Context;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 文本输入组件
 *
 * @author noear
 * @since 3.3
 */
@Component("WebInput")
public class WebInputCom extends AbsAiComponent implements AiIoComponent {
    @Override
    public Object getInput(FlowContext context, Node node) throws Throwable {
        String input_name = getInputName(node);
        return Context.current().param(input_name);
    }

    @Override
    public Object getAttachment(FlowContext context, Node node) throws Throwable {
        String attachment_name = getAttachmentName(node);

        return Context.current().file(attachment_name);
    }

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        Object data = getInput(context, node);
        Object attachment = getAttachment(context, node);

        setOutput(context, node, data);
        setAttachment(context, node, attachment);
    }
}