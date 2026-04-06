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
package features.ai.repo.dashvector;

import com.aliyun.dashvector.DashVectorClient;
import com.aliyun.dashvector.DashVectorCollection;
import com.aliyun.dashvector.models.Doc;
import com.aliyun.dashvector.models.PartitionStats;
import com.aliyun.dashvector.models.Vector;
import com.aliyun.dashvector.models.requests.QueryDocRequest;
import com.aliyun.dashvector.proto.FieldType;
import com.aliyun.dashvector.proto.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.DashVectorSDKRepository;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.MetadataField;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.util.DashVectorQueryCondition;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.util.DocumentConverter;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.net.http.HttpUtils;
import org.noear.solon.test.SolonTest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DashVector 官方 SDK 仓库测试
 *
 * <p>覆盖 {@link DashVectorSDKRepository} 全部公开方法，
 * 包括 CRUD（默认/指定分区）、Partition 管理、SDK 直接访问、
 * DocumentConverter 互转、DashVectorQueryCondition 扩展查询等。
 *
 * <p>详情参考 <a href="https://www.aliyun.com/product/ai/dashvector">DashVector</a>，可领取免费试用。
 *
 * @author 烧饵块
 */
@SolonTest
@Disabled("需要配置真实的 DashVector 凭据后再运行！！")
public class DashVectorSDKRepositoryTest {

    private DashVectorSDKRepository repository;

    // ===== 以下凭据需替换为真实值 =====
    private static final String SERVER_URL = "https://vrs-cn-test.dashvector.cn-hangzhou.aliyuncs.com";
    private static final String API_KEY = "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
    private static final String COLLECTION_NAME = "test_collection_sdk";
    private static final String TEST_PARTITION = "test_partition";

    private final String apiUrl = "http://127.0.0.1:11434/api/embed";
    private final String provider = "ollama";
    private final String model = "bge-m3:latest";

    // 保存在 setup 中创建的文档 ID，供后续测试使用
    private final List<String> seededDocIds = new ArrayList<>();

    // ====================================================================
    // setUp / tearDown
    // ====================================================================

    @BeforeEach
    public void setup() throws Exception {
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl).provider(provider).model(model).build();

        List<MetadataField> metadataFields = new ArrayList<>();
        metadataFields.add(new MetadataField("category", FieldType.STRING));
        metadataFields.add(new MetadataField("price", FieldType.FLOAT));
        metadataFields.add(new MetadataField("stock", FieldType.FLOAT));

        // builder(embeddingModel, DashVectorClient)
        repository = DashVectorSDKRepository
                .builder(embeddingModel, new DashVectorClient(API_KEY, SERVER_URL))
                .collectionName(COLLECTION_NAME)
                .metadataFields(metadataFields)
                .build();

        // initRepository() 在 build() 内部已调用，
        // 再次调用验证幂等性
        repository.initRepository();

        // 创建测试分区
        repository.createPartition(TEST_PARTITION);

        // 向默认分区写入种子数据
        seedDefaultPartition();

        // 向测试分区写入种子数据
        seedTestPartition();

        // 等待索引生效
        Thread.sleep(1000);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (repository != null) {
            // dropRepository 会删除整个 collection
            repository.dropRepository();
        }
    }

    /**
     * 向默认分区填充数据（通过 URL 加载 + 手动创建文档）
     */
    private void seedDefaultPartition() throws IOException {
        // 从网页加载文档
        loadFromUrl("https://solon.noear.org/article/about?format=md");

        // 手动创建几条带 metadata 的文档
        Document doc1 = new Document("Solon 是一个高效的 Java 应用开发框架");
        doc1.getMetadata().put("category", "framework");
        doc1.getMetadata().put("price", 0f);

        Document doc2 = new Document("Spring Boot 快速开始教程");
        doc2.getMetadata().put("category", "tutorial");
        doc2.getMetadata().put("price", 99f);

        Document doc3 = new Document("Java 微服务架构设计指南");
        doc3.getMetadata().put("category", "framework");
        doc3.getMetadata().put("price", 150f);
        doc3.getMetadata().put("stock", 30f);

        List<Document> docs = Arrays.asList(doc1, doc2, doc3);
        repository.save(docs);

        seededDocIds.add(doc1.getId());
        seededDocIds.add(doc2.getId());
        seededDocIds.add(doc3.getId());
    }

    /**
     * 向测试分区填充数据
     */
    private void seedTestPartition() throws IOException {
        Document pDoc1 = new Document("分区测试文档 A：Solon Cloud 微服务");
        pDoc1.getMetadata().put("category", "framework");

        Document pDoc2 = new Document("分区测试文档 B：NoSQL 数据库教程");
        pDoc2.getMetadata().put("category", "tutorial");

        List<Document> partitionDocs = Arrays.asList(pDoc1, pDoc2);
        repository.save(partitionDocs, TEST_PARTITION, null);

        seededDocIds.add(pDoc1.getId());
        seededDocIds.add(pDoc2.getId());
    }

    private void loadFromUrl(String url) throws IOException {
        String text = HttpUtils.http(url).get();
        List<Document> documents = new TokenSizeTextSplitter(200).split(text).stream()
                .map(doc -> {
                    doc.url(url);
                    return doc;
                })
                .collect(Collectors.toList());
        repository.save(documents);
        for (Document d : documents) {
            seededDocIds.add(d.getId());
        }
    }

    // ====================================================================
    // 1. Builder / SDK 访问
    // ====================================================================

    @Test
    public void testBuilderWithApiKeyEndpoint() throws Exception {
        // 测试 builder(embeddingModel, apiKey, endpoint)
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl).provider(provider).model(model).build();
        DashVectorSDKRepository repo2 = DashVectorSDKRepository
                .builder(embeddingModel, API_KEY, SERVER_URL)
                .collectionName(COLLECTION_NAME)
                .build();

        assertNotNull(repo2.getClient());
        assertNotNull(repo2.getCollection());
        assertEquals(COLLECTION_NAME, repo2.getCollectionName());
        // 不调用 dropRepository，因为和主 repository 共用同一个 collection
    }

    @Test
    public void testDirectSDKAccess() {
        // getClient()
        DashVectorClient client = repository.getClient();
        assertNotNull(client, "getClient() 应返回非 null");

        // getCollection()
        DashVectorCollection coll = repository.getCollection();
        assertNotNull(coll, "getCollection() 应返回非 null");
        assertTrue(coll.isSuccess());

        // getCollectionName()
        assertEquals(COLLECTION_NAME, repository.getCollectionName());
    }

    // ====================================================================
    // 2. save（默认分区 / 指定分区 / 进度回调）
    // ====================================================================

    @Test
    public void testSaveDefaultPartition() throws Exception {
        Document doc = new Document("testSaveDefaultPartition 文档内容");
        repository.save(Collections.singletonList(doc));

        Thread.sleep(500);
        assertTrue(repository.existsById(doc.getId()));

        repository.deleteById(doc.getId());
    }

    @Test
    public void testSaveWithPartition() throws Exception {
        Document doc = new Document("testSaveWithPartition 分区文档");
        repository.save(Collections.singletonList(doc), TEST_PARTITION, null);

        Thread.sleep(500);
        assertTrue(repository.existsByIdInPartition(TEST_PARTITION, doc.getId()));

        repository.deleteByIdInPartition(TEST_PARTITION, doc.getId());
    }

    @Test
    public void testSaveWithProgressCallback() throws Exception {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            docs.add(new Document("progress callback doc " + i));
        }

        AtomicInteger lastBatch = new AtomicInteger(0);
        AtomicInteger totalBatch = new AtomicInteger(0);

        repository.save(docs, (current, total) -> {
            lastBatch.set(current);
            totalBatch.set(total);
        });

        assertTrue(lastBatch.get() > 0, "应有至少一个批次回调");
        assertEquals(lastBatch.get(), totalBatch.get(), "最后回调应等于总批次数");

        for (Document d : docs) {
            repository.deleteById(d.getId());
        }
    }

    @Test
    public void testSaveEmptyList() throws IOException {
        // 空列表不应报错
        repository.save(new ArrayList<>());
        repository.save(new ArrayList<>(), TEST_PARTITION, null);
    }

    // ====================================================================
    // 3. upsertDocs（原生 Doc / 默认分区 / 指定分区）
    // ====================================================================

    @Test
    public void testUpsertDocsDefaultPartition() throws Exception {
        Document original = new Document("upsertDocs 原始内容");
        original.id("upsert-test-001");
        original.embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f});

        Doc sdkDoc = DocumentConverter.toDoc(original);
        repository.upsertDocs(Collections.singletonList(sdkDoc));

        Thread.sleep(500);
        assertTrue(repository.existsById("upsert-test-001"));

        repository.deleteById("upsert-test-001");
    }

    @Test
    public void testUpsertDocsWithPartition() throws Exception {
        Document original = new Document("upsertDocs 分区内容");
        original.id("upsert-part-001");
        original.embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f});

        Doc sdkDoc = DocumentConverter.toDoc(original);
        repository.upsertDocs(Collections.singletonList(sdkDoc), TEST_PARTITION);

        Thread.sleep(500);
        assertTrue(repository.existsByIdInPartition(TEST_PARTITION, "upsert-part-001"));

        repository.deleteByIdInPartition(TEST_PARTITION, "upsert-part-001");
    }

    // ====================================================================
    // 4. deleteById / deleteByIdInPartition / deleteAll
    // ====================================================================

    @Test
    public void testDeleteByIdDefaultPartition() throws Exception {
        Document doc = new Document("待删除文档");
        repository.save(Collections.singletonList(doc));
        Thread.sleep(500);

        assertTrue(repository.existsById(doc.getId()));

        repository.deleteById(doc.getId());
        Thread.sleep(500);

        assertFalse(repository.existsById(doc.getId()));
    }

    @Test
    public void testDeleteByIdInPartition() throws Exception {
        Document doc = new Document("分区待删除文档");
        repository.save(Collections.singletonList(doc), TEST_PARTITION, null);
        Thread.sleep(500);

        assertTrue(repository.existsByIdInPartition(TEST_PARTITION, doc.getId()));

        repository.deleteByIdInPartition(TEST_PARTITION, doc.getId());
        Thread.sleep(500);

        assertFalse(repository.existsByIdInPartition(TEST_PARTITION, doc.getId()));
    }

    @Test
    public void testDeleteAll() throws Exception {
        // 先往测试分区追加一条
        Document doc = new Document("deleteAll 测试文档");
        repository.save(Collections.singletonList(doc), TEST_PARTITION, null);
        Thread.sleep(500);

        assertTrue(repository.existsByIdInPartition(TEST_PARTITION, doc.getId()));

        // 清空测试分区
        repository.deleteAll(TEST_PARTITION);
        Thread.sleep(500);

        assertFalse(repository.existsByIdInPartition(TEST_PARTITION, doc.getId()));
    }

    @Test
    public void testDeleteByIdEmpty() throws IOException {
        // 空 ID 不应报错
        repository.deleteById();
        repository.deleteByIdInPartition(TEST_PARTITION);
    }

    // ====================================================================
    // 5. existsById / existsByIdInPartition
    // ====================================================================

    @Test
    public void testExistsByIdDefaultPartition() throws Exception {
        // 种子数据中的文档应存在
        assertFalse(seededDocIds.isEmpty());
        assertTrue(repository.existsById(seededDocIds.get(0)));

        // 不存在的 ID
        assertFalse(repository.existsById("non-existent-id-12345"));

        // 空 ID
        assertFalse(repository.existsById(""));
    }

    @Test
    public void testExistsByIdInPartition() throws Exception {
        Document doc = new Document("existsByIdInPartition 文档");
        repository.save(Collections.singletonList(doc), TEST_PARTITION, null);
        Thread.sleep(500);

        assertTrue(repository.existsByIdInPartition(TEST_PARTITION, doc.getId()));
        assertFalse(repository.existsByIdInPartition(TEST_PARTITION, "no-such-id"));

        repository.deleteByIdInPartition(TEST_PARTITION, doc.getId());
    }

    // ====================================================================
    // 6. getById / getByIdInPartition
    // ====================================================================

    @Test
    public void testGetByIdDefaultPartition() throws Exception {
        Document doc = new Document("getById 测试内容");
        doc.getMetadata().put("category", "test");
        repository.save(Collections.singletonList(doc));
        Thread.sleep(500);

        Document fetched = repository.getById(doc.getId());
        assertNotNull(fetched);
        assertEquals("getById 测试内容", fetched.getContent());
        assertEquals("test", fetched.getMetadata().get("category"));

        // 不存在返回 null
        assertNull(repository.getById("non-existent-id-xyz"));
        // 空 ID 返回 null
        assertNull(repository.getById(""));

        repository.deleteById(doc.getId());
    }

    @Test
    public void testGetByIdInPartition() throws Exception {
        Document doc = new Document("getByIdInPartition 分区内容");
        doc.getMetadata().put("category", "partition_test");
        repository.save(Collections.singletonList(doc), TEST_PARTITION, null);
        Thread.sleep(500);

        Document fetched = repository.getByIdInPartition(TEST_PARTITION, doc.getId());
        assertNotNull(fetched);
        assertEquals("getByIdInPartition 分区内容", fetched.getContent());

        // 在默认分区查不到
        assertNull(repository.getById(doc.getId()));

        repository.deleteByIdInPartition(TEST_PARTITION, doc.getId());
    }

    // ====================================================================
    // 7. search（QueryCondition / 过滤 / score 排序）
    // ====================================================================

    @Test
    public void testSearchBasic() throws Exception {
        List<Document> results = repository.search("solon");
        assertFalse(results.isEmpty(), "应能搜到与 solon 相关的文档");

        results = repository.search("xyzzynoexist999");
        assertTrue(results.isEmpty(), "搜不到不相关内容时应返回空");
    }

    @Test
    public void testSearchWithQueryCondition() throws Exception {
        QueryCondition condition = new QueryCondition("solon").limit(2).disableRefilter(true);
        List<Document> results = repository.search(condition);
        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 2, "limit=2 应最多返回 2 条");
    }

    @Test
    public void testSearchScoreDescending() throws Exception {
        QueryCondition condition = new QueryCondition("solon 框架").disableRefilter(true);
        List<Document> results = repository.search(condition);

        if (results.size() > 1) {
            for (int i = 1; i < results.size(); i++) {
                assertTrue(results.get(i - 1).getScore() >= results.get(i).getScore(),
                        "结果应按 score 降序排列");
            }
        }
    }

    @Test
    public void testSearchWithExpressionFilter() throws Exception {
        // category == 'framework'
        String expr = "category == 'framework'";
        QueryCondition condition = new QueryCondition("框架")
                .filterExpression(expr)
                .disableRefilter(true);

        List<Document> results = repository.search(condition);
        assertFalse(results.isEmpty());
        for (Document doc : results) {
            assertEquals("framework", doc.getMetadata().get("category"));
        }
    }

    @Test
    public void testSearchWithAndFilter() throws Exception {
        String expr = "category == 'framework' AND price > 100";
        QueryCondition condition = new QueryCondition("架构")
                .filterExpression(expr)
                .disableRefilter(true);

        List<Document> results = repository.search(condition);
        for (Document doc : results) {
            assertEquals("framework", doc.getMetadata().get("category"));
        }
    }

    @Test
    public void testSearchNullCondition() throws IOException {
        List<Document> results = repository.search((QueryCondition) null);
        assertTrue(results.isEmpty());
    }

    // ====================================================================
    // 8. DashVectorQueryCondition 扩展搜索
    // ====================================================================

    @Test
    public void testSearchWithDashVectorQueryCondition() throws Exception {
        DashVectorQueryCondition condition = new DashVectorQueryCondition("solon")
                .limit(5)
                .includeVector(true)
                .outputFields(Arrays.asList("category"))
                .disableRefilter(true);

        List<Document> results = repository.search(condition);
        assertFalse(results.isEmpty());
    }

    @Test
    public void testSearchInPartition() throws Exception {
        DashVectorQueryCondition condition = new DashVectorQueryCondition("微服务")
                .partition(TEST_PARTITION)
                .limit(10)
                .disableRefilter(true);

        List<Document> results = repository.search(condition);
        assertFalse(results.isEmpty(), "分区内应能搜到文档");
    }

    @Test
    public void testSearchById() throws Exception {
        // 先在默认分区选一条已有的文档 ID
        String existingId = seededDocIds.get(0);

        DashVectorQueryCondition condition = new DashVectorQueryCondition("any")
                .id(existingId)
                .limit(5)
                .disableRefilter(true);

        List<Document> results = repository.search(condition);
        assertFalse(results.isEmpty(), "通过 ID 检索应能返回结果");
    }

    // ====================================================================
    // 9. searchAsDoc / buildQueryRequest
    // ====================================================================

    @Test
    public void testSearchAsDoc() throws Exception {
        DashVectorQueryCondition condition = new DashVectorQueryCondition("solon")
                .limit(3)
                .includeVector(true)
                .disableRefilter(true);

        List<Doc> docs = repository.searchAsDoc(condition);
        assertFalse(docs.isEmpty(), "searchAsDoc 应返回原始 Doc");

        // 原始 Doc 转 Document
        Document document = DocumentConverter.toDocument(docs.get(0));
        assertNotNull(document);
        assertNotNull(document.getContent());
    }

    @Test
    public void testBuildQueryRequest() throws Exception {
        DashVectorQueryCondition condition = new DashVectorQueryCondition("solon")
                .partition(TEST_PARTITION)
                .limit(5)
                .includeVector(true)
                .filterExpression("category == 'framework'");

        QueryDocRequest request = repository.buildQueryRequest(condition);
        assertNotNull(request, "buildQueryRequest 不应返回 null");
        assertEquals(5, request.getTopk());
        assertTrue(request.isIncludeVector());
        assertEquals(TEST_PARTITION, request.getPartition());
    }

    // ====================================================================
    // 10. Partition 管理
    // ====================================================================

    @Test
    public void testListPartitions() throws Exception {
        List<String> partitions = repository.listPartitions();
        assertNotNull(partitions);
        assertTrue(partitions.contains("default"), "应包含 default 分区");
        assertTrue(partitions.contains(TEST_PARTITION), "应包含测试分区");
    }

    @Test
    public void testDescribePartition() throws Exception {
        Status status = repository.describePartition(TEST_PARTITION);
        assertNotNull(status);
        // 正常状态为 SERVING
        assertEquals(Status.SERVING, status);
    }

    @Test
    public void testStatsPartition() throws Exception {
        PartitionStats stats = repository.statsPartition(TEST_PARTITION);
        assertNotNull(stats);
        assertTrue(stats.getTotalDocCount() >= 0);
    }

    @Test
    public void testCreateAndDeletePartition() throws Exception {
        String tmpPartition = "tmp_test_part";
        repository.createPartition(tmpPartition);

        List<String> partitions = repository.listPartitions();
        assertTrue(partitions.contains(tmpPartition), "新建分区应出现在列表中");

        Status status = repository.describePartition(tmpPartition);
        assertEquals(Status.SERVING, status);

        repository.deletePartition(tmpPartition);

        // 删除后不应再出现
        partitions = repository.listPartitions();
        assertFalse(partitions.contains(tmpPartition));
    }

    // ====================================================================
    // 11. DocumentConverter 互转
    // ====================================================================

    @Test
    public void testDocumentToDocConversion() {
        Document original = new Document("互转测试内容");
        original.id("conv-001");
        original.url("https://example.com/page");
        original.getMetadata().put("category", "framework");
        original.getMetadata().put("price", 42);
        original.embedding(new float[]{0.1f, 0.2f, 0.3f, 0.4f});

        Doc sdkDoc = DocumentConverter.toDoc(original);
        assertEquals("conv-001", sdkDoc.getId());
        assertEquals("互转测试内容", sdkDoc.getFields().get(DocumentConverter.CONTENT_FIELD_KEY));
        assertEquals("https://example.com/page", sdkDoc.getFields().get(DocumentConverter.URL_FIELD_KEY));
        assertEquals("framework", sdkDoc.getFields().get("category"));
        assertNotNull(sdkDoc.getVector());
        assertEquals(4, sdkDoc.getVector().getValue().size());
    }

    @Test
    public void testDocToDocumentConversion() {
        Doc sdkDoc = Doc.builder()
                .id("conv-002")
                .vector(Vector.builder().value(Arrays.asList(0.5f, 0.6f, 0.7f, 0.8f)).build())
                .field(DocumentConverter.CONTENT_FIELD_KEY, "SDK Doc 内容")
                .field(DocumentConverter.URL_FIELD_KEY, "https://example.com/sdk")
                .field("category", "test")
                .build();

        Document restored = DocumentConverter.toDocument(sdkDoc);
        assertEquals("conv-002", restored.getId());
        assertEquals("SDK Doc 内容", restored.getContent());
        assertEquals("https://example.com/sdk", restored.getUrl());
        assertEquals("test", restored.getMetadata().get("category"));
        // content / url 不应残留在 metadata
        assertFalse(restored.getMetadata().containsKey(DocumentConverter.CONTENT_FIELD_KEY));
        assertFalse(restored.getMetadata().containsKey(DocumentConverter.URL_FIELD_KEY));
        // vector 回写
        assertNotNull(restored.getEmbedding());
        assertEquals(4, restored.getEmbedding().length);
    }

    @Test
    public void testRoundTripConversion() {
        Document original = new Document("往返转换测试");
        original.id("rt-001");
        original.url("https://example.com");
        original.getMetadata().put("k1", "v1");
        original.embedding(new float[]{1.0f, 2.0f, 3.0f});

        Doc sdkDoc = DocumentConverter.toDoc(original);
        Document restored = DocumentConverter.toDocument(sdkDoc);

        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getContent(), restored.getContent());
        assertEquals(original.getUrl(), restored.getUrl());
        assertEquals("v1", restored.getMetadata().get("k1"));
    }

    @Test
    public void testBatchConversion() {
        List<Document> docs = Arrays.asList(
                new Document("batch-1"),
                new Document("batch-2")
        );
        docs.get(0).id("b1");
        docs.get(0).embedding(new float[]{0.1f, 0.2f});
        docs.get(1).id("b2");
        docs.get(1).embedding(new float[]{0.3f, 0.4f});

        List<Doc> sdkDocs = DocumentConverter.toDocs(docs);
        assertEquals(2, sdkDocs.size());

        List<Document> restored = DocumentConverter.toDocuments(sdkDocs);
        assertEquals(2, restored.size());
        assertEquals("batch-1", restored.get(0).getContent());
        assertEquals("batch-2", restored.get(1).getContent());
    }

    @Test
    public void testNullConversion() {
        assertNull(DocumentConverter.toDoc(null));
        assertNull(DocumentConverter.toDocument(null));
        assertTrue(DocumentConverter.toDocs(null).isEmpty());
        assertTrue(DocumentConverter.toDocuments(null).isEmpty());
    }

    @Test
    public void testFloatArrayConversions() {
        List<Float> list = DocumentConverter.floatArrayToList(new float[]{1.1f, 2.2f});
        assertEquals(2, list.size());
        assertEquals(1.1f, list.get(0), 0.001f);

        float[] arr = DocumentConverter.toFloatArray(Arrays.asList(3.3f, 4.4f));
        assertEquals(2, arr.length);
        assertEquals(3.3f, arr[0], 0.001f);

        // null 安全
        assertTrue(DocumentConverter.floatArrayToList(null).isEmpty());
        assertEquals(0, DocumentConverter.toFloatArray(null).length);
    }

    // ====================================================================
    // 12. dropRepository / initRepository 幂等
    // ====================================================================

    @Test
    public void testInitRepositoryIdempotent() throws Exception {
        // 多次调用 initRepository 不应报错
        repository.initRepository();
        repository.initRepository();
        assertNotNull(repository.getCollection());
    }

    @Test
    public void testDropAndReinit() throws Exception {
        repository.dropRepository();
        assertNull(repository.getCollection());

        // 重新初始化
        repository.initRepository();
        assertNotNull(repository.getCollection());
        assertTrue(repository.getCollection().isSuccess());
    }
}