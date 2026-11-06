package features.ai.talents.mount;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.source.MountConflictException;
import org.noear.solon.ai.talents.mount.source.MountEntry;
import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.ai.talents.mount.source.MoveOptions;
import org.noear.solon.ai.talents.mount.source.WriteOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MountSource} 实现契约套件：任何来源（本地、内存、db、http）都必须跑通。
 *
 * <p>新实现只需继承本类并实现 {@link #create()}；能力位为 false 的项会被跳过。</p>
 */
public abstract class MountSourceContract {
    protected MountSource source;

    /** 创建一个空的、可写的来源。 */
    protected abstract MountSource create() throws Exception;

    @BeforeEach
    void setUp() throws Exception {
        source = create();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (source != null) {
            source.close();
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void normalizeRejectsEscape() {
        assertEquals("", source.normalize(""));
        assertEquals("a/b.md", source.normalize("/a/./b.md"));
        assertThrows(SecurityException.class, () -> source.normalize("../x"));
        assertThrows(SecurityException.class, () -> source.normalize("a/../../x"));
    }

    @Test
    void missingEntryContract() throws Exception {
        assertNull(source.stat("nope.txt"));
        assertFalse(source.exists("nope.txt"));
        assertNull(source.readStringOrNull("nope.txt"));
        assertThrows(IOException.class, () -> source.openRead("nope.txt"));
        assertFalse(source.deleteIfExists("nope.txt"));
    }

    @Test
    void writeReadRoundTripWithParents() throws Exception {
        source.writeString("a/b/c.txt", "你好");
        assertEquals("你好", source.readStringOrNull("a/b/c.txt"));
        assertTrue(source.isDirectory("a"));
        assertTrue(source.isDirectory("a/b"));
        MountEntry entry = source.stat("a/b/c.txt");
        assertNotNull(entry);
        assertFalse(entry.isDirectory());
        assertEquals("c.txt", entry.getName());
        assertEquals(utf8("你好").length, entry.getSize());
    }

    @Test
    void replaceAndAppend() throws Exception {
        source.writeString("f.txt", "one");
        source.writeString("f.txt", "two");
        assertEquals("two", source.readStringOrNull("f.txt"));
        source.writeBytes("f.txt", utf8("+3"), WriteOptions.builder().append(true).build());
        assertEquals("two+3", source.readStringOrNull("f.txt"));
    }

    @Test
    void listAndFind() throws Exception {
        source.writeString("d/x.md", "x");
        source.writeString("d/y.txt", "y");
        source.writeString("d/sub/z.md", "z");

        List<String> names = source.list("d").stream().map(MountEntry::getName).collect(Collectors.toList());
        assertEquals(java.util.Arrays.asList("sub", "x.md", "y.txt"), names);

        List<String> md = source.find("d", FindOptions.builder().glob("**/*.md").filesOnly(true).build())
                .stream().map(MountEntry::getPath).collect(Collectors.toList());
        assertEquals(java.util.Arrays.asList("d/sub/z.md"), md, "glob 相对搜索起点匹配");

        List<String> shallow = source.find("d", FindOptions.builder().maxDepth(1).filesOnly(true).build())
                .stream().map(MountEntry::getPath).collect(Collectors.toList());
        assertEquals(java.util.Arrays.asList("d/x.md", "d/y.txt"), shallow);

        assertThrows(IOException.class, () -> source.list("d/x.md"));
    }

    @Test
    void deleteAndMove() throws Exception {
        source.writeString("m/a.txt", "a");
        source.move("m/a.txt", "m/b.txt", MoveOptions.defaults());
        assertNull(source.stat("m/a.txt"));
        assertEquals("a", source.readStringOrNull("m/b.txt"));

        source.writeString("m/c.txt", "c");
        assertThrows(IOException.class, () -> source.move("m/c.txt", "m/b.txt", MoveOptions.defaults()));
        source.move("m/c.txt", "m/b.txt", MoveOptions.replaceExisting());
        assertEquals("c", source.readStringOrNull("m/b.txt"));

        assertTrue(source.deleteIfExists("m/b.txt"));
        assertNull(source.stat("m/b.txt"));
        assertThrows(SecurityException.class, () -> source.delete(""));
    }

    @Test
    void atomicWriteInvisibleUntilClose() throws Exception {
        if (!source.capabilities().isAtomicWrite()) return;
        source.writeString("w.txt", "old");
        try (OutputStream out = source.openWrite("w.txt", WriteOptions.replace())) {
            out.write(utf8("new-content"));
            out.flush();
            assertEquals("old", source.readStringOrNull("w.txt"), "close 之前读者只能看到旧内容");
        }
        assertEquals("new-content", source.readStringOrNull("w.txt"));

        List<String> names = source.list("").stream().map(MountEntry::getName).collect(Collectors.toList());
        assertEquals(java.util.Arrays.asList("w.txt"), names, "提交后不得残留临时条目");
    }

    @Test
    void versionChangesWithContentAndGuardsWrites() throws Exception {
        if (!source.capabilities().isVersioned()) return;
        source.writeString("v.txt", "1");
        String v1 = source.stat("v.txt").getVersion();
        assertNotNull(v1, "versioned 来源的文件条目必须有版本");

        // 版本匹配：允许写入
        source.writeBytes("v.txt", utf8("22"), WriteOptions.builder().expectedVersion(v1).build());
        String v2 = source.stat("v.txt").getVersion();
        assertNotEquals(v1, v2, "内容变化后版本必须变化");

        // 旧版本写入：冲突，内容不变
        MountConflictException ex = assertThrows(MountConflictException.class,
                () -> source.writeBytes("v.txt", utf8("333"), WriteOptions.builder().expectedVersion(v1).build()));
        assertEquals(v2, ex.getActualVersion());
        assertEquals("22", source.readStringOrNull("v.txt"));

        // 期望存在但已被删除：冲突
        source.delete("v.txt");
        assertThrows(MountConflictException.class,
                () -> source.writeBytes("v.txt", utf8("x"), WriteOptions.builder().expectedVersion(v2).build()));
        assertNull(source.stat("v.txt"), "冲突不得留下条目");
    }
}
