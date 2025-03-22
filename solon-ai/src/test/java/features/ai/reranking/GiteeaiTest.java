package features.ai.reranking;

import features.ai.rag.TestUtils;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.InMemoryRepository;
import org.noear.solon.ai.rag.splitter.RegexTextSplitter;
import org.noear.solon.ai.rag.splitter.SplitterPipeline;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.reranking.RerankingModel;
import org.noear.solon.net.http.HttpUtils;

import java.io.IOException;
import java.util.List;

/**
 * @author noear 2025/3/22 created
 */
public class GiteeaiTest {
    @Test
    public void rag_case1() throws Exception {
        //1.构建模型
        ChatModel chatModel = TestUtils.getChatModel();

        //2.构建知识库
        InMemoryRepository repository = new InMemoryRepository(TestUtils.getEmbeddingModel()); //3.初始化知识库
        load(repository, "https://solon.noear.org/article/about?format=md");
        load(repository, "https://h5.noear.org/more.htm");
        load(repository, "https://h5.noear.org/readme.htm");

        String query = "Solon 是谁开发的？";

        //检索
        List<Document> documents = repository.search(query);

        RerankingModel rerankingModel = TestUtils.getRerankingModel();

        //重排
        documents = rerankingModel.rerank(query, documents);

        //3.应用
        ChatResponse resp = chatModel
                .prompt(ChatMessage.augment(query, documents)) //3.1.搜索知识库（结果，作为提示语）
                .call(); //3.2.调用大模型

        //打印
        System.out.println(resp.getMessage());
    }

    private void load(RepositoryStorable repository, String url) throws IOException {
        String text = HttpUtils.http(url).get(); //1.加载文档（测试用）

        List<Document> documents = new SplitterPipeline() //2.分割文档（确保不超过 max-token-size）
                .next(new RegexTextSplitter())
                .next(new TokenSizeTextSplitter(500))
                .split(text);

        repository.insert(documents); //（推入文档）
    }
}
