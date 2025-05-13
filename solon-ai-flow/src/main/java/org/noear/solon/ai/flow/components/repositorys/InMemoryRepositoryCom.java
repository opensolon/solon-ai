package org.noear.solon.ai.flow.components.repositorys;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.flow.components.AbsAiComponent;
import org.noear.solon.ai.flow.components.AiIoComponent;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentSplitter;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.InMemoryRepository;
import org.noear.solon.ai.rag.splitter.SplitterPipeline;
import org.noear.solon.annotation.Component;
import org.noear.solon.core.util.Assert;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.flow.Node;
import org.noear.solon.net.http.HttpUtils;

import java.util.List;

/**
 * 知识库组件
 *
 * @author noear
 * @since 3.1
 */
@Component("InMemoryRepository")
public class InMemoryRepositoryCom extends AbsAiComponent implements AiIoComponent {
    static final String META_EMBEDDING_CONFIG = "embeddingConfig";
    static final String META_DOCUMENT_SOURCES = "documentSources";
    static final String META_SPLIT_PIPELINE = "splitPipeline";

    @Override
    protected void doRun(FlowContext context, Node node) throws Throwable {
        //构建知识库（预热后，会缓存住）
        RepositoryStorable repository = (RepositoryStorable) node.attachment;
        if (repository == null) {
            //构建
            EmbeddingConfig embeddingConfig = ONode.load(node.getMeta(META_EMBEDDING_CONFIG)).toObject(EmbeddingConfig.class);
            EmbeddingModel embeddingModel = EmbeddingModel.of(embeddingConfig).build();

            repository = new InMemoryRepository(embeddingModel);
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

        Assert.notNull(data, "InMemoryRepository input is null");

        if (data instanceof String) {
            List<Document> documents = repository.search((String) data);
            data = ChatMessage.augment((String) data, documents);
            setOutput(context, node, data);
        } else {
            throw new IllegalArgumentException("Unsupported data type: " + data.getClass());
        }
    }
}