package features.ai.talents.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.cli.ProcessExecutor;

/**
 * 验证 executeCode 临时脚本卫生：写到系统 temp，不污染工作区，执行后清理。
 */
public class ProcessExecutorTempScriptTest {

    @TempDir
    Path workDir;

    @Test
    public void executeCode_doesNotLeaveScriptInWorkDir() throws Exception {
        assumeTrue(!isWindows(), "Unix shell test");

        Set<String> before = listFileNames(workDir);

        ProcessExecutor executor = new ProcessExecutor();
        String result = executor.executeCode(
                workDir,
                "echo hello-from-temp-script\n",
                "bash",
                ".sh",
                null,
                10_000,
                64_000,
                null
        );

        assertTrue(result.contains("hello-from-temp-script") || "执行成功".equals(result),
                "脚本应正常执行, result=" + result);

        Set<String> after = listFileNames(workDir);
        Set<String> created = new HashSet<>(after);
        created.removeAll(before);

        assertTrue(created.stream().noneMatch(n -> n.startsWith("_script_") || n.startsWith("solon-ai-script-")),
                "工作区不应出现临时脚本文件: " + created);
    }

    @Test
    public void executeCode_usesSystemTempAndCleansUp() throws Exception {
        assumeTrue(!isWindows(), "Unix shell test");

        Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Set<String> before = listSolonScriptNames(tmpDir);

        ProcessExecutor executor = new ProcessExecutor();
        String result = executor.executeCode(
                workDir,
                "pwd\necho ok\n",
                "bash",
                ".sh",
                null,
                10_000,
                64_000,
                null
        );

        assertTrue(result.contains("ok") || "执行成功".equals(result), "脚本应正常执行, result=" + result);

        // finally 应已删除；若有并发残留，也不应持续增长到不可控
        Set<String> after = listSolonScriptNames(tmpDir);
        Set<String> leftover = new HashSet<>(after);
        leftover.removeAll(before);

        // 正常路径下本次创建的脚本应已被 deleteIfExists
        assertTrue(leftover.isEmpty() || leftover.stream().allMatch(n -> n.startsWith("solon-ai-script-")),
                "若有残留，前缀必须是 solon-ai-script-: " + leftover);
        assertTrue(leftover.isEmpty(), "执行完成后系统 temp 不应残留本次 solon-ai-script- 文件: " + leftover);
    }

    @Test
    public void executeCode_keepsWorkingDirectorySemantics() throws Exception {
        assumeTrue(!isWindows(), "Unix shell test");

        Path marker = workDir.resolve("marker.txt");
        Files.write(marker, "x".getBytes());

        ProcessExecutor executor = new ProcessExecutor();
        // 相对路径应基于 rootPath（工作区），而非脚本所在系统 temp
        String result = executor.executeCode(
                workDir,
                "test -f marker.txt && echo WORKDIR_OK\n",
                "bash",
                ".sh",
                null,
                10_000,
                64_000,
                null
        );

        assertTrue(result.contains("WORKDIR_OK"), "工作目录语义应保持为 rootPath, result=" + result);
    }

    @Test
    public void createTempScript_usesPrefixAndOwnerOnlyPermsOnPosix() throws Exception {
        assumeTrue(!isWindows(), "POSIX permission test");

        // 通过反射调用 private createTempScript，验证前缀与权限策略
        java.lang.reflect.Method m = ProcessExecutor.class.getDeclaredMethod("createTempScript", String.class);
        m.setAccessible(true);
        Path tempScript = (Path) m.invoke(null, ".sh");
        try {
            assertTrue(tempScript.getFileName().toString().startsWith("solon-ai-script-"),
                    "临时脚本前缀应为 solon-ai-script-");
            assertTrue(tempScript.toAbsolutePath().startsWith(
                            Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()),
                    "应落在系统临时目录");

            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tempScript);
            assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
            assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ));
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ));
        } finally {
            Files.deleteIfExists(tempScript);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static Set<String> listFileNames(Path dir) throws Exception {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private static Set<String> listSolonScriptNames(Path tmpDir) throws Exception {
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(tmpDir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir, "solon-ai-script-*")) {
            for (Path p : stream) {
                names.add(p.getFileName().toString());
            }
        }
        return names;
    }
}
