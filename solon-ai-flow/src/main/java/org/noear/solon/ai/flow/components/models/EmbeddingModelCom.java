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
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiPropertyComponent;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.annotation.Component;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 嵌入模型组件
 *
 * @author noear
 * @since 3.3
 */
@Component("EmbeddingModel")
public class EmbeddingModelCom extends AbsAiComponent implements AiPropertyComponent {
    static final String META_EMBEDDING_CONFIG = "embeddingConfig";

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        EmbeddingModel embeddingModel = (EmbeddingModel) node.attachment;

        if (embeddingModel == null) {
            //构建
            EmbeddingConfig embeddingConfig = ONode.load(node.getMeta(META_EMBEDDING_CONFIG)).toObject(EmbeddingConfig.class);
            embeddingModel = EmbeddingModel.of(embeddingConfig).build();
        }

        setProperty(context, Attrs.PROP_EMBEDDING_MODEL, embeddingModel);
    }
}