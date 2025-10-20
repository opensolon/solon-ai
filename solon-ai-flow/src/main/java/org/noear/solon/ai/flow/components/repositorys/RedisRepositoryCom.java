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

import org.noear.redisx.RedisClient;
import org.noear.snack4.ONode;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.RedisRepository;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Condition;
import org.noear.solon.core.util.Assert;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;

import java.util.Properties;

/**
 * 知识库组件
 *
 * @author noear
 * @since 3.3
 */
@Condition(onClass = RedisRepository.class)
@Component("RedisRepository")
public class RedisRepositoryCom extends AbsRepositoryCom {
    static final String META_REDIS_CONFIG = "redisConfig";

    @Override
    public RepositoryStorable getRepository(FlowContext context, Node node) throws Throwable {
        RepositoryStorable repository = (RepositoryStorable) node.attachment;
        if (repository == null) {
            //构建
            EmbeddingModel embeddingModel = (EmbeddingModel) getProperty(context, Attrs.PROP_EMBEDDING_MODEL);

            Assert.notNull(embeddingModel, "Missing embeddingModel property!");


            Properties redisProperties = ONode.ofBean(node.getMeta(META_REDIS_CONFIG))
                    .toBean(Properties.class);

            RedisClient redisClient = new RedisClient(redisProperties);

            repository = RedisRepository.builder(embeddingModel, redisClient.jedis()).build();
            node.attachment = repository;

            //首次加载文档源
            initRepository(repository, context, node);
        }

        return repository;
    }
}