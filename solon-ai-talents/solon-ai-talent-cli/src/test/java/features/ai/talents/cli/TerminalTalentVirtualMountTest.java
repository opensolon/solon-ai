package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.mount.FindOptions;
import org.noear.solon.ai.talents.mount.MountCapabilities;
import org.noear.solon.ai.talents.mount.MountEntry;
import org.noear.solon.ai.talents.mount.MountSource;
import org.noear.solon.ai.talents.mount.MoveOptions;
import org.noear.solon.ai.talents.mount.WriteOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.noear.solon.ai.talents.mount.ClasspathMountSource;
import org.noear.solon.ai.talents.mount.FileMountSource;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 验证非本地来源不经过 Path/Files，而是通过 MountSource 访问。 */
public class TerminalTalentVirtualMountTest {
    @Test
    public void readsSearchesAndRejectsWritesOnClasspathMount() throws Exception {
        Path work = Files.createTempDirectory("terminal-virtual-mount");
        try {
            MountManager manager = new MountManager(work.toString());
            manager.register(Mount.builder()
                    .alias("@cp")
                    .type(MountType.FILES)
                    .source(ClasspathMountSource.of(
                            Thread.currentThread().getContextClassLoader(), "test-skills"))
                    .writeable(false)
                    .build());
            TerminalTalent terminal = new TerminalTalent(manager);

            String listing = terminal.ls("@cp/demo", false, false, null);
            assertTrue(listing.contains("SKILL.md"));

            String content = terminal.read("@cp/demo/SKILL.md", null, null, null);
            assertTrue(content.contains("Demo classpath skill"));

            String grep = terminal.grep("classpath", "@cp/demo", null, null);
            assertTrue(grep.contains("SKILL.md"));

            String glob = terminal.glob("**/*.md", "@cp/demo", null);
            assertTrue(glob.contains("SKILL.md"));

            assertThrows(SecurityException.class,
                    () -> terminal.write("@cp/demo/new.txt", "no", null));
        } finally {
            Files.walk(work).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void localSourceMountHonorsPermissionsAndRealNewlines() throws Exception {
        Path work = Files.createTempDirectory("terminal-local-source");
        try {
            Files.write(work.resolve("a.txt"), "hello\nworld".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(work.resolve("b.txt"), "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            MountManager manager = new MountManager(work.toString());
            manager.register(Mount.builder().alias("@local").type(MountType.FILES)
                    .source(FileMountSource.of(work)).writeable(false).build());
            TerminalTalent terminal = new TerminalTalent(manager);
            assertTrue(terminal.ls("@local", false, false, null).contains("\n"));
            assertTrue(terminal.read("@local/a.txt", null, null, null).contains("hello\n"));
            assertThrows(SecurityException.class, () -> terminal.write("@local/a.txt", "bad", null));
            assertTrue(terminal.grep("world", "@local", null, null).contains("world"));
        } finally {
            Files.walk(work).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void editRejectsConcurrentChangeWhenSourceProvidesVersion() throws Exception {
        Path work = Files.createTempDirectory("terminal-versioned-mount");
        try {
            VersionedSource source = new VersionedSource();
            MountManager manager = new MountManager(work.toString());
            manager.register(Mount.builder().alias("@db").type(MountType.FILES)
                    .source(source).writeable(true).build());
            TerminalTalent terminal = new TerminalTalent(manager);
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStr = "original";
            edit.newStr = "edited";
            edit.oldStrStartLine = 1;

            source.changeOnReadClose = true;
            IOException conflict = assertThrows(IOException.class,
                    () -> terminal.edit("@db/note.txt", Collections.singletonList(edit), null));
            assertTrue(conflict.getMessage().contains("version conflict"));
            assertEquals("newer", source.content);

            source.changeOnReadClose = false;
            edit.oldStr = "newer";
            assertTrue(terminal.edit("@db/note.txt", Collections.singletonList(edit), null).contains("成功"));
            assertEquals("edited", source.content);
        } finally {
            Files.delete(work);
        }
    }

    @Test
    public void unknownSizeCannotBypassVirtualReadLimit() throws Exception {
        Path work = Files.createTempDirectory("terminal-unknown-size-mount");
        try {
            byte[] oversized = new byte[10 * 1024 * 1024 + 1];
            java.util.Arrays.fill(oversized, (byte) 'x');
            VersionedSource source = new VersionedSource() {
                @Override public MountEntry stat(String path) {
                    return new MountEntry(path, path, false, -1, null, null);
                }
                @Override public InputStream openRead(String path) {
                    return new ByteArrayInputStream(oversized);
                }
            };
            MountManager manager = new MountManager(work.toString());
            manager.register(Mount.builder().alias("@unknown").type(MountType.FILES)
                    .source(source).writeable(true).build());
            TerminalTalent terminal = new TerminalTalent(manager);
            assertTrue(terminal.read("@unknown/note.txt", null, null, null).contains("超过单文件读取上限"));
            TerminalTalent.EditOp edit = new TerminalTalent.EditOp();
            edit.oldStr = "x";
            edit.newStr = "y";
            edit.oldStrStartLine = 1;
            assertTrue(terminal.edit("@unknown/note.txt", Collections.singletonList(edit), null)
                    .contains("超过单文件读取上限"));
            assertTrue(terminal.grep("x", "@unknown/note.txt", null, null)
                    .contains("跳过 1 个超过单文件读取上限"));
        } finally {
            Files.delete(work);
        }
    }

    @Test
    public void virtualReadPagesLongOutput() throws Exception {
        Path work = Files.createTempDirectory("terminal-virtual-pagination");
        try {
            VersionedSource source = new VersionedSource();
            source.content = "first\nsecond\nthird\nfourth\nfifth";
            MountManager manager = new MountManager(work.toString());
            manager.register(Mount.builder().alias("@text").type(MountType.FILES)
                    .source(source).writeable(false).build());
            TerminalTalent terminal = new TerminalTalent(manager);
            terminal.setMaxCharacterLimit(18);
            String firstPage = terminal.read("@text/note.txt", null, null, null);
            assertTrue(firstPage.contains("offset=2"), firstPage);
            assertFalse(firstPage.contains("second"), firstPage);
            assertTrue(terminal.read("@text/note.txt", 2, 1, null).contains("second"));
        } finally {
            Files.delete(work);
        }
    }

    private static class VersionedSource implements MountSource {
        String content = "original";
        int version;
        boolean changeOnReadClose;

        @Override public String getScheme() { return "test"; }
        @Override public String normalize(String path) { return path; }
        @Override public MountEntry stat(String path) {
            return new MountEntry(path, path, false, content.length(), null, String.valueOf(version));
        }
        @Override public List<MountEntry> list(String path) { return Collections.emptyList(); }
        @Override public List<MountEntry> find(String path, FindOptions options) { return Collections.emptyList(); }
        @Override public InputStream openRead(String path) {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)) {
                @Override public void close() throws IOException {
                    super.close();
                    if (changeOnReadClose) {
                        content = "newer";
                        version++;
                    }
                }
            };
        }
        @Override public OutputStream openWrite(String path, WriteOptions options) {
            return new ByteArrayOutputStream() {
                @Override public void close() throws IOException {
                    super.close();
                    if (options.getExpectedVersion() != null
                            && !options.getExpectedVersion().equals(String.valueOf(version))) {
                        throw new IOException("version conflict");
                    }
                    content = toString("UTF-8");
                    version++;
                }
            };
        }
        @Override public void delete(String path) { }
        @Override public void move(String source, String target, MoveOptions options) { }
        @Override public MountCapabilities capabilities() {
            return new MountCapabilities(true, true, true, true, false, false,
                    false, false, false, false);
        }
    }
}
