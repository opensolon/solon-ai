package features.ai.repo.zvec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.ZvecRepository;
import org.noear.solon.ai.rag.repository.zvec.MetadataField;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.expression.snel.ConditionBuilder;
import org.zvec.binding.MetricType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zvec 知识库测试（嵌入式，无需外部服务）
 *
 * @author noear
 */
public class ZvecRepositoryTest {
    private static final String BASE_PATH = "target/zvec_test";

    private ZvecRepository repository;

    private List<Document> allDocs() {
        List<Document> docs = new ArrayList<>();
        docs.add(new Document("doc1", "solon 是一个 Java 应用开发框架", null, 0)
                .metadata("category", "framework").metadata("year", 2018));
        docs.add(new Document("doc2", "solon 支持 graalvm 原生编译", null, 0)
                .metadata("category", "framework").metadata("year", 2020));
        docs.add(new Document("doc3", "dubbo 是一个 RPC 框架", null, 0)
                .metadata("category", "rpc").metadata("year", 2011));
        docs.add(new Document("doc4", "milvus 是向量数据库", null, 0)
                .metadata("category", "database").metadata("year", 2019));
        return docs;
    }

    @BeforeEach
    public void setup() throws Exception {
        repository = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("solon_ai_test")
                .metadataFields(
                        MetadataField.text("category"),
                        MetadataField.int32("year"))
                .hnswEfConstruction(100)
                .build();

        repository.dropRepository();
        repository.initRepository();

        repository.save(allDocs());
        repository.optimize();
    }

    @AfterEach
    public void cleanup() {
        repository.dropRepository();
    }

    @Test
    public void case1_search() throws Exception {
        List<Document> list = repository.search("solon");
        assertEquals(2, list.size());
        assertTrue(list.get(0).getScore() > 0);

        list = repository.search("dubbo");
        assertEquals(1, list.size());
        assertEquals("doc3", list.get(0).getId());
    }

    @Test
    public void case2_save_and_exists() throws Exception {
        Document doc = new Document("zvec 是阿里开源的进程内向量库").id("doc5");
        repository.save(doc);

        assertTrue(repository.existsById("doc5"));
        assertFalse(repository.existsById("doc_unknown"));

        Document loaded = repository.search(new QueryCondition("zvec").similarityThreshold(0.0).limit(10)).stream()
                .filter(d -> "doc5".equals(d.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(loaded);

        //重复保存即更新
        repository.save(new Document("zvec 是一个嵌入式向量数据库").id("doc5").metadata("category", "database"));
        repository.optimize();

        List<Document> list = repository.search(new QueryCondition("zvec").similarityThreshold(0.0).limit(10));
        assertTrue(list.stream().anyMatch(d -> "doc5".equals(d.getId())));
    }

    @Test
    public void case3_delete() throws Exception {
        repository.deleteById("doc1");
        assertFalse(repository.existsById("doc1"));

        List<Document> list = repository.search("Java 应用开发框架");
        assertTrue(list.stream().noneMatch(d -> "doc1".equals(d.getId())));
    }

    @Test
    public void case4_filter_metadata() throws Exception {
        QueryCondition condition = new QueryCondition("框架")
                .filterExpression("category == 'rpc'")
                .limit(10)
                .similarityThreshold(0.0);

        List<Document> list = repository.search(condition);
        assertEquals(1, list.size());
        assertEquals("doc3", list.get(0).getId());
    }

    @Test
    public void case5_filter_numeric() throws Exception {
        QueryCondition condition = new QueryCondition("框架")
                .filterExpression("year > 2015")
                .limit(10)
                .similarityThreshold(0.0);

        List<Document> list = repository.search(condition);
        assertEquals(3, list.size());
        list.forEach(d -> assertTrue(((Number) d.getMetadata("year")).intValue() > 2015));
    }

    @Test
    public void case6_filter_unknown_field_fallback() throws Exception {
        //未声明为标量列的元数据不能下推给 zvec，应回退为内存二次过滤
        Document doc = new Document("elasticsearch 是搜索引擎").id("doc6").metadata("tags", "search");
        repository.save(doc);
        repository.optimize();

        QueryCondition condition = new QueryCondition("搜索引擎")
                .filterExpression("tags == 'search'")
                .limit(10)
                .similarityThreshold(0.0);

        List<Document> list = repository.search(condition);
        assertEquals(1, list.size());
        assertEquals("doc6", list.get(0).getId());
    }

    @Test
    public void case7_batch_and_progress() throws Exception {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            docs.add(new Document("批量文档 编号 " + i).id("batch" + i));
        }

        final int[] progress = {0, 0};
        repository.save(docs, (current, total) -> {
            progress[0] = current;
            progress[1] = total;
        });

        assertEquals(progress[1], progress[0]);
        assertTrue(repository.existsById("batch0"));
    }

    @Test
    public void case8_metric_l2() throws Exception {
        ZvecRepository l2Repository = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("solon_ai_test_l2")
                .metricType(MetricType.L2)
                .build();

        try {
            l2Repository.save(Collections.singletonList(
                    new Document("hello world").id("l2-1")));
            l2Repository.optimize();

            List<Document> list = l2Repository.search("hello world");
            assertEquals(1, list.size());
            //L2 距离转相似度后应在 0-1 之间，且完全匹配时接近 1
            assertTrue(list.get(0).getScore() > 0.9);
        } finally {
            l2Repository.dropRepository();
        }
    }

    @Test
    public void case9_empty_search() throws Exception {
        List<Document> list = repository.search(new QueryCondition("完全不相干的词语abcxyz").limit(3));
        assertNotNull(list);
        assertTrue(list.size() <= 3);
    }

    @Test
    public void case11_reopen() throws Exception {
        // 目录已存在时应直接打开（initRepository 的 open 分支）
        ZvecRepository reopened = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("solon_ai_test")
                .metadataFields(
                        MetadataField.text("category"),
                        MetadataField.int32("year"))
                .build();

        assertTrue(reopened.existsById("doc1"));
        assertEquals(1, reopened.search("dubbo").size());
    }

    @Test
    public void case12_filter_in_and_like() throws Exception {
        //in（SnEL 的字符串语法不支持 in，用 ConditionBuilder 构造）
        QueryCondition condition = new QueryCondition("框架")
                .filterExpression(new ConditionBuilder().in("category", "rpc", "database"))
                .limit(10)
                .similarityThreshold(0.0);

        List<Document> list = repository.search(condition);
        assertEquals(2, list.size());

        //like 不下推（避免通配符语义差异），走内存二次过滤
        QueryCondition condition2 = new QueryCondition("框架")
                .filterExpression("category LIKE 'fra'")
                .limit(10)
                .similarityThreshold(0.0);

        assertEquals(2, repository.search(condition2).size());
    }

    @Test
    public void case13_metadata_roundtrip() throws Exception {
        //未声明为列、且是复杂结构的元数据要能原样回读
        Document doc = new Document("complex meta doc").id("doc-meta")
                .metadata("category", "database")
                .metadata("tags", Arrays.asList("a", "b"))
                .metadata("nested", Collections.singletonMap("k", 1));
        repository.save(doc);
        repository.optimize();

        Document loaded = repository.search(new QueryCondition("complex meta doc").limit(1)).get(0);
        assertEquals("doc-meta", loaded.getId());
        assertEquals(Arrays.asList("a", "b"), loaded.getMetadata("tags"));
        assertEquals(Collections.singletonMap("k", 1), loaded.getMetadata("nested"));
    }

    @Test
    public void case10_save_empty() throws Exception {
        repository.save(new ArrayList<>());
        repository.deleteById();
        assertTrue(repository.existsById("doc2"));
    }
}
