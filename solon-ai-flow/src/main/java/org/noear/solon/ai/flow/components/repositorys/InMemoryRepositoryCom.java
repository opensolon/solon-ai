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
package org.noear.solon.ai.flow.components.repositorys;

import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.InMemoryRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

/**
 * 知识库组件
 *
 * @author noear
 * @since 3.3
 */
@Component("InMemoryRepository")
public class InMemoryRepositoryCom extends AbsRepositoryCom {
    @Override
    public RepositoryStorable getRepository(FlowContext context, Node node) throws Throwable {
        //构建知识库（预热后，会缓存住）
        RepositoryStorable repository = (RepositoryStorable) node.attachment;
        if (repository == null) {
            //构建
            EmbeddingModel embeddingModel = (EmbeddingModel) getProperty(context, Attrs.PROP_EMBEDDING_MODEL);

            Assert.notNull(embeddingModel, "Missing embeddingModel property!");

            repository = new InMemoryRepository(embeddingModel);
            node.attachment = repository;

            initRepository(repository, context, node);
        }

        return repository;
    }
}