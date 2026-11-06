package demo.ai.memory;

import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.talents.memory.MemorySearchResult;
import org.noear.solon.ai.talents.memory.md.MemoryMdData;
import org.noear.solon.ai.talents.mount.source.MemoryMountSource;
import org.noear.solon.ai.talents.mount.source.MountSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MemoryMdData 经挂载来源读写：用内存来源模拟远程存储（db / http），证明不依赖本地文件系统。
 */
public class MemoryMdDataMountSourceTest {
    private static final String U1 = "u1";

    private static String val(String content, int importance) {
        return new ONode().set("content", content).set("time", "2026-01-01 10:00:00")
                .set("importance", importance).toJson();
    }

    private static Map<String, MountSource> scopes(MountSource user, MountSource ws) {
        Map<String, MountSource> map = new LinkedHashMap<>();
        map.put("user", user);
        map.put("workspace", ws);
        return map;
    }

    @Test
    public void putWritesMdIntoSourceAndReloadsFromIt() throws Exception {
        MemoryMountSource user = new MemoryMountSource("user");
        MemoryMountSource ws = new MemoryMountSource("ws");

        MemoryMdData data = MemoryMdData.of(scopes(user, ws));
        data.put(U1, "lang", val("偏好使用 Solon 框架", 8), -1, "workspace");
        data.updateIndex(U1, "lang", "偏好使用 Solon 框架", 8, "2026-01-01 10:00:00", "workspace");
        data.close();

        String md = ws.readStringOrNull("u1__lang.md");
        assertNotNull(md, "条目必须写进来源");
        assertTrue(md.contains("偏好使用 Solon 框架"));
        assertNull(user.readStringOrNull("u1__lang.md"), "不应写到其他作用域");

        // 新实例（模拟重启或另一计算节点）从同一来源全量加载
        MemoryMdData reloaded = MemoryMdData.of(scopes(user, ws));
        try {
            assertTrue(reloaded.get(U1, "lang").contains("Solon"));
            List<MemorySearchResult> hits = reloaded.search(U1, "Solon", 5);
            assertEquals(1, hits.size());
            assertEquals("workspace", hits.get(0).getScope());
        } finally {
            reloaded.close();
        }
    }

    @Test
    public void getFallsBackToSourceOnCacheMiss() throws Exception {
        MemoryMountSource user = new MemoryMountSource("user");
        MemoryMountSource ws = new MemoryMountSource("ws");
        MemoryMdData a = MemoryMdData.of(scopes(user, ws));
        MemoryMdData b = MemoryMdData.of(scopes(user, ws));
        try {
            a.put(U1, "k", val("另一节点写入", 6), -1, "user");
            // b 启动时没有这条，按 key 读取时回源
            assertNotNull(b.get(U1, "k"));
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    public void removeDeletesFromSource() throws Exception {
        MemoryMountSource user = new MemoryMountSource("user");
        MemoryMountSource ws = new MemoryMountSource("ws");
        MemoryMdData data = MemoryMdData.of(scopes(user, ws));
        try {
            data.put(U1, "k", val("x", 5), -1, "workspace");
            assertTrue(ws.exists("u1__k.md"));
            data.remove(U1, "k");
            assertFalse(ws.exists("u1__k.md"));
            assertNull(data.get(U1, "k"));
        } finally {
            data.close();
        }
    }

    @Test
    public void expiredEntryIsCleanedFromSourceOnLoad() throws Exception {
        MemoryMountSource user = new MemoryMountSource("user");
        MemoryMountSource ws = new MemoryMountSource("ws");
        ws.writeString("u1__old.md", "---\nname: \"u1__old\"\ntime: \"2020-01-01 00:00:00\"\nimportance: 5\n"
                + "ttl: 1\nstored_at: \"2020-01-01 00:00:00\"\nscope: \"workspace\"\n---\n\n过期内容\n");
        ws.writeString("u1__new.md.tmp", "残留");

        MemoryMdData data = MemoryMdData.of(scopes(user, ws));
        try {
            assertNull(data.get(U1, "old"));
            assertFalse(ws.exists("u1__old.md"), "过期条目在加载时删除");
            assertFalse(ws.exists("u1__new.md.tmp"), "残留临时文件在加载时清理");
        } finally {
            data.close();
        }
    }

    @Test
    public void illegalKeyIsRejected() {
        MemoryMdData data = MemoryMdData.of(scopes(new MemoryMountSource(), new MemoryMountSource()));
        try {
            assertThrows(IllegalStateException.class,
                    () -> data.put(U1, "a/../../x", val("x", 5), -1, "workspace"));
        } finally {
            data.close();
        }
    }

    @Test
    public void emptySourceMapIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MemoryMdData.of(new LinkedHashMap<>()));
    }
}
