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
package org.noear.solon.ai.flow.components.models;

import org.noear.snack.ONode;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.flow.components.AiPropertyComponent;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.image.ImageConfig;
import org.noear.solon.ai.image.ImageModel;
import org.noear.solon.ai.image.ImageResponse;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 聊天模型组件
 *
 * @author noear
 * @since 3.3
 */
@Component("ImageModel")
public class ImageModelCom extends AbsAiComponent implements AiIoComponent, AiPropertyComponent {
    //私有元信息
    static final String META_IMAGE_CONFIG = "imageConfig";


    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        //构建聊天模型（预热后，会缓存住）
        ImageModel imageModel = (ImageModel) node.attachment;
        if (imageModel == null) {
            ImageConfig imageConfig = ONode.load(node.getMeta(META_IMAGE_CONFIG)).toObject(ImageConfig.class);
            assert imageConfig != null;
            ImageModel.Builder imageModelBuilder = ImageModel.of(imageConfig);

            imageModel = imageModelBuilder.build();
            node.attachment = imageModel;
        }

        //转入上下文
        setProperty(context, Attrs.PROP_IMAGE_MODEL, imageModel);

        //获取数据
        Object data = getInput(context, node);

        if (data != null) {
            //数据检测
            String promptStr = null;
            if (data instanceof String) {
                promptStr = (String) data;
            } else if (data instanceof ChatMessage) {
                promptStr = ((ChatMessage) data).getContent();
            } else {
                throw new IllegalArgumentException("Unsupported data type: " + data.getClass());
            }

            ImageResponse resp = imageModel.prompt(promptStr).call();
            setOutput(context, node, resp);
        }
    }
}