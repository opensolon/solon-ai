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
package org.noear.solon.ai.flow.components;

import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * Ai 输入输出组件基类
 *
 * @author noear
 * @since 3.3
 */
public interface AiIoComponent extends AiComponent {
    /**
     * 获取组件数据输入名字（有默认，可以配置）
     */
    default String getInputName(Node node) {
        return node.getMetaOrDefault(Attrs.META_INPUT, Attrs.CTX_MESSAGE);
    }

    /**
     * 获取组件附件名
     */
    default String getAttachmentName(Node node) {
        return node.getMetaOrDefault(Attrs.META_ATTACHMENT, Attrs.CTX_ATTACHMENT);
    }

    /**
     * 获取组件数据输出名字（有默认，可以配置）
     */
    default String getOutputName(Node node) {
        return node.getMetaOrDefault(Attrs.META_OUTPUT, Attrs.CTX_MESSAGE);
    }


    /**
     * 获取组件数据输入
     */
    default Object getInput(FlowContext context, Node node) throws Throwable {
        String input_name = getInputName(node);
        Object input = node.getMeta(input_name);

        if (input == null) {
            return context.get(input_name);
        } else {
            return input;
        }
    }

    /**
     * 获取组件附件
     */
    default Object getAttachment(FlowContext context, Node node) throws Throwable {
        String attachment_name = getAttachmentName(node);
        Object attachment = context.get(attachment_name);

        if (attachment == null) {
            return node.getMeta(attachment_name);
        } else {
            return attachment;
        }
    }

    /**
     * 设置组件附件
     */
    default void setAttachment(FlowContext context, Node node, Object data) throws Throwable {
        String attachment_name = getAttachmentName(node);
        context.put(attachment_name, data);
    }

    /**
     * 获取组件数据输出
     */
    default void setOutput(FlowContext context, Node node, Object data) throws Throwable {
        String output_name = getOutputName(node);
        context.put(output_name, data);
    }
}