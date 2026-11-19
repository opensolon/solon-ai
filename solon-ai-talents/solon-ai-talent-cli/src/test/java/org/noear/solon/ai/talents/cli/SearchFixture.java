package org.noear.solon.ai.talents.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * 搜索差分测试用的目录树：固定的受保护/忽略/链接场景 + 按种子随机生成的目录与文件。
 */
final class SearchFixture {
    private SearchFixture() {
    }

    static final boolean WINDOWS = File.separatorChar == '\\';

    private static final String[] DIRS = {
            "src", "main", "java", "a", "b", "lib", "docs", "secret", "keys", "sub",
            ".vscode", ".idea", ".git", "hooks", "target", "node_modules", ".solon",
            "commands", "agents", "sessions", "cache-root", "mount", "中文目录", "with space", "Src"
    };

    private static final String[] FILES = {
            "x.java", "Foo.java", "y.txt", "README.md", ".bashrc", ".mcp.json", ".gitconfig",
            ".DS_Store", "note.txt", "中文.txt", "a b.txt", "data.bin", "Y.TXT"
    };

    /** 写入固定场景（工作区）。 */
    static void fixed(Path ws) throws IOException {
        write(ws.resolve("src/main/java/Foo.java"), "class Foo { // TODO token\n}");
        write(ws.resolve("src/main/java/org/Bar.java"), "class Bar {} // token");
        write(ws.resolve("src/test/java/FooTest.java"), "token test");
        write(ws.resolve(".bashrc"), "token bashrc");
        write(ws.resolve("sub/.mcp.json"), "{\"token\":1}");
        write(ws.resolve("sub/.vscode/settings.json"), "token vscode");
        write(ws.resolve("sub/.git/hooks/pre-commit"), "token hook");
        write(ws.resolve("deep/.git/hooks/x.java"), "token hook java");
        write(ws.resolve(".solon/commands/c.md"), "token command");
        write(ws.resolve("nested/.solon/commands/c.md"), "token nested command");
        write(ws.resolve("nested/.solon/agents/a.md"), "token nested agent");
        write(ws.resolve("nested/.solon/sessions/s1/t.md"), "token nested session");
        write(ws.resolve("nested/.solon/skills/ok.md"), "token nested skill");
        write(ws.resolve("secret/keys/k.txt"), "token key");
        write(ws.resolve("secret/plain.txt"), "token plain");
        write(ws.resolve("cache-root/mount/m.txt"), "token cache");
        write(ws.resolve("cache-root/other/o.txt"), "token cache-other");
        write(ws.resolve("target/classes/X.java"), "token target");
        write(ws.resolve("node_modules/pkg/index.java"), "token nm");
        write(ws.resolve("a/b/c/d.txt"), "token deep");
        write(ws.resolve("docs/读我.md"), "token 中文");
        write(ws.resolve("bom.txt"), "\uFEFFtoken bom");
        if (!WINDOWS) {
            write(ws.resolve("odd\\name.txt"), "token backslash");
        }
    }

    /** 尝试创建链接场景；不支持符号链接时返回 false。 */
    static boolean links(Path ws, Path outside, Path pool) throws IOException {
        write(outside.resolve("o.txt"), "token outside");
        write(outside.resolve("inner/o.java"), "token outside java");
        try {
            Files.createSymbolicLink(ws.resolve("link-in-file.java"), ws.resolve("src/main/java/Foo.java"));
        } catch (UnsupportedOperationException | SecurityException | FileSystemException e) {
            return false;
        }
        Files.createSymbolicLink(ws.resolve("link-out-file.txt"), outside.resolve("o.txt"));
        Files.createSymbolicLink(ws.resolve("link-in-dir"), ws.resolve("secret"));
        Files.createSymbolicLink(ws.resolve("link-out-dir"), outside);
        Files.createSymbolicLink(ws.resolve("broken.txt"), ws.resolve("nope.txt"));
        Files.createSymbolicLink(ws.resolve("safe-link"), ws.resolve(".bashrc"));
        Files.createSymbolicLink(ws.resolve("src/link-cache"), ws.resolve("cache-root"));
        Files.createSymbolicLink(ws.resolve("src/link-cache-file.txt"), ws.resolve("cache-root/mount/m.txt"));
        Files.createSymbolicLink(ws.resolve("a/link-up"), ws);
        Files.createSymbolicLink(ws.resolve("link-vscode"), ws.resolve("sub/.vscode"));
        Files.createSymbolicLink(ws.resolve("link-commands"), ws.resolve("nested/.solon/commands"));
        Files.createSymbolicLink(ws.resolve("link-cmd-file.md"), ws.resolve("nested/.solon/commands/c.md"));
        Files.createSymbolicLink(pool.resolve("link-ws"), ws);
        Files.createSymbolicLink(pool.resolve("link-ws-foo.java"), ws.resolve("src/main/java/Foo.java"));
        return true;
    }

    /** 按种子随机生成目录与文件。 */
    static void random(Path root, long seed, int maxDepth) throws IOException {
        random(root, new Random(seed), 0, maxDepth);
    }

    private static void random(Path dir, Random r, int depth, int maxDepth) throws IOException {
        Files.createDirectories(dir);
        int files = r.nextInt(4);
        for (int i = 0; i < files; i++) {
            Path f = dir.resolve(FILES[r.nextInt(FILES.length)]);
            if (!Files.exists(f)) {
                write(f, "line1\ntoken " + f.getFileName() + "\nTODO end");
            }
        }
        if (depth >= maxDepth) {
            return;
        }
        int dirs = r.nextInt(4);
        for (int i = 0; i < dirs; i++) {
            Path d = dir.resolve(DIRS[r.nextInt(DIRS.length)]);
            // 大小写不敏感的文件系统上 src / Src 会落到同一目录，存在即复用
            if (Files.exists(d) && !Files.isDirectory(d)) {
                continue;
            }
            random(d, r, depth + 1, maxDepth);
        }
    }

    static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }
}
