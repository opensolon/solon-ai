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
import com.aliyun.dashvector.proto.CollectionInfo;
import com.aliyun.dashvector.proto.FieldType;
import com.aliyun.dashvector.proto.Status;

import org.junit.jupiter.api.*;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.DashVectorSDKRepository;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.MetadataField;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.util.DashVectorQueryCondition;
import org.noear.solon.ai.rag.repository.aliyun.dashvector.util.DocumentConverter;
import org.noear.solon.ai.rag.splitter.TokenSizeTextSplitter;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;
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
 * <p>使用 {@code PER_CLASS} 生命周期：全程只创建/销毁一次 collection，
 * 避免每个用例重复创建 gRPC 连接和集合。
 *
 * <p>详情参考 <a href="https://www.aliyun.com/product/ai/dashvector">DashVector</a>
 *
 * @author 烧饵块
 */
@SolonTest
@Disabled("需要配置真实的 DashVector 凭据后再运行！！")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DashVectorSDKRepositoryTest {

    private DashVectorSDKRepository repository;

    // ===== 以下凭据需替换为真实值 =====
    private static final String SERVER_URL = "vrs-cn-???.dashvector.cn-hangzhou.aliyuncs.com";
    private static final String API_KEY = "sk-";
    private static final String COLLECTION_NAME = "test_collection_sdk";
    private static final String TEST_PARTITION = "test_partition";

    private final String apiUrl = "";
    private final String apiKey = "";
    private final String provider = "";
    private final String model = "Qwen3-Embedding-0.6B";

    // 种子文档 ID，供搜索类测试使用
    private final List<String> seededDocIds = new ArrayList<>();

    // ====================================================================
    // 全局 setUp / tearDown（只执行一次）
    // ====================================================================

    @BeforeAll
    public void setupAll() throws Exception {
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl)
                .provider(provider).model(model)
                .apiKey(apiKey)
                .build();

        List<MetadataField> metadataFields = new ArrayList<>();
        metadataFields.add(new MetadataField("category", FieldType.STRING));
        metadataFields.add(new MetadataField("price", FieldType.FLOAT));
        metadataFields.add(new MetadataField("stock", FieldType.FLOAT));

        repository = DashVectorSDKRepository
                .builder(embeddingModel, new DashVectorClient(API_KEY, SERVER_URL))
                .collectionName(COLLECTION_NAME)
                .metadataFields(metadataFields)
                .build();

        // 创建测试分区
        repository.createPartition(TEST_PARTITION);

        // 填充种子数据到默认分区
        seedDefaultPartition();

        // 填充种子数据到测试分区
        seedTestPartition();

        // 等待索引生效
        Thread.sleep(2000);
    }

    @AfterAll
    public void tearDownAll() throws Exception {
        if (repository != null) {
            repository.dropRepository();
        }
    }

    private void seedDefaultPartition() throws IOException {
        // 从网页加载
        loadFromUrl("https://solon.noear.org/article/about?format=md");

        // 手动创建带 metadata 的文档
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
    @Order(1)
    public void testBuilderWithApiKeyEndpoint() throws Exception {
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl)
                .provider(provider).model(model)
                .apiKey(apiKey)
                .build();

        // 测试 builder(embeddingModel, apiKey, endpoint)
        DashVectorSDKRepository repo2 = DashVectorSDKRepository
                .builder(embeddingModel, API_KEY, SERVER_URL)
                .collectionName(COLLECTION_NAME)
                .build();

        assertNotNull(repo2.getClient());
        assertNotNull(repo2.getCollection());
        assertEquals(COLLECTION_NAME, repo2.getCollectionName());
    }

    @Test
    @Order(1)
    public void testDirectSDKAccess() {
        DashVectorClient client = repository.getClient();
        assertNotNull(client, "getClient() 应返回非 null");

        DashVectorCollection coll = repository.getCollection();
        assertNotNull(coll, "getCollection() 应返回非 null");
        assertTrue(coll.isSuccess());

        assertEquals(COLLECTION_NAME, repository.getCollectionName());
    }

    @Test
    @Order(1)
    public void testInitRepositoryIdempotent() throws Exception {
        // 多次调用 initRepository 不应报错（collection 已存在，直接返回）
        repository.initRepository();
        repository.initRepository();
        assertNotNull(repository.getCollection());
    }

    // ====================================================================
    // 2. save（默认分区 / 指定分区 / 进度回调）
    // ====================================================================

    @Test
    @Order(2)
    public void testSaveDefaultPartition() throws Exception {
        Document doc = new Document("testSaveDefaultPartition 文档内容");
        repository.save(Collections.singletonList(doc));

        Thread.sleep(500);
        assertTrue(repository.existsById(doc.getId()));

        // 清理
        repository.deleteById(doc.getId());
    }

    @Test
    @Order(2)
    public void testSaveWithPartition() throws Exception {
        Document doc = new Document("testSaveWithPartition 分区文档");
        repository.save(Collections.singletonList(doc), TEST_PARTITION, null);

        Thread.sleep(500);
        assertTrue(repository.existsByIdInPartition(TEST_PARTITION, doc.getId()));

        repository.deleteByIdInPartition(TEST_PARTITION, doc.getId());
    }

    @Test
    @Order(2)
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
    @Order(2)
    public void testSaveEmptyList() throws IOException {
        // 空列表不应报错
        repository.save(new ArrayList<>());
        repository.save(new ArrayList<>(), TEST_PARTITION, null);
    }

    // ====================================================================
    // 3. upsertDocs（原生 Doc / 默认分区 / 指定分区）
    // ====================================================================

    @Test
    @Order(3)
    public void testUpsertDocsDefaultPartition() throws Exception {
        // 先通过 save 让 EmbeddingModel 生成正确维度的向量
        Document original = new Document("upsertDocs 原始内容");
        original.id("upsert-test-001");
        repository.save(Collections.singletonList(original));

        Thread.sleep(500);
        assertTrue(repository.existsById("upsert-test-001"));

        // 再用 upsertDocs 更新同一条文档（验证 upsertDocs 调用通路）
        Doc sdkDoc = DocumentConverter.toDoc(original);
        repository.upsertDocs(Collections.singletonList(sdkDoc));

        Thread.sleep(500);
        assertTrue(repository.existsById("upsert-test-001"));

        repository.deleteById("upsert-test-001");
    }

    @Test
    @Order(3)
    public void testUpsertDocsWithPartition() throws Exception {
        Document original = new Document("upsertDocs 分区内容");
        original.id("upsert-part-001");
        repository.save(Collections.singletonList(original), TEST_PARTITION, null);

        Thread.sleep(500);

        // 用 upsertDocs 在指定分区更新
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
    @Order(4)
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
    @Order(4)
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
    @Order(4)
    public void testDeleteAll() throws Exception {
        // 单独创建一个临时分区来测试 deleteAll，避免影响其他用例
        String tmpPart = "tmp_del_all";
        repository.createPartition(tmpPart);

        Document doc = new Document("deleteAll 测试文档");
        repository.save(Collections.singletonList(doc), tmpPart, null);
        Thread.sleep(500);

        assertTrue(repository.existsByIdInPartition(tmpPart, doc.getId()));

        repository.deleteAll(tmpPart);
        Thread.sleep(500);

        assertFalse(repository.existsByIdInPartition(tmpPart, doc.getId()));

        repository.deletePartition(tmpPart);
    }

    @Test
    @Order(4)
    public void testDeleteByIdEmpty() throws IOException {
        // 空 ID 不应报错
        repository.deleteById();
        repository.deleteByIdInPartition(TEST_PARTITION);
    }

    // ====================================================================
    // 5. existsById / existsByIdInPartition
    // ====================================================================

    @Test
    @Order(5)
    public void testExistsByIdDefaultPartition() throws Exception {
        assertFalse(seededDocIds.isEmpty());
        assertTrue(repository.existsById(seededDocIds.get(0)));

        assertFalse(repository.existsById("non-existent-id-12345"));
        assertFalse(repository.existsById(""));
    }

    @Test
    @Order(5)
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
    @Order(6)
    public void testGetByIdDefaultPartition() throws Exception {
        Document doc = new Document("getById 测试内容");
        doc.getMetadata().put("category", "test");
        repository.save(Collections.singletonList(doc));
        Thread.sleep(500);

        Document fetched = repository.getById(doc.getId());
        assertNotNull(fetched);
        assertEquals("getById 测试内容", fetched.getContent());
        assertEquals("test", fetched.getMetadata().get("category"));

        assertNull(repository.getById("non-existent-id-xyz"));
        assertNull(repository.getById(""));

        repository.deleteById(doc.getId());
    }

    @Test
    @Order(6)
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
    @Order(7)
    public void testSearchBasic() throws Exception {
        List<Document> results = repository.search("solon");
        assertFalse(results.isEmpty(), "应能搜到与 solon 相关的文档");

        // 向量搜索即使查询无意义也可能返回低分结果，
        // 通过 similarityThreshold 过滤掉低相似度结果
        QueryCondition condition = new QueryCondition("xyzzynoexist999")
                .similarityThreshold(0.8);
        results = repository.search(condition);
        assertTrue(results.isEmpty(), "高阈值下不相关内容应被过滤");
    }

    @Test
    @Order(7)
    public void testSearchWithQueryCondition() throws Exception {
        QueryCondition condition = new QueryCondition("solon").limit(2).disableRefilter(true);
        List<Document> results = repository.search(condition);
        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 2, "limit=2 应最多返回 2 条");
    }

    @Test
    @Order(7)
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
    @Order(7)
    public void testSearchWithExpressionFilter() throws Exception {
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
    @Order(7)
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
    @Order(7)
    public void testSearchNullCondition() throws IOException {
        List<Document> results = repository.search((QueryCondition) null);
        assertTrue(results.isEmpty());
    }

    // ====================================================================
    // 8. DashVectorQueryCondition 扩展搜索
    // ====================================================================

    @Test
    @Order(8)
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
    @Order(8)
    public void testSearchInPartition() throws Exception {
        DashVectorQueryCondition condition = new DashVectorQueryCondition("微服务")
                .partition(TEST_PARTITION)
                .limit(10)
                .disableRefilter(true);

        List<Document> results = repository.search(condition);
        assertFalse(results.isEmpty(), "分区内应能搜到文档");
    }

    @Test
    @Order(8)
    public void testSearchById() throws Exception {
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
    @Order(9)
    public void testSearchAsDoc() throws Exception {
        DashVectorQueryCondition condition = new DashVectorQueryCondition("solon")
                .limit(3)
                .includeVector(true)
                .disableRefilter(true);

        List<Doc> docs = repository.searchAsDoc(condition);
        assertFalse(docs.isEmpty(), "searchAsDoc 应返回原始 Doc");

        Document document = DocumentConverter.toDocument(docs.get(0));
        assertNotNull(document);
        assertNotNull(document.getContent());
    }

    @Test
    @Order(9)
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
    @Order(10)
    public void testListPartitions() throws Exception {
        List<String> partitions = repository.listPartitions();
        assertNotNull(partitions);
        assertTrue(partitions.contains("default"), "应包含 default 分区");
        assertTrue(partitions.contains(TEST_PARTITION), "应包含测试分区");
    }

    @Test
    @Order(10)
    public void testDescribePartition() throws Exception {
        Status status = repository.describePartition(TEST_PARTITION);
        assertNotNull(status);
        assertEquals(Status.SERVING, status);
    }

    @Test
    @Order(10)
    public void testStatsPartition() throws Exception {
        PartitionStats stats = repository.statsPartition(TEST_PARTITION);
        assertNotNull(stats);
        assertTrue(stats.getTotalDocCount() >= 0);
    }

    @Test
    @Order(10)
    public void testCreateAndDeletePartition() throws Exception {
        String tmpPartition = "tmp_test_part";
        repository.createPartition(tmpPartition);

        List<String> partitions = repository.listPartitions();
        assertTrue(partitions.contains(tmpPartition), "新建分区应出现在列表中");

        Status status = repository.describePartition(tmpPartition);
        assertEquals(Status.SERVING, status);

        repository.deletePartition(tmpPartition);

        partitions = repository.listPartitions();
        assertFalse(partitions.contains(tmpPartition));
    }

    // ====================================================================
    // 11. DocumentConverter 互转（纯内存，无需网络）
    // ====================================================================

    @Test
    @Order(11)
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
    @Order(11)
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
        assertFalse(restored.getMetadata().containsKey(DocumentConverter.CONTENT_FIELD_KEY));
        assertFalse(restored.getMetadata().containsKey(DocumentConverter.URL_FIELD_KEY));
        assertNotNull(restored.getEmbedding());
        assertEquals(4, restored.getEmbedding().length);
    }

    @Test
    @Order(11)
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
    @Order(11)
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
    @Order(11)
    public void testNullConversion() {
        assertNull(DocumentConverter.toDoc(null));
        assertNull(DocumentConverter.toDocument(null));
        assertTrue(DocumentConverter.toDocs(null).isEmpty());
        assertTrue(DocumentConverter.toDocuments(null).isEmpty());
    }

    @Test
    @Order(11)
    public void testFloatArrayConversions() {
        List<Float> list = DocumentConverter.floatArrayToList(new float[]{1.1f, 2.2f});
        assertEquals(2, list.size());
        assertEquals(1.1f, list.get(0), 0.001f);

        float[] arr = DocumentConverter.toFloatArray(Arrays.asList(3.3f, 4.4f));
        assertEquals(2, arr.length);
        assertEquals(3.3f, arr[0], 0.001f);

        assertTrue(DocumentConverter.floatArrayToList(null).isEmpty());
        assertEquals(0, DocumentConverter.toFloatArray(null).length);
    }

    // ====================================================================
    // 12. Builder 新增选项（metric / dataType / quantizeType）
    // ====================================================================

    @Test
    @Order(12)
    public void testBuilderWithEuclideanMetric() throws Exception {
        String euclideanCollection = "test_euclidean";
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl)
                .provider(provider).model(model)
                .apiKey(apiKey)
                .build();

        DashVectorSDKRepository euclideanRepo = DashVectorSDKRepository
                .builder(embeddingModel, new DashVectorClient(API_KEY, SERVER_URL))
                .collectionName(euclideanCollection)
                .metric(CollectionInfo.Metric.euclidean)
                .build();

        try {
            Document doc = new Document("euclidean 度量测试文档");
            euclideanRepo.save(Collections.singletonList(doc));
            Thread.sleep(1000);

            assertTrue(euclideanRepo.existsById(doc.getId()));

            // euclidean 的 score 是距离值，不在 [0,1] 范围，需要将阈值设为 0
            List<Document> results = euclideanRepo.search(
                    new QueryCondition("euclidean 度量")
                            .similarityThreshold(0)
                            .disableRefilter(true));
            assertFalse(results.isEmpty());
        } finally {
            euclideanRepo.dropRepository();
        }
    }

    @Test
    @Order(12)
    public void testBuilderWithDotProductMetric() throws Exception {
        String dotProductCollection = "test_dotproduct";
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl)
                .provider(provider).model(model)
                .apiKey("PESUGLZTSY4YMBA8LSGLW5PW5QJHIYDWWRGON61R")
                .build();

        DashVectorSDKRepository dotProductRepo = DashVectorSDKRepository
                .builder(embeddingModel, new DashVectorClient(API_KEY, SERVER_URL))
                .collectionName(dotProductCollection)
                .metric(CollectionInfo.Metric.dotproduct)
                .build();

        try {
            Document doc = new Document("dotproduct 度量测试文档");
            dotProductRepo.save(Collections.singletonList(doc));
            Thread.sleep(1000);

            assertTrue(dotProductRepo.existsById(doc.getId()));

            // dotproduct 的 score 不在 [0,1] 范围，需要将阈值设为 0
            List<Document> results = dotProductRepo.search(
                    new QueryCondition("dotproduct 度量")
                            .similarityThreshold(0)
                            .disableRefilter(true));
            assertFalse(results.isEmpty());
        } finally {
            dotProductRepo.dropRepository();
        }
    }

    @Test
    @Order(12)
    public void testBuilderWithQuantizeType() throws Exception {
        String quantizedCollection = "test_quantized";
        EmbeddingModel embeddingModel = EmbeddingModel.of(apiUrl)
                .provider(provider).model(model)
                .apiKey(apiKey)
                .build();

        DashVectorSDKRepository quantizedRepo = DashVectorSDKRepository
                .builder(embeddingModel, new DashVectorClient(API_KEY, SERVER_URL))
                .collectionName(quantizedCollection)
                .metric(CollectionInfo.Metric.dotproduct)
                .quantizeType("DT_VECTOR_INT8")
                .build();

        try {
            Document doc = new Document("INT8 量化测试文档");
            quantizedRepo.save(Collections.singletonList(doc));
            Thread.sleep(1000);

            assertTrue(quantizedRepo.existsById(doc.getId()));

            // dotproduct + 量化，score 不在 [0,1]，阈值设为 0
            List<Document> results = quantizedRepo.search(
                    new QueryCondition("INT8 量化")
                            .similarityThreshold(0)
                            .disableRefilter(true));
            assertFalse(results.isEmpty());
        } finally {
            quantizedRepo.dropRepository();
        }
    }

    // ====================================================================
    // 13. SimilarityUtil 三种度量方法
    // ====================================================================

    @Test
    @Order(13)
    public void testCosineSimilarity() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        float[] c = {0.0f, 1.0f, 0.0f};

        // 相同向量 cosine = 1.0
        assertEquals(1.0, SimilarityUtil.cosineSimilarity(a, b), 0.001);
        // 正交向量 cosine = 0.0
        assertEquals(0.0, SimilarityUtil.cosineSimilarity(a, c), 0.001);
    }

    @Test
    @Order(13)
    public void testEuclideanDistance() {
        float[] a = {0.0f, 0.0f};
        float[] b = {3.0f, 4.0f};

        // 距离 = 5.0
        assertEquals(5.0, SimilarityUtil.euclideanDistance(a, b), 0.001);
        // 相同向量距离 = 0
        assertEquals(0.0, SimilarityUtil.euclideanDistance(a, a), 0.001);
    }

    @Test
    @Order(13)
    public void testEuclideanSimilarity() {
        float[] a = {0.0f, 0.0f};
        float[] b = {3.0f, 4.0f};

        // similarity = 1 / (1 + 5) = 1/6
        double similarity = SimilarityUtil.euclideanSimilarity(a, b);
        assertEquals(1.0 / 6.0, similarity, 0.001);

        // 相同向量 similarity = 1 / (1 + 0) = 1.0
        assertEquals(1.0, SimilarityUtil.euclideanSimilarity(a, a), 0.001);
    }

    @Test
    @Order(13)
    public void testDotProductSimilarity() {
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {4.0f, 5.0f, 6.0f};

        // dotProduct = 1*4 + 2*5 + 3*6 = 32
        assertEquals(32.0, SimilarityUtil.dotProductSimilarity(a, b), 0.001);
    }

    @Test
    @Order(13)
    public void testScoreMethods() {
        float[] queryEmbed = {1.0f, 0.0f, 0.0f};

        Document doc = new Document("score test");
        doc.embedding(new float[]{0.6f, 0.8f, 0.0f});

        // score (cosine)
        Document scored = SimilarityUtil.score(doc, queryEmbed);
        assertTrue(scored.getScore() > 0 && scored.getScore() <= 1.0);

        // scoreByEuclidean
        Document euclideanScored = SimilarityUtil.scoreByEuclidean(doc, queryEmbed);
        assertTrue(euclideanScored.getScore() > 0 && euclideanScored.getScore() <= 1.0);

        // scoreByDotProduct
        Document dotScored = SimilarityUtil.scoreByDotProduct(doc, queryEmbed);
        assertEquals(0.6, dotScored.getScore(), 0.001);
    }

    @Test
    @Order(13)
    public void testSimilarityNullAndMismatch() {
        float[] a = {1.0f, 2.0f};
        float[] b = {1.0f, 2.0f, 3.0f};

        // 长度不等
        assertThrows(IllegalArgumentException.class,
                () -> SimilarityUtil.cosineSimilarity(a, b));
        assertThrows(IllegalArgumentException.class,
                () -> SimilarityUtil.euclideanDistance(a, b));
        assertThrows(IllegalArgumentException.class,
                () -> SimilarityUtil.dotProductSimilarity(a, b));

        // null 输入
        assertThrows(RuntimeException.class,
                () -> SimilarityUtil.cosineSimilarity(null, a));
        assertThrows(RuntimeException.class,
                () -> SimilarityUtil.euclideanDistance(null, a));
        assertThrows(RuntimeException.class,
                () -> SimilarityUtil.dotProductSimilarity(null, a));
    }

    // ====================================================================
    // 99. dropRepository / initRepository 生命周期
    //     放在最后执行，因为 drop 会销毁 collection
    // ====================================================================

    @Test
    @Order(100)
    public void testDropAndReinit() throws Exception {
        // drop 后 collection 置空
        repository.dropRepository();
        assertNull(repository.getCollection());

        // 等待服务端完成删除
        Thread.sleep(2000);

        // 重新 init —— 应重新创建 collection
        repository.initRepository();
        assertNotNull(repository.getCollection());
        assertTrue(repository.getCollection().isSuccess());
    }
}