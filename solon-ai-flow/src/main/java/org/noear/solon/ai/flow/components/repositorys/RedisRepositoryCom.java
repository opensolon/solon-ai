package org.noear.solon.ai.flow.components.repositorys;

import org.noear.redisx.RedisClient;
import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.flow.components.AiPropertyComponent;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentSplitter;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.RedisRepository;
import org.noear.solon.ai.rag.splitter.SplitterPipeline;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Condition;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.net.http.HttpUtils;

import java.util.List;
import java.util.Properties;

/**
 * 知识库组件
 *
 * @author noear
 * @since 3.1
 */
@Condition(onClass = RedisRepository.class)
@Component("RedisRepository")
public class RedisRepositoryCom extends AbsAiComponent implements AiIoComponent, AiPropertyComponent {
    static final String META_REPOSITORY_CONFIG = "repositoryConfig";
    static final String META_DOCUMENT_SOURCES = "documentSources";
    static final String META_SPLIT_PIPELINE = "splitPipeline";

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        //构建知识库（预热后，会缓存住）
        RepositoryStorable repository = (RepositoryStorable) node.attachment;
        if (repository == null) {
            //构建
            EmbeddingModel embeddingModel = (EmbeddingModel) getProperty(context, Attrs.PROP_EMBEDDING_MODEL);

            Assert.notNull(embeddingModel, "Missing embeddingModel property!");


            Properties redisProperties = ONode.loadObj(node.getMeta(META_REPOSITORY_CONFIG)).toObject(Properties.class);
            RedisClient redisClient = new RedisClient(redisProperties);

            repository = RedisRepository.builder(embeddingModel, redisClient.jedis()).build();
            node.attachment = repository;

            //首次加载文档源
            List<String> documentSource = node.getMeta(META_DOCUMENT_SOURCES);
            if (Utils.isNotEmpty(documentSource)) {
                //获取分割器
                List<String> splitters = node.getMeta(META_SPLIT_PIPELINE);
                SplitterPipeline splitterPipeline = new SplitterPipeline();
                if (Utils.isNotEmpty(splitters)) {
                    for (String splitter : splitters) {
                        splitterPipeline.next((DocumentSplitter) ClassUtil.tryInstance(splitter));
                    }
                }

                //加载文档
                for (String uri : documentSource) {
                    if (uri.startsWith("http")) {
                        String text = HttpUtils.http(uri).get();

                        List<Document> documents = splitterPipeline.split(text);
                        repository.insert(documents);
                    }
                }
            }
        }

        Object data = getInput(context, node);

        Assert.notNull(data, "RedisRepository input is null");

        if (data instanceof String) {
            List<Document> documents = repository.search((String) data);
            data = ChatMessage.augment((String) data, documents);
            setOutput(context, node, data);
        } else {
            throw new IllegalArgumentException("Unsupported data type: " + data.getClass());
        }
    }
}