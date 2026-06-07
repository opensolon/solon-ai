package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.cli.sandbox.SandboxConfig;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TerminalTalentSandboxPolicyTest {

    @Test
    public void writeRejectsMandatoryDenyPathWhenSandboxConfigEnabled() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        try {
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            talent.setSandboxConfig(new SandboxConfig());

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.write("sub/.bashrc", "evil", workDir.toString()));
            assertTrue(ex.getMessage().contains("路径受保护"), ex.getMessage());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void writeRejectsPathOutsideAllowWriteWhenSandboxConfigEnabled() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        try {
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            SandboxConfig config = new SandboxConfig();
            config.getFilesystem().setAllowWrite(Collections.singletonList("src"));
            talent.setSandboxConfig(config);

            talent.write("src/App.java", "class App {}", workDir.toString());
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.write("README.md", "blocked", workDir.toString()));
            assertTrue(ex.getMessage().contains("可写白名单"), ex.getMessage());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void readRejectsConfiguredDenyReadUnlessAllowed() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        try {
            Files.createDirectories(workDir.resolve("secret/public"));
            Files.write(workDir.resolve("secret/private.txt"), Collections.singletonList("hidden"));
            Files.write(workDir.resolve("secret/public/visible.txt"), Collections.singletonList("visible"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            SandboxConfig config = new SandboxConfig();
            config.getFilesystem().setDenyRead(Collections.singletonList("secret"));
            config.getFilesystem().setAllowRead(Collections.singletonList("secret/public"));
            talent.setSandboxConfig(config);

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.read("secret/private.txt", 1, null, workDir.toString()));
            assertTrue(ex.getMessage().contains("读取拒绝"), ex.getMessage());
            assertTrue(talent.read("secret/public/visible.txt", 1, null, workDir.toString()).contains("visible"));
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void globSkipsConfiguredDenyReadSubtrees() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        try {
            Files.createDirectories(workDir.resolve("secret"));
            Files.write(workDir.resolve("secret/private.txt"), Collections.singletonList("hidden"));
            Files.write(workDir.resolve("visible.txt"), Collections.singletonList("visible"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            SandboxConfig config = new SandboxConfig();
            config.getFilesystem().setDenyRead(Collections.singletonList("secret"));
            talent.setSandboxConfig(config);

            String result = talent.glob("**/*.txt", ".", workDir.toString());
            assertTrue(result.contains("visible.txt"), result);
            assertTrue(!result.contains("private.txt"), result);
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void rootPathHonorsDenyReadPolicy() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        try {
            Files.write(workDir.resolve("visible.txt"), Collections.singletonList("visible"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            SandboxConfig config = new SandboxConfig();
            config.getFilesystem().setDenyRead(Collections.singletonList("."));
            talent.setSandboxConfig(config);

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.ls(".", false, true, workDir.toString()));
            assertTrue(ex.getMessage().contains("读取拒绝"), ex.getMessage());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void rootPathHonorsAllowWritePolicy() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        try {
            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            SandboxConfig config = new SandboxConfig();
            config.getFilesystem().setAllowWrite(Collections.emptyList());
            talent.setSandboxConfig(config);

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.write(".", "blocked", workDir.toString()));
            assertTrue(ex.getMessage().contains("可写白名单"), ex.getMessage());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void editRequiresReadAndWritePermission() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        try {
            Files.createDirectories(workDir.resolve("secret"));
            Files.write(workDir.resolve("secret/private.txt"), Collections.singletonList("old"));

            TerminalTalent talent = new TerminalTalent(new MountManager(workDir.toString()));
            SandboxConfig config = new SandboxConfig();
            config.getFilesystem().setAllowWrite(Collections.singletonList("secret"));
            config.getFilesystem().setDenyRead(Collections.singletonList("secret"));
            talent.setSandboxConfig(config);

            TerminalTalent.EditOp op = new TerminalTalent.EditOp();
            op.oldStr = "old";
            op.newStr = "new";

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.edit("secret/private.txt", Collections.singletonList(op), workDir.toString()));
            assertTrue(ex.getMessage().contains("读取拒绝"), ex.getMessage());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Test
    public void readWriteMountHonorsMountWritableFlag() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        Path mountDir = Files.createTempDirectory("solon-ai-terminal-mount-");
        try {
            Files.write(mountDir.resolve("note.txt"), Collections.singletonList("mounted"));
            MountManager mountManager = new MountManager(workDir.toString());
            mountManager.register(MountDir.builder()
                    .alias("@pool")
                    .path(mountDir.toString())
                    .type(MountType.SKILLS)
                    .writeable(false)
                    .build());

            TerminalTalent talent = new TerminalTalent(mountManager);
            talent.setSandboxConfig(new SandboxConfig());

            assertTrue(talent.read("@pool/note.txt", 1, null, workDir.toString()).contains("mounted"));
            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.write("@pool/new.txt", "blocked", workDir.toString()));
            assertTrue(ex.getMessage().contains("只读挂载点"), ex.getMessage());
        } finally {
            deleteRecursively(workDir);
            deleteRecursively(mountDir);
        }
    }

    @Test
    public void writableMountAllowsWriteWhenSandboxConfigAllowsMountRoot() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        Path mountDir = Files.createTempDirectory("solon-ai-terminal-mount-");
        try {
            MountManager mountManager = new MountManager(workDir.toString());
            mountManager.register(MountDir.builder()
                    .alias("@pool")
                    .path(mountDir.toString())
                    .type(MountType.SKILLS)
                    .writeable(true)
                    .build());

            TerminalTalent talent = new TerminalTalent(mountManager);
            SandboxConfig config = new SandboxConfig();
            config.getFilesystem().setAllowWrite(Collections.singletonList("."));
            talent.setSandboxConfig(config);

            talent.write("@pool/new.txt", "mounted write", workDir.toString());
            assertTrue(new String(Files.readAllBytes(mountDir.resolve("new.txt"))).contains("mounted write"));
        } finally {
            deleteRecursively(workDir);
            deleteRecursively(mountDir);
        }
    }

    @Test
    public void globOnMountUsesMountRootForDenyReadPolicy() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        Path mountDir = Files.createTempDirectory("solon-ai-terminal-mount-");
        try {
            Files.createDirectories(mountDir.resolve("secret"));
            Files.write(mountDir.resolve("secret/private.txt"), Collections.singletonList("hidden"));
            Files.write(mountDir.resolve("visible.txt"), Collections.singletonList("visible"));

            MountManager mountManager = new MountManager(workDir.toString());
            mountManager.register(MountDir.builder()
                    .alias("@pool")
                    .path(mountDir.toString())
                    .type(MountType.SKILLS)
                    .writeable(false)
                    .build());

            TerminalTalent talent = new TerminalTalent(mountManager);
            SandboxConfig config = new SandboxConfig();
            config.getFilesystem().setDenyRead(Collections.singletonList("secret"));
            talent.setSandboxConfig(config);

            String result = talent.glob("**/*.txt", "@pool", workDir.toString());
            assertTrue(result.contains("visible.txt"), result);
            assertTrue(!result.contains("private.txt"), result);
        } finally {
            deleteRecursively(workDir);
            deleteRecursively(mountDir);
        }
    }

    @Test
    public void mountAliasResolutionRequiresExactPathBoundary() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        Path poolDir = Files.createTempDirectory("solon-ai-terminal-pool-");
        Path pool1Dir = Files.createTempDirectory("solon-ai-terminal-pool1-");
        try {
            Files.write(poolDir.resolve("note.txt"), Collections.singletonList("pool"));
            Files.write(pool1Dir.resolve("note.txt"), Collections.singletonList("pool1"));

            MountManager mountManager = new MountManager(workDir.toString());
            mountManager.register(MountDir.builder()
                    .alias("@pool")
                    .path(poolDir.toString())
                    .type(MountType.SKILLS)
                    .writeable(false)
                    .build());
            mountManager.register(MountDir.builder()
                    .alias("@pool1")
                    .path(pool1Dir.toString())
                    .type(MountType.SKILLS)
                    .writeable(false)
                    .build());

            TerminalTalent talent = new TerminalTalent(mountManager);
            talent.setSandboxConfig(new SandboxConfig());

            String result = talent.read("@pool1/note.txt", 1, null, workDir.toString());
            assertTrue(result.contains("pool1"), result);
            assertTrue(!result.contains("pool |"), result);
        } finally {
            deleteRecursively(workDir);
            deleteRecursively(poolDir);
            deleteRecursively(pool1Dir);
        }
    }

    @Test
    public void disabledMountIsRejected() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        Path mountDir = Files.createTempDirectory("solon-ai-terminal-mount-");
        try {
            Files.write(mountDir.resolve("note.txt"), Collections.singletonList("disabled"));
            MountManager mountManager = new MountManager(workDir.toString());
            mountManager.register(MountDir.builder()
                    .alias("@pool")
                    .path(mountDir.toString())
                    .type(MountType.SKILLS)
                    .enabled(false)
                    .writeable(false)
                    .build());

            TerminalTalent talent = new TerminalTalent(mountManager);
            talent.setSandboxConfig(new SandboxConfig());

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.read("@pool/note.txt", 1, null, workDir.toString()));
            assertTrue(ex.getMessage().contains("未知的挂载点"), ex.getMessage());
        } finally {
            deleteRecursively(workDir);
            deleteRecursively(mountDir);
        }
    }

    @Test
    public void writeThroughMountSymlinkParentOutsideIsRejected() throws Exception {
        Path workDir = Files.createTempDirectory("solon-ai-terminal-sandbox-");
        Path mountDir = Files.createTempDirectory("solon-ai-terminal-mount-");
        Path outsideDir = Files.createTempDirectory("solon-ai-terminal-outside-");
        try {
            Path link = mountDir.resolve("link-out");
            try {
                Files.createSymbolicLink(link, outsideDir);
            } catch (UnsupportedOperationException | SecurityException | java.nio.file.FileSystemException e) {
                assumeTrue(false, "Symbolic links are not available in this environment: " + e.getMessage());
            }

            MountManager mountManager = new MountManager(workDir.toString());
            mountManager.register(MountDir.builder()
                    .alias("@pool")
                    .path(mountDir.toString())
                    .type(MountType.SKILLS)
                    .writeable(true)
                    .build());

            TerminalTalent talent = new TerminalTalent(mountManager);
            SandboxConfig config = new SandboxConfig();
            config.getFilesystem().setAllowWrite(Collections.singletonList("."));
            talent.setSandboxConfig(config);

            SecurityException ex = assertThrows(SecurityException.class,
                    () -> talent.write("@pool/link-out/new.txt", "escape", workDir.toString()));
            assertTrue(ex.getMessage().contains("符号链接越界"), ex.getMessage());
            assertTrue(!Files.exists(outsideDir.resolve("new.txt")));
        } finally {
            deleteRecursively(workDir);
            deleteRecursively(mountDir);
            deleteRecursively(outsideDir);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        ArrayList<Path> paths = new ArrayList<>();
        Files.walk(root).forEach(paths::add);
        Collections.sort(paths, Collections.reverseOrder());
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
