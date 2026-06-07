package features.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.cli.sandbox.SandboxConfig;
import org.noear.solon.ai.talents.mount.MountManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
