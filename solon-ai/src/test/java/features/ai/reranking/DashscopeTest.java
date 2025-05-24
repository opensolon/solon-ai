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
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.ai.reranking.RerankingModel;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.test.SolonTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 未调通
 */
@SolonTest
public class DashscopeTest {
    public static RerankingModel getRerankingModel() {
        final String apiUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        final String apiKey = "sk-1ffe449611a74e61ad8e71e1b35a9858";
        final String provider = "dashscope";
        final String model = "gte-rerank";//

        return RerankingModel.of(apiUrl).apiKey(apiKey).provider(provider).model(model).build();
    }

    @Test
    public void case0() throws Exception {
        List<Document> documents = new ArrayList<>();
        documents.add(new Document().content("Hello World"));
        documents.add(new Document().content("Solon"));

        RerankingModel rerankingModel = getRerankingModel();

        //重排
        documents = rerankingModel.rerank("solon", documents);
        documents = SimilarityUtil.refilter(documents.stream());

        assert documents.size() == 1;
    }

    @Test
    public void case1() throws Exception {
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

        RerankingModel rerankingModel = getRerankingModel();

        //重排
        documents = rerankingModel.rerank(query, documents);

        //3.应用
        ChatResponse resp = chatModel
                .prompt(ChatMessage.ofUserAugment(query, documents)) //3.1.搜索知识库（结果，作为提示语）
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
