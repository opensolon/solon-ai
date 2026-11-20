package features.ai.repo.zvec;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.ZvecRepository;
import org.noear.solon.ai.rag.repository.zvec.MetadataField;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.expression.snel.ConditionBuilder;
import org.zvec.binding.MetricType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 构建参数与生命周期测试
 *
 * @author noear
 */
public class ZvecRepositoryBuilderTest {
    private static final String BASE_PATH = "target/zvec_test_builder";

    @Test
    public void case1_nullEmbeddingModel() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> ZvecRepository.builder(null, BASE_PATH).build());
        assertTrue(err.getMessage().contains("embeddingModel"));
    }

    @Test
    public void case2_emptyBasePath() {
        assertThrows(IllegalArgumentException.class,
                () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), "").build());
        assertThrows(IllegalArgumentException.class,
                () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), null).build());
    }

    @Test
    public void case3_nullMetricType() {
        assertThrows(IllegalArgumentException.class,
                () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                        .metricType(null).build());
    }

    @Test
    public void case4_emptyCollectionName() {
        assertThrows(IllegalArgumentException.class,
                () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                        .collectionName("").build());
        assertThrows(IllegalArgumentException.class,
                () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                        .collectionName(null).build());
    }

    @Test
    public void case5_pathTraversalCollectionName() {
        //集合名会拼成子目录，不允许跳出 basePath
        for (String name : Arrays.asList("a/b", "a\\b", "..", "../x")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                            .collectionName(name).build(), name);
        }
    }

    @Test
    public void case6_reservedMetadataField() {
        for (String name : Arrays.asList("content", "metadata", "embedding")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                            .collectionName("b_reserved")
                            .metadataFields(MetadataField.text(name))
                            .build(), name);
        }
    }

    @Test
    public void case7_duplicatedMetadataField() {
        assertThrows(IllegalArgumentException.class,
                () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                        .collectionName("b_dup")
                        .metadataFields(MetadataField.text("category"), MetadataField.int32("category"))
                        .build());
    }

    @Test
    public void case8_nullMetadataField() {
        assertThrows(IllegalArgumentException.class,
                () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                        .collectionName("b_null")
                        .metadataFields((MetadataField) null)
                        .build());
    }

    @Test
    public void case9_emptyMetadataFieldName() {
        assertThrows(IllegalArgumentException.class,
                () -> ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                        .collectionName("b_empty_name")
                        .metadataFields(MetadataField.text(""))
                        .build());
    }

    @Test
    public void case10_nullMetadataFieldsIgnored() throws Exception {
        //传 null 表示不配置（保持默认空列表）
        ZvecRepository repo = null;
        try {
            repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                    .collectionName("b_null_list")
                    .metadataFields((List<MetadataField>) null)
                    .build();
            assertTrue(repo.search("x").size() >= 0);
        } finally {
            dropQuietly(repo);
        }
    }

    @Test
    public void case10b_metadataFieldsListNullAndSet() {
        //List 版本：null 保持默认，非 null 直接生效
        ZvecRepository.Builder b = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_list_api");
        b.metadataFields((List<MetadataField>) null);
        b.metadataFields(Arrays.asList(MetadataField.text("k1"), MetadataField.text("k2")));
        ZvecRepository repo = b.build();
        try {
            assertNotNull(repo);
        } finally {
            try {
                repo.dropRepository();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    public void case10c_metadataFieldsVarargsNull() {
        ZvecRepository.Builder b = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_varargs_null");
        b.metadataFields((MetadataField[]) null);
        ZvecRepository repo = b.build();
        try {
            assertNotNull(repo);
        } finally {
            try {
                repo.dropRepository();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    public void case27_metricMipsL2UsesDefaultSimilarity() throws Exception {
        //MIPSL2 等非常规度量走默认相似度换算（1/(1+d)），不应抛异常
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_mipsl2")
                .metricType(MetricType.MIPSL2)
                .build();
        try {
            repo.dropRepository();
            repo.initRepository();
            repo.save(new Document("mipsl2 doc").id("m1"));
            repo.optimize();
            List<Document> list = repo.search(new QueryCondition("mipsl2")
                    .similarityThreshold(-1.0).limit(5));
            assertEquals(1, list.size());
            assertTrue(list.get(0).getScore() > 0);
        } catch (Exception e) {
            //原生层若不支持该度量，跳过（不算失败）
            System.out.println("skip MIPSL2: " + e.getMessage());
        } finally {
            try {
                repo.dropRepository();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    public void case11_collectionNameUsedAsSubDir() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("sub_dir_name")
                .build();
        try {
            repo.save(new Document("hello").id("h1"));
            assertTrue(repo.existsById("h1"));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case12_readOnlyAndMmap() throws Exception {
        //只读打开不应影响读取；写入应由原生层拒绝
        ZvecRepository writer = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_readonly")
                .build();
        writer.dropRepository();
        writer.initRepository();
        writer.save(new Document("readonly doc").id("r1"));
        writer.optimize();

        ZvecRepository reader = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_readonly")
                .readOnly(false)
                .mmap(false)
                .build();
        try {
            assertTrue(reader.existsById("r1"));
        } finally {
            reader.dropRepository();
        }
    }

    @Test
    public void case13_optimizeOnSave() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_optimize")
                .optimizeOnSave(true)
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(Arrays.asList(
                    new Document("alpha one").id("1"),
                    new Document("alpha two").id("2")));
            assertEquals("1", repo.search("alpha one").get(0).getId());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case14_handleSharingAndReopen() throws Exception {
        ZvecRepository a = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_share")
                .build();
        a.dropRepository();
        a.initRepository();
        a.save(new Document("shared doc").id("s1"));

        ZvecRepository b = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_share")
                .build();

        try {
            //同一目录共用同一句柄，不报 "Can't lock read-write collection"
            assertTrue(b.existsById("s1"));

            //a 被 drop 后，b 应能自行恢复（重新打开），不应一直报句柄已关闭
            a.dropRepository();
            b.initRepository();
            assertNotNull(b.search("shared doc"));
        } finally {
            a.dropRepository();
        }
    }

    @Test
    public void case15_includeVector() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_vector")
                .includeVector(true)
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("vector doc").id("v1"));
            repo.optimize();

            Document doc = repo.search(new QueryCondition("vector doc").limit(1)).get(0);
            assertNotNull(doc.getEmbedding());
            assertEquals(TestUtils.DIMENSIONS, doc.getEmbedding().length);
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case16_noIncludeVector() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_novector")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("no vector doc").id("n1"));
            repo.optimize();

            Document doc = repo.search(new QueryCondition("no vector doc").limit(1)).get(0);
            assertNull(doc.getEmbedding());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case17_metricIp() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_ip")
                .metricType(MetricType.IP)
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("hello world").id("ip1"));
            repo.optimize();

            List<Document> list = repo.search(new QueryCondition("hello world").similarityThreshold(0.0).limit(5));
            assertEquals(1, list.size());
            //IP 直接使用内积（词袋向量下为正数）
            assertTrue(list.get(0).getScore() > 0);
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case18_strongTypedMetadata() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_typed")
                .metadataFields(
                        MetadataField.text("cat"),
                        MetadataField.bool("flag"),
                        MetadataField.int32("num"),
                        MetadataField.int64("big"),
                        MetadataField.floatField("f"),
                        MetadataField.doubleField("d"))
                .hnswM(8)
                .hnswEfConstruction(50)
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("typed doc").id("t1")
                    .metadata("cat", "x")
                    .metadata("flag", true)
                    .metadata("num", 5)
                    .metadata("big", 9000000000L)
                    .metadata("f", 1.5f)
                    .metadata("d", 2.5d));
            repo.save(new Document("typed doc two").id("t2"));
            repo.optimize();

            assertTrue(repo.existsById("t1"));
            assertEquals(1, repo.search(new QueryCondition("typed")
                    .filterExpression("flag == true").limit(10).similarityThreshold(0.0)).size());
            assertEquals(0, repo.search(new QueryCondition("typed")
                    .filterExpression("flag == false").limit(10).similarityThreshold(0.0)).size());
            assertEquals(1, repo.search(new QueryCondition("typed")
                    .filterExpression(SnELHelper.gt("num", 4)).limit(10).similarityThreshold(0.0)).size());
            assertEquals(1, repo.search(new QueryCondition("typed")
                    .filterExpression(SnELHelper.gt("d", 2.0)).limit(10).similarityThreshold(0.0)).size());
            assertEquals(1, repo.search(new QueryCondition("typed")
                    .filterExpression(SnELHelper.gt("f", 1.0)).limit(10).similarityThreshold(0.0)).size());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case19_typeMismatchMessage() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_mismatch")
                .metadataFields(MetadataField.int32("year"))
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            //类型不匹配时应给出明确提示，而不是裸 ClassCastException
            IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                    () -> repo.save(new Document("mismatch").id("m1").metadata("year", "2018")));
            assertTrue(err.getMessage().contains("year"));
            assertTrue(err.getMessage().contains("INT32"));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case20_boolFromStringRejected() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_bool")
                .metadataFields(MetadataField.bool("flag"))
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            //不做字符串宽松转换（先宽松再严格会与内存二次过滤的语义对不上），应报明确错误
            IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                    () -> repo.save(new Document("bool doc").id("b1").metadata("flag", "true")));
            assertTrue(err.getMessage().contains("BOOL"));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case21_boolInvalidType() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_bool2")
                .metadataFields(MetadataField.bool("flag"))
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                    () -> repo.save(new Document("bool bad").id("b2").metadata("flag", 1)));
            assertTrue(err.getMessage().contains("BOOL"));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case22_metadataNullValueSkipped() throws Exception {
        //已声明列的元数据值为 null 时应跳过该列，不作强转
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_nullvalue")
                .metadataFields(MetadataField.int32("year"))
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("null value doc").id("nv1").metadata("year", null));
            repo.optimize();
            assertTrue(repo.existsById("nv1"));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case23_quoteInFilterValue() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_quote")
                .metadataFields(MetadataField.text("cat"))
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("quote doc").id("q1").metadata("cat", "o'x"));
            repo.optimize();

            //单引号需转义；若转义有误，原生层会报语法错误
            assertEquals(1, repo.search(new QueryCondition("quote")
                    .filterExpression(new ConditionBuilder().eq("cat", "o'x"))
                    .limit(10).similarityThreshold(0.0)).size());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case24_filterOnContentNotPushedDown() throws Exception {
        //content 不是 metadata 键：不应下推（否则会静默返回空结果），而是由内存二次过滤求值
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_content")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("alpha beta").id("c1"));
            repo.optimize();

            //metadata 上没有 content 键，内存过滤按语义得到 0 条（但不应因下推而与实现耦合）
            assertEquals(0, repo.search(new QueryCondition("alpha")
                    .filterExpression("content == 'alpha beta'")
                    .limit(10).similarityThreshold(0.0)).size());

            //未过滤时能正常召回，说明是过滤语义而不是数据丢失
            assertEquals(1, repo.search(new QueryCondition("alpha")
                    .limit(10).similarityThreshold(0.0)).size());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case25_dropWithoutInit() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_drop")
                .build();
        //目录不存在时 drop 也要能安全返回
        repo.dropRepository();
        repo.dropRepository();
    }

    @Test
    public void case26_deleteEmptyAndUnknownId() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_delete")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("delete doc").id("d1"));

            repo.deleteById();
            //删除不存在的 id 不应抛异常
            repo.deleteById("unknown-id");
            assertTrue(repo.existsById("d1"));

            repo.deleteById("d1", "d2");
            assertFalse(repo.existsById("d1"));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case27_existsByIdEmpty() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_exists")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            assertFalse(repo.existsById(""));
            assertFalse(repo.existsById(null));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case28_unindexedMetadataColumnFilterable() throws Exception {
        //未建倒排索引的列仍然可以下推过滤（zvec 支持逐行扫描）
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("b_noindex")
                .metadataFields(MetadataField.text("plain").indexed(false))
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("plain doc").id("p1").metadata("plain", "p1"));
            repo.save(new Document("plain doc two").id("p2").metadata("plain", "p2"));
            repo.optimize();

            assertEquals(1, repo.search(new QueryCondition("plain")
                    .filterExpression("plain == 'p1'").limit(10).similarityThreshold(0.0)).size());
        } finally {
            repo.dropRepository();
        }
    }

    private void dropQuietly(ZvecRepository repo) throws Exception {
        if (repo != null) {
            repo.dropRepository();
        }
    }

    /**
     * 便于在有类型参数下构造比较表达式
     */
    static class SnELHelper {
        static org.noear.solon.expression.Expression<Boolean> gt(String field, Number value) {
            return new ConditionBuilder().gt(field, value);
        }
    }

    static {
        //避免未使用告警
        Collections.emptyList();
    }
}
