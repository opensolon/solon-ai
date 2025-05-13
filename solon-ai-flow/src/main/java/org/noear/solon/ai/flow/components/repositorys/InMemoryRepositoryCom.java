package org.noear.solon.ai.flow.components.repositorys;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.flow.components.AbstractDataCom;
import org.noear.solon.ai.flow.components.Attrs;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.DocumentSplitter;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.InMemoryRepository;
import org.noear.solon.ai.rag.splitter.SplitterPipeline;
import org.noear.solon.annotation.Component;
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
public class InMemoryRepositoryCom extends AbstractDataCom {
    static final String META_STREAM = "stream";

    @Override
    public void run(FlowContext context, Node node) throws Throwable {
        //初始化知识库（已缓存）
        RepositoryStorable repository = (RepositoryStorable) node.attachment;
        if (repository == null) {
            //构建
            EmbeddingConfig embeddingConfig = ONode.load(node.getMeta(Attrs.META_EMBEDDING_CONFIG)).toObject(EmbeddingConfig.class);
            EmbeddingModel embeddingModel = EmbeddingModel.of(embeddingConfig).build();

            repository = new InMemoryRepository(embeddingModel);
            node.attachment = repository;

            //首次加载文档源
            List<String> documentSource = node.getMeta(Attrs.META_DOCUMENT_SOURCE);
            if (Utils.isNotEmpty(documentSource)) {
                //获取分割器
                List<String> splitters = node.getMeta(Attrs.META_SPLIT_PIPELINE);
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

        Object data = getDataInput(context, node, null);

        if (data instanceof String) {
            List<Document> documents = repository.search((String) data);
            data = ChatMessage.augment((String) data, documents);
            setDataOutput(context, node, data, null);
        } else {
            throw new IllegalArgumentException("Unsupported data type: " + data.getClass());
        }
    }
}
