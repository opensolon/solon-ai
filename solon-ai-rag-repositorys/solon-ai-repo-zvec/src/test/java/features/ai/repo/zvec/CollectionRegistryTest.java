package features.ai.repo.zvec;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.rag.repository.zvec.CollectionRegistry;
import org.zvec.binding.Collection;
import org.zvec.binding.CollectionSchema;
import org.zvec.binding.DataType;
import org.zvec.binding.FieldSchema;
import org.zvec.binding.IndexParams;
import org.zvec.binding.MetricType;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集合注册表测试
 *
 * @author noear
 */
public class CollectionRegistryTest {
    private static final String BASE = "target/zvec_test_registry";

    @Test
    public void case1_initNativeIsIdempotent() {
        CollectionRegistry.initNative();
        CollectionRegistry.initNative();
        //重复初始化不应抛异常
        assertTrue(true);
    }

    @Test
    public void case2_openReusesSameInstance() throws Exception {
        String path = BASE + "/reuse";
        CollectionRegistry.destroy(path);

        Collection first = CollectionRegistry.open(path, this::schema, null);
        Collection second = CollectionRegistry.open(path, this::schema, null);

        //同一路径必须复用同一句柄（否则会撞目录锁）
        assertSame(first, second);

        CollectionRegistry.destroy(path);
    }

    @Test
    public void case3_openExistingDirAfterRestart() throws Exception {
        String path = BASE + "/existing";
        CollectionRegistry.destroy(path);

        //模拟“进程重启”：磁盘目录已存在，但注册表里没有句柄
        //（先用原生 API 直接建库并关闭，不经过注册表）
        try (CollectionSchema s = schema()) {
            Collection direct = org.zvec.binding.Zvec.createAndOpen(path, s, null);
            direct.close();
        }
        assertTrue(new File(path).exists());

        //此时 open 应走 Zvec.open（复用磁盘上的已有数据），而不是 createAndOpen
        Collection reused = CollectionRegistry.open(path, () -> {
            throw new AssertionError("existing collection should not be re-created");
        }, null);
        assertNotNull(reused);
        assertTrue(reused.isOpen());

        CollectionRegistry.destroy(path);
        assertFalse(new File(path).exists());
    }

    @Test
    public void case4_destroyCreatesParentDirs() throws Exception {
        //basePath 的父目录不存在时应自动创建
        String path = BASE + "/deep/nested/dir";
        CollectionRegistry.destroy(path);

        Collection c = CollectionRegistry.open(path, this::schema, null);
        assertTrue(new File(path).exists());

        CollectionRegistry.destroy(path);
        assertFalse(new File(path).exists());
    }

    @Test
    public void case5_destroyUnregisteredRecursiveDir() throws Exception {
        //注册表里没有记录时，destroy 直接递归删除磁盘目录
        String path = BASE + "/plain";
        File sub = new File(path, "a/b");
        assertTrue(sub.mkdirs());

        File f = new File(sub, "x.txt");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("hello".getBytes());
        }
        assertTrue(f.exists());

        CollectionRegistry.destroy(path);

        assertFalse(new File(path).exists());
    }

    @Test
    public void case8_relativePathWithoutParent() throws Exception {
        //单段相对路径（没有父目录）也要能建库，不能 NPE
        String path = "zvec_test_single_segment";
        CollectionRegistry.destroy(path);
        try {
            Collection c = CollectionRegistry.open(path, this::schema, null);
            assertTrue(c.isOpen());
        } finally {
            CollectionRegistry.destroy(path);
        }
    }

    @Test
    public void case6_destroyNonExistent() {
        //目录不存在时 destroy 也要安全
        CollectionRegistry.destroy(BASE + "/not_exists");
        assertTrue(true);
    }

    private CollectionSchema schema() {
        CollectionSchema schema = new CollectionSchema("c");

        try (FieldSchema content = new FieldSchema("content", DataType.STRING, true, 0)) {
            schema.addField(content);
        }

        try (FieldSchema vector = new FieldSchema("embedding", DataType.VECTOR_FP32, false, 4)) {
            try (IndexParams hnsw = IndexParams.createHNSW(MetricType.COSINE, 8, 50)) {
                vector.setIndexParams(hnsw);
            }
            schema.addField(vector);
        }

        return schema;
    }
}
