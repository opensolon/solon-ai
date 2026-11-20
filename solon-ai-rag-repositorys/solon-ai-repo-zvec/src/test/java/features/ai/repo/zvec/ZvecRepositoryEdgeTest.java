package features.ai.repo.zvec;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.embedding.EmbeddingConfig;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.repository.ZvecRepository;
import org.noear.solon.ai.rag.repository.zvec.FilterTransformer;
import org.noear.solon.ai.rag.repository.zvec.MetadataField;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.expression.snel.ConditionBuilder;
import org.noear.solon.expression.snel.SnEL;
import org.zvec.binding.DataType;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 边界与分支补充测试
 *
 * @author noear
 */
public class ZvecRepositoryEdgeTest {
    private static final String BASE_PATH = "target/zvec_test_edge";

    @Test
    public void case1_saveEmptyListInvokesCallback() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_empty")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            final int[] called = {-1, -1};
            repo.save(java.util.Collections.<Document>emptyList(), (total, ok) -> {
                called[0] = total;
                called[1] = ok;
            });
            //空输入也要回调（0,0），避免调用方一直等进度
            assertEquals(0, called[0]);
            assertEquals(0, called[1]);
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case2_saveWithoutIdGeneratesId() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_noid")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            Document doc = new Document("no id doc");
            assertNull(doc.getId());

            repo.save(doc);
            //保存后回填了 id（调用方可以直接拿到）
            assertNotNull(doc.getId());
            assertTrue(repo.existsById(doc.getId()));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case3_saveBatchAndProgress() throws Exception {
        //batchSize 为 10，25 条应分 3 批
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_progress")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            java.util.List<Document> docs = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) {
                docs.add(new Document("batch doc " + i).id("b" + i));
            }

            final java.util.List<String> progress = new java.util.ArrayList<>();
            repo.save(docs, (total, ok) -> progress.add(total + "/" + ok));

            assertEquals(Arrays.asList("1/3", "2/3", "3/3"), progress);
            assertEquals(25, repo.search(new QueryCondition("batch").limit(30)).size());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case4_documentWithNullContent() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_nullcontent")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            Document doc = new Document(null).id("nc1");
            doc.embedding(TestUtils.bow("placeholder"));
            repo.save(java.util.Collections.singletonList(doc));
            repo.optimize();

            //content 为 null 时不写该列，回读时也应为 null
            assertTrue(repo.existsById("nc1"));
            Document got = repo.search(new QueryCondition("placeholder").limit(1)
                    .similarityThreshold(-1.0)).get(0);
            assertNull(got.getContent());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case5_documentWithoutEmbeddingUsesEmbeddingModel() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_autoembed")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            //没有预先设置 embedding 时，save 会调用嵌入模型补齐
            repo.save(new Document("auto embed doc").id("ae1"));
            repo.optimize();

            assertEquals(1, repo.search(new QueryCondition("auto embed").limit(1)).size());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case5b_embeddingDimensionsMismatch() throws Exception {
        //嵌入模型声明的维度与实际返回的向量长度不一致时，写入前就应给出明确错误
        EmbeddingConfig cfg = new EmbeddingConfig();
        cfg.setApiUrl("http://127.0.0.1:0/embed");
        cfg.setProvider("test");
        cfg.setModel("test-bow-bad");
        EmbeddingModel badModel = new EmbeddingModel(cfg) {
            @Override
            public float[] embed(String text) {
                return new float[]{1, 2};
            }

            @Override
            public void embed(List<Document> documents) {
                for (Document doc : documents) {
                    doc.embedding(new float[]{1, 2});
                }
            }

            @Override
            public int dimensions() {
                return TestUtils.DIMENSIONS;
            }
        };

        ZvecRepository repo = ZvecRepository.builder(badModel, BASE_PATH)
                .collectionName("e_dim")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                    () -> repo.save(new Document("dim doc").id("dm1")));
            assertTrue(err.getMessage().contains("dm1"));
            assertTrue(err.getMessage().contains(String.valueOf(TestUtils.DIMENSIONS)));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case6_metadataPreservedAsJson() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_meta")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            Document doc = new Document("meta doc").id("m1")
                    .metadata("s", "text")
                    .metadata("n", 42)
                    .metadata("b", true)
                    .metadata("list", Arrays.asList("a", "b"));
            repo.save(doc);
            repo.optimize();

            Document got = repo.search(new QueryCondition("meta doc").limit(1)).get(0);
            //未声明为强类型列的元数据整体存 JSON，应原样回读
            assertEquals("text", got.getMetadata("s"));
            assertEquals(Arrays.asList("a", "b"), got.getMetadata("list"));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case7_emptyMetadata() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_emptymeta")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("empty meta").id("em1"));
            repo.optimize();

            Document got = repo.search(new QueryCondition("empty meta").limit(1)).get(0);
            assertNotNull(got.getMetadata());
            assertTrue(got.getMetadata().isEmpty());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case8_unsupportedMetadataType() {
        //声明了不支持写入的类型（数组）时，应给出明确错误
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_unsupported")
                .metadataFields(new MetadataField("tags", DataType.ARRAY_STRING))
                .build();
        try {
            repo.dropRepository();
            repo.initRepository();
            IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                    () -> repo.save(new Document("x").id("x1").metadata("tags", Arrays.asList("a"))));
            assertTrue(err.getMessage().contains("Unsupported"));
        } catch (Exception e) {
            //建表阶段被原生层拒绝也算合理结果
            assertTrue(true);
        } finally {
            try {
                repo.dropRepository();
            } catch (Exception ignore) {
            }
        }
    }

    @Test
    public void case9_enabledFilterFallsBackToRefilter() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_fallback")
                .metadataFields(MetadataField.text("cat"))
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("fallback one").id("f1").metadata("cat", "a"));
            repo.save(new Document("fallback two").id("f2").metadata("cat", "b"));
            repo.optimize();

            //LIKE 不下推，走内存二次过滤（topK 会被放大）
            List<Document> list = repo.search(new QueryCondition("fallback")
                    .filterExpression("cat LIKE 'a'").limit(5).similarityThreshold(0.0));
            assertEquals(1, list.size());
            assertEquals("f1", list.get(0).getId());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case10_searchWithNullFilter() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_nullfilter")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(new Document("null filter doc").id("nf1"));
            repo.optimize();

            assertEquals(1, repo.search(new QueryCondition("null filter").limit(1)).size());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case11_dimensionsInvalid() {
        EmbeddingConfig cfg = new EmbeddingConfig();
        cfg.setApiUrl("http://127.0.0.1:0/embed");
        cfg.setProvider("test");
        cfg.setModel("test-bow");
        EmbeddingModel bad = new EmbeddingModel(cfg) {
            @Override
            public float[] embed(String text) {
                return new float[0];
            }

            @Override
            public int dimensions() {
                return 0;
            }
        };

        IllegalStateException err = assertThrows(IllegalStateException.class,
                () -> ZvecRepository.builder(bad, BASE_PATH).collectionName("e_baddim").build());
        assertTrue(err.getMessage().contains("dimensions"));
    }

    @Test
    public void case12_dimensionsThrows() {
        EmbeddingConfig cfg = new EmbeddingConfig();
        cfg.setApiUrl("http://127.0.0.1:0/embed");
        cfg.setProvider("test");
        cfg.setModel("test-bow");
        EmbeddingModel bad = new EmbeddingModel(cfg) {
            @Override
            public float[] embed(String text) {
                return new float[0];
            }

            @Override
            public int dimensions() throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
        };

        IllegalStateException err = assertThrows(IllegalStateException.class,
                () -> ZvecRepository.builder(bad, BASE_PATH).collectionName("e_dimex").build());
        assertTrue(err.getMessage().contains("dimensions"));
    }

    @Test
    public void case13_deleteAll() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_delall")
                .build();
        repo.dropRepository();
        repo.initRepository();

        try {
            repo.save(Arrays.asList(new Document("del a").id("d1"), new Document("del b").id("d2")));
            repo.optimize();
            assertEquals(2, repo.search(new QueryCondition("del").limit(10)).size());

            repo.deleteById("d1", "d2");
            assertEquals(0, repo.search(new QueryCondition("del").limit(10)).size());
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case14_collectionReopenAfterDrop() throws Exception {
        ZvecRepository repo = ZvecRepository.builder(TestUtils.getEmbeddingModel(), BASE_PATH)
                .collectionName("e_reopen")
                .build();
        repo.dropRepository();
        repo.initRepository();
        repo.save(new Document("reopen doc").id("r1"));

        //drop 后同一实例可以重新 init（句柄与磁盘数据都重建）
        repo.dropRepository();
        repo.initRepository();

        try {
            assertFalse(repo.existsById("r1"));
            repo.save(new Document("reopen doc2").id("r2"));
            repo.optimize();
            assertTrue(repo.existsById("r2"));
        } finally {
            repo.dropRepository();
        }
    }

    @Test
    public void case15_filterTransformerNotPushedDownForNot() {
        //NOT 与未知节点类型都返回 null（不下推）
        FilterTransformer t = FilterTransformer.getInstance();
        assertNull(t.transform(SnEL.parse("!(cat == 'x')"), new java.util.HashSet<>(Arrays.asList("cat"))));
    }

    @Test
    public void case16_inWithEnumValue() {
        FilterTransformer t = FilterTransformer.getInstance();
        String filter = t.transform(new ConditionBuilder().in("cat", OutEnum.A, OutEnum.B),
                new java.util.HashSet<>(Arrays.asList("cat")));
        assertEquals("(cat in ('A', 'B'))", filter);
    }

    @Test
    public void case17_eqWithEnumValue() {
        FilterTransformer t = FilterTransformer.getInstance();
        String filter = t.transform(new ConditionBuilder().eq("cat", OutEnum.A),
                new java.util.HashSet<>(Arrays.asList("cat")));
        assertEquals("(cat = 'A')", filter);
    }

    enum OutEnum {A, B}
}
