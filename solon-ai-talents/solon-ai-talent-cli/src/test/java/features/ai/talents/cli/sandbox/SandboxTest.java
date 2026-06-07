package features.ai.talents.cli.sandbox;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.sandbox.*;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 沙盒组件单元测试
 */
public class SandboxTest {

    // ==================== ShellQuote ====================

    @Test
    public void shellQuote_simpleArg() {
        assertEquals("hello", ShellQuote.quote("hello"));
    }

    @Test
    public void shellQuote_safeChars() {
        assertEquals("foo-bar_baz@123:456%+=,./qux", ShellQuote.quote("foo-bar_baz@123:456%+=,./qux"));
    }

    @Test
    public void shellQuote_emptyString() {
        assertEquals("''", ShellQuote.quote(""));
    }

    @Test
    public void shellQuote_nullString() {
        assertEquals("''", ShellQuote.quote((String) null));
    }

    @Test
    public void shellQuote_stringWithSpaces() {
        assertEquals("'hello world'", ShellQuote.quote("hello world"));
    }

    @Test
    public void shellQuote_stringWithSingleQuote() {
        assertEquals("'don'\\''t'", ShellQuote.quote("don't"));
    }

    @Test
    public void shellQuote_stringWithDollar() {
        assertEquals("'$HOME'", ShellQuote.quote("$HOME"));
    }

    @Test
    public void shellQuote_pathWithSlash() {
        assertEquals("/usr/bin/bash", ShellQuote.quote("/usr/bin/bash"));
    }

    @Test
    public void shellQuote_array() {
        String result = ShellQuote.quote(new String[]{"echo", "hello world", "it's me"});
        assertEquals("echo 'hello world' 'it'\\''s me'", result);
    }

    // ==================== SandboxFsConfig ====================

    @Test
    public void fsConfig_defaultAllowWrite() {
        SandboxFsConfig config = new SandboxFsConfig();
        assertEquals(Arrays.asList(".", "/tmp"), config.getAllowWrite());
    }

    @Test
    public void fsConfig_effectiveDenyWrite_includesMandatory() {
        SandboxFsConfig config = new SandboxFsConfig();
        List<String> effective = config.getEffectiveDenyWrite("/workspace");
        assertTrue(effective.stream().anyMatch(p -> p.contains(".bashrc")),
                "Should deny .bashrc: " + effective);
        assertTrue(effective.stream().anyMatch(p -> p.contains(".gitconfig")),
                "Should deny .gitconfig: " + effective);
        assertTrue(effective.stream().anyMatch(p -> p.contains(".vscode")),
                "Should deny .vscode: " + effective);
    }

    @Test
    public void fsConfig_effectiveDenyWrite_mergesUserDeny() {
        SandboxFsConfig config = new SandboxFsConfig();
        config.setDenyWrite(Arrays.asList("/custom/secret"));
        List<String> effective = config.getEffectiveDenyWrite("/workspace");
        assertTrue(effective.contains("/custom/secret"));
        assertTrue(effective.stream().anyMatch(p -> p.contains(".bashrc")));
    }

    @Test
    public void fsConfig_isMandatoryDenyPath_bashrc() {
        assertTrue(SandboxFsConfig.isMandatoryDenyPath(".bashrc"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath("./.bashrc"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath("subdir/.bashrc"));
    }

    @Test
    public void fsConfig_isMandatoryDenyPath_normalFile() {
        assertFalse(SandboxFsConfig.isMandatoryDenyPath("README.md"));
        assertFalse(SandboxFsConfig.isMandatoryDenyPath("src/Main.java"));
    }

    @Test
    public void fsConfig_isMandatoryDenyPath_null() {
        assertFalse(SandboxFsConfig.isMandatoryDenyPath(null));
    }

    // ==================== SandboxNetConfig ====================

    @Test
    public void netConfig_emptyAllowedBlocksAll() {
        SandboxNetConfig config = new SandboxNetConfig();
        assertFalse(config.isDomainAllowed("example.com"));
    }

    @Test
    public void netConfig_allowSpecificDomain() {
        SandboxNetConfig config = new SandboxNetConfig();
        config.setAllowedDomains(Arrays.asList("example.com", "api.example.com"));
        assertTrue(config.isDomainAllowed("example.com"));
        assertTrue(config.isDomainAllowed("api.example.com"));
        assertFalse(config.isDomainAllowed("other.com"));
    }

    @Test
    public void netConfig_wildcardDomain() {
        SandboxNetConfig config = new SandboxNetConfig();
        config.setAllowedDomains(Arrays.asList("*.example.com"));
        assertTrue(config.isDomainAllowed("sub.example.com"));
        assertTrue(config.isDomainAllowed("example.com"));
        assertFalse(config.isDomainAllowed("other.com"));
    }

    @Test
    public void netConfig_denyOverridesAllow() {
        SandboxNetConfig config = new SandboxNetConfig();
        config.setAllowedDomains(Arrays.asList("example.com"));
        config.setDeniedDomains(Arrays.asList("evil.example.com"));
        assertTrue(config.isDomainAllowed("example.com"));
        assertFalse(config.isDomainAllowed("evil.example.com"));
    }

    @Test
    public void netConfig_nullHost() {
        SandboxNetConfig config = new SandboxNetConfig();
        assertFalse(config.isDomainAllowed(null));
    }

    // ==================== SandboxConfig ====================

    @Test
    public void sandboxConfig_defaults() {
        SandboxConfig config = new SandboxConfig();
        assertNotNull(config.getFilesystem());
        assertNotNull(config.getNetwork());
    }

    // ==================== SandboxViolationStore ====================

    @Test
    public void violationStore_addAndGet() {
        SandboxViolationStore store = new SandboxViolationStore();
        store.addViolation(new SandboxViolationStore.ViolationEvent("msg1", "cmd1", java.time.Instant.now()));
        assertEquals(1, store.size());
        assertFalse(store.getViolations().isEmpty());
    }

    @Test
    public void violationStore_getForCommand() {
        SandboxViolationStore store = new SandboxViolationStore();
        store.addViolation(new SandboxViolationStore.ViolationEvent("msg1", "cmd1", java.time.Instant.now()));
        store.addViolation(new SandboxViolationStore.ViolationEvent("msg2", "cmd2", java.time.Instant.now()));
        store.addViolation(new SandboxViolationStore.ViolationEvent("msg3", "cmd1", java.time.Instant.now()));
        assertEquals(2, store.getViolationsForCommand("cmd1").size());
        assertEquals(1, store.getViolationsForCommand("cmd2").size());
    }

    @Test
    public void violationStore_maxSize() {
        SandboxViolationStore store = new SandboxViolationStore();
        for (int i = 0; i < 150; i++) {
            store.addViolation(new SandboxViolationStore.ViolationEvent("msg" + i, "cmd" + i, java.time.Instant.now()));
        }
        assertEquals(100, store.size());
    }

    @Test
    public void violationStore_clear() {
        SandboxViolationStore store = new SandboxViolationStore();
        store.addViolation(new SandboxViolationStore.ViolationEvent("msg", "cmd", java.time.Instant.now()));
        store.clear();
        assertEquals(0, store.size());
    }

    // ==================== OsSandboxExecutorFactory ====================

    @Test
    public void factory_createsNonNull() {
        OsSandboxExecutor executor = OsSandboxExecutorFactory.create();
        assertNotNull(executor);
        assertTrue(executor.isAvailable());
    }

    // ==================== MacOsSandboxExecutor Profile ====================

    @Test
    public void macOs_wrapCommand_profileContainsDenyDefault() {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(result.contains("deny default"), "Profile should contain 'deny default'");
        assertTrue(result.contains("sandbox-exec"), "Should contain sandbox-exec");
        assertTrue(result.contains("bash -c"), "Should contain bash -c");
    }

    @Test
    public void macOs_wrapCommand_profileContainsMandatoryDeny() {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(result.contains(".bashrc"), "Profile should deny .bashrc");
        assertTrue(result.contains(".gitconfig"), "Profile should deny .gitconfig");
    }

    @Test
    public void macOs_wrapCommand_profileContainsMoveBlocking() {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(result.contains("file-write-unlink"), "Should contain Move-Blocking");
        assertTrue(result.contains("file-write-create"), "Should contain Move-Blocking");
    }

    @Test
    public void macOs_wrapCommand_profileContainsSysctl() {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(result.contains("hw.activecpu"), "Should contain sysctl hw.activecpu");
        assertTrue(result.contains("kern.osrelease"), "Should contain sysctl kern.osrelease");
    }

    // ==================== LinuxSandboxExecutor ====================

    @Test
    public void linux_wrapCommand_containsBwrap() {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(result.startsWith("bwrap "), "Should start with bwrap");
        assertTrue(result.contains("--die-with-parent"), "Should contain --die-with-parent");
        assertTrue(result.contains("--unshare-pid"), "Should contain --unshare-pid");
    }

    // ==================== UlimitFallbackExecutor ====================

    @Test
    public void ulimit_wrapCommand_prependsUlimit() {
        UlimitFallbackExecutor executor = new UlimitFallbackExecutor();
        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(result.startsWith("ulimit -u 64 -f 102400"), "Should prepend ulimit");
        assertTrue(result.contains("echo hello"), "Should contain original command");
    }

    @Test
    public void ulimit_isAlwaysAvailable() {
        assertTrue(new UlimitFallbackExecutor().isAvailable());
    }

    @Test
    public void ulimit_injectResourceLimits_static() {
        String result = UlimitFallbackExecutor.injectResourceLimits("ls -la");
        assertTrue(result.contains("ulimit -u 64"));
        assertTrue(result.contains("-f 102400"));
        assertTrue(result.endsWith("ls -la"));
    }

    // ==================== Cross-cutting ====================

    @Test
    public void executorFactory_withNullConfig_works() {
        OsSandboxExecutor executor = OsSandboxExecutorFactory.create(null);
        assertNotNull(executor);
        String result = executor.wrapCommand("echo test", Paths.get("/tmp/test"), new HashMap<>());
        assertTrue(result.contains("echo test"));
    }

    @Test
    public void executorFactory_withFullConfig_works() {
        SandboxConfig config = new SandboxConfig();
        config.getFilesystem().setAllowWrite(Arrays.asList(".", "/tmp"));
        config.getFilesystem().setDenyRead(Arrays.asList("/etc/shadow"));
        config.getNetwork().setAllowedDomains(Arrays.asList("github.com"));
        OsSandboxExecutor executor = OsSandboxExecutorFactory.create(config);
        assertNotNull(executor);
        String result = executor.wrapCommand("echo test", Paths.get("/tmp/test"), new HashMap<>());
        assertTrue(result.contains("echo test"));
    }
}
