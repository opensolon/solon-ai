package org.noear.solon.ai.mount.testkit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.mount.source.FindOptions;
import org.noear.solon.ai.mount.source.MountConflictException;
import org.noear.solon.ai.mount.source.MountEntry;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.source.MoveOptions;
import org.noear.solon.ai.mount.source.WriteOptions;

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
 *
 * <p>本类随 {@code solon-ai-mount} 的 test-jar 发布（只含 testkit 包），第三方实现这样引入：</p>
 * <pre>{@code
 * <dependency>
 *     <groupId>org.noear</groupId>
 *     <artifactId>solon-ai-mount</artifactId>
 *     <version>${solon-ai.version}</version>
 *     <type>test-jar</type>
 *     <scope>test</scope>
 * </dependency>
 * }</pre>
 * <p>需要自备 JUnit 5（如 solon-test）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public abstract class MountSourceContract {
    protected MountSource source;

    /** 创建一个空的、可写的来源。 */
    protected abstract MountSource create() throws Exception;

    @BeforeEach
    public void setUp() throws Exception {
        source = create();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (source != null) {
            source.close();
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void normalizeRejectsEscape() {
        assertEquals("", source.normalize(""));
        assertEquals("a/b.md", source.normalize("/a/./b.md"));
        assertThrows(SecurityException.class, () -> source.normalize("../x"));
        assertThrows(SecurityException.class, () -> source.normalize("a/../../x"));
    }

    @Test
    public void missingEntryContract() throws Exception {
        assertNull(source.stat("nope.txt"));
        assertFalse(source.exists("nope.txt"));
        assertNull(source.readStringOrNull("nope.txt"));
        assertThrows(IOException.class, () -> source.openRead("nope.txt"));
        assertFalse(source.deleteIfExists("nope.txt"));
    }

    @Test
    public void writeReadRoundTripWithParents() throws Exception {
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
    public void replaceAndAppend() throws Exception {
        source.writeString("f.txt", "one");
        source.writeString("f.txt", "two");
        assertEquals("two", source.readStringOrNull("f.txt"));
        source.writeBytes("f.txt", utf8("+3"), WriteOptions.builder().append(true).build());
        assertEquals("two+3", source.readStringOrNull("f.txt"));
    }

    @Test
    public void listAndFind() throws Exception {
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
    public void deleteAndMove() throws Exception {
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
        assertThrows(IOException.class, () -> source.move("m/none.txt", "m/x.txt", MoveOptions.defaults()),
                "来源不存在时移动失败");
    }

    @Test
    public void deleteIsNotRecursive() throws Exception {
        source.writeString("tree/a.txt", "a");
        source.writeString("tree/sub/b.txt", "b");

        assertThrows(IOException.class, () -> source.delete("tree"), "非空目录不得被删除");
        assertEquals("a", source.readStringOrNull("tree/a.txt"), "删除失败时内容保持不变");
        assertEquals("b", source.readStringOrNull("tree/sub/b.txt"));

        source.delete("tree/sub/b.txt");
        source.deleteIfExists("tree/sub"); // 显式目录的来源需要删空目录；隐式目录的来源此时已不存在
        source.delete("tree/a.txt");
        source.deleteIfExists("tree");
        assertNull(source.stat("tree"), "逐级删空后目录消失");
    }

    @Test
    public void moveAcrossDirectories() throws Exception {
        source.writeString("from/f.txt", "f");
        source.move("from/f.txt", "to/deep/f2.txt", MoveOptions.defaults());
        assertNull(source.stat("from/f.txt"));
        assertEquals("f", source.readStringOrNull("to/deep/f2.txt"), "目标上级目录自动创建");
    }

    @Test
    public void moveDirectoryCarriesContents() throws Exception {
        source.writeString("src/a.txt", "a");
        source.writeString("src/sub/b.txt", "b");

        source.move("src", "dst/inner", MoveOptions.defaults());
        assertNull(source.stat("src/a.txt"));
        assertNull(source.stat("src/sub/b.txt"));
        assertEquals("a", source.readStringOrNull("dst/inner/a.txt"));
        assertEquals("b", source.readStringOrNull("dst/inner/sub/b.txt"));

        source.writeString("other/c.txt", "c");
        assertThrows(IOException.class, () -> source.move("other", "dst/inner", MoveOptions.defaults()),
                "目标已存在且未要求替换时失败");
        assertEquals("c", source.readStringOrNull("other/c.txt"), "失败时两边保持不变");
        assertEquals("a", source.readStringOrNull("dst/inner/a.txt"));
    }

    @Test
    public void atomicWriteInvisibleUntilClose() throws Exception {
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
    public void versionChangesWithContentAndGuardsWrites() throws Exception {
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
