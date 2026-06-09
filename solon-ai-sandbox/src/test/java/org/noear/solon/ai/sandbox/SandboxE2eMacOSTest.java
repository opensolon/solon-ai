package org.noear.solon.ai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.sandbox.platform.MacOSSandboxBackend;
import org.noear.solon.ai.sandbox.platform.MacOSSandboxBackend.MacOSSandboxParams;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * macOS sandbox-exec integration tests (T14-T24).
 *
 * <p>T14-T16 test profile generation via {@code wrapCommandWithSandbox} output assertions.
 * T17-T21 execute actual sandbox-exec commands and verify behaviour.
 * T22-T24 test edge cases and mandatory deny patterns.</p>
 *
 * <p>All sandbox-exec tests are guarded by an availability check and skipped
 * via {@link org.junit.jupiter.api.Assumptions#assumeTrue} when /usr/bin/sandbox-exec
 * is not present (e.g. on Linux CI).</p>
 */
class SandboxE2eMacOSTest {

    @TempDir
    Path tempDir;

    // --------------------------------------------------------------- helpers

    private static boolean hasSandboxExec() {
        return File.separator.equals("/") && new File("/usr/bin/sandbox-exec").exists();
    }

    private static int executeCommand(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] output = new byte[4096];
        //noinspection ResultOfMethodCallIgnored
        p.getInputStream().read(output);
        p.waitFor(10, TimeUnit.SECONDS);
        return p.exitValue();
    }

    private static String executeCommandAndGetOutput(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] output = new byte[4096];
        int len = p.getInputStream().read(output);
        p.waitFor(10, TimeUnit.SECONDS);
        return len > 0 ? new String(output, 0, len, StandardCharsets.UTF_8) : "";
    }

    private static MacOSSandboxParams baseParams() {
        MacOSSandboxParams params = new MacOSSandboxParams();
        params.command = "echo hello";
        params.needsNetworkRestriction = false;
        params.readConfig = null;
        params.writeConfig = null;
        return params;
    }

    // --------------------------------------------------------------- T14

    @Test
    void profileGeneration_readRestriction() {
        assumeTrue(hasSandboxExec(), "sandbox-exec not available");

        MacOSSandboxParams params = baseParams();
        params.readConfig = new FsReadRestrictionConfig(
                Collections.singletonList("/Users"),
                Collections.singletonList("/Users/test/workspace"));

        String result = MacOSSandboxBackend.wrapCommandWithSandbox(params);

        assertTrue(result.contains("(deny file-read*"),
                "profile should contain a file-read deny rule, was: " + result);
        assertTrue(result.contains("(allow file-read*"),
                "profile should contain a file-read allow rule, was: " + result);
        // denyOnly path appears as subpath
        assertTrue(result.contains("(subpath \"/Users\")") || result.contains("/Users"),
                "profile should reference /Users in deny rule, was: " + result);
        // allowWithinDeny path
        assertTrue(result.contains("/Users/test/workspace"),
                "profile should reference /Users/test/workspace in allow rule, was: " + result);
    }

    // --------------------------------------------------------------- T15

    @Test
    void profileGeneration_writeRestriction() {
        assumeTrue(hasSandboxExec(), "sandbox-exec not available");

        MacOSSandboxParams params = baseParams();
        params.writeConfig = new FsWriteRestrictionConfig(
                Collections.singletonList("/tmp/sandbox-e2e-test"),
                Collections.emptyList());

        String result = MacOSSandboxBackend.wrapCommandWithSandbox(params);

        assertTrue(result.contains("(allow file-write*"),
                "profile should contain a file-write allow rule, was: " + result);
        assertTrue(result.contains("(deny file-write*"),
                "profile should contain a file-write deny rule (mandatory deny patterns), was: " + result);
    }

    // --------------------------------------------------------------- T16

    @Test
    void profileGeneration_networkRestriction() {
        assumeTrue(hasSandboxExec(), "sandbox-exec not available");

        MacOSSandboxParams params = baseParams();
        params.needsNetworkRestriction = true;
        params.httpProxyPort = 18080;

        String result = MacOSSandboxBackend.wrapCommandWithSandbox(params);

        assertTrue(result.contains("localhost:18080"),
                "profile should reference localhost:18080 for proxy, was: " + result);
        // When network is restricted, the broad "(allow network*)" should NOT appear
        // (instead, specific network-outbound rules for the proxy are emitted)
        assertFalse(result.contains("(allow network*)"),
                "profile should NOT contain broad (allow network*) when restricted, was: " + result);
    }

    // --------------------------------------------------------------- T17

    @Test
    void sandboxExec_writeBlocked() throws Exception {
        assumeTrue(hasSandboxExec(), "sandbox-exec not available");

        Path safeDir = tempDir.resolve("safe");
        Files.createDirectories(safeDir);

        MacOSSandboxParams params = baseParams();
        params.command = "touch " + tempDir + "/outside.txt";
        params.writeConfig = new FsWriteRestrictionConfig(
                Collections.singletonList(safeDir.toString()),
                Collections.emptyList());

        String wrapped = MacOSSandboxBackend.wrapCommandWithSandbox(params);

        int exitCode = executeCommand(wrapped);
        assertNotEquals(0, exitCode,
                "writing outside the allowed directory should be blocked by sandbox");
    }

    // --------------------------------------------------------------- T18

    @Test
    void sandboxExec_writeAllowed() throws Exception {
        assumeTrue(hasSandboxExec(), "sandbox-exec not available");

        Path safeDir = tempDir.resolve("safe");
        Files.createDirectories(safeDir);
        Path targetFile = safeDir.resolve("test.txt");

        MacOSSandboxParams params = baseParams();
        params.command = "touch " + targetFile;
        params.writeConfig = new FsWriteRestrictionConfig(
                Collections.singletonList(safeDir.toString()),
                Collections.emptyList());

        String wrapped = MacOSSandboxBackend.wrapCommandWithSandbox(params);

        int exitCode = executeCommand(wrapped);
        assertEquals(0, exitCode,
                "writing inside the allowed directory should succeed");
        assertTrue(Files.exists(targetFile),
                "target file should exist after sandboxed write");
    }

    // --------------------------------------------------------------- T19

    @Test
    void sandboxExec_readBlocked() throws Exception {
        assumeTrue(hasSandboxExec(), "sandbox-exec not available");

        Path secretFile = tempDir.resolve("secret.txt");
        Files.write(secretFile, "secret data".getBytes(StandardCharsets.UTF_8));

        MacOSSandboxParams params = baseParams();
        params.command = "cat " + secretFile;
        params.readConfig = new FsReadRestrictionConfig(
                Collections.singletonList(secretFile.toString()),
                Collections.emptyList());

        String wrapped = MacOSSandboxBackend.wrapCommandWithSandbox(params);

        String output = executeCommandAndGetOutput(wrapped);
        assertFalse(output.contains("secret data"),
                "reading the denied file should not return its contents");
    }

    // --------------------------------------------------------------- T20

    @Test
    void sandboxExec_readAllowed() throws Exception {
        assumeTrue(hasSandboxExec(), "sandbox-exec not available");

        Path allowedDir = tempDir.resolve("allowed");
        Files.createDirectories(allowedDir);
        Path dataFile = allowedDir.resolve("data.txt");
        Files.write(dataFile, "hello from allowed".getBytes(StandardCharsets.UTF_8));

        MacOSSandboxParams params = baseParams();
        params.command = "cat " + dataFile;
        // Deny reads from tempDir, but re-allow reads from allowedDir
        params.readConfig = new FsReadRestrictionConfig(
                Collections.singletonList(tempDir.toString()),
                Collections.singletonList(allowedDir.toString()));

        String wrapped = MacOSSandboxBackend.wrapCommandWithSandbox(params);

        String output = executeCommandAndGetOutput(wrapped);
        assertTrue(output.contains("hello from allowed"),
                "reading an allowed file within a denied region should succeed");
    }

    // --------------------------------------------------------------- T21

    @Test
    void sandboxExec_networkBlocked() throws Exception {
        assumeTrue(hasSandboxExec(), "sandbox-exec not available");

        MacOSSandboxParams params = baseParams();
        params.command = "curl -s --connect-timeout 2 http://127.0.0.1:1";
        params.needsNetworkRestriction = true;
        // No proxy ports set — all network should be denied by (deny default)

        String wrapped = MacOSSandboxBackend.wrapCommandWithSandbox(params);

        int exitCode = executeCommand(wrapped);
        assertNotEquals(0, exitCode,
                "network connection should be blocked by sandbox when no proxy is configured");
    }

    // --------------------------------------------------------------- T22

    @Test
    void wrapCommand_noRestriction_returnsOriginal() {
        // No sandbox-exec requirement — this is a pure logic test
        MacOSSandboxParams params = baseParams();
        // All restriction fields are null/false by default from baseParams()

        String result = MacOSSandboxBackend.wrapCommandWithSandbox(params);

        assertEquals("echo hello", result,
                "wrapCommandWithSandbox should return the original command when no restrictions apply");
    }

    // --------------------------------------------------------------- T23

    @Test
    void mandatoryDenyPatterns_containsGitHooks() {
        List<String> patterns = MacOSSandboxBackend.macGetMandatoryDenyPatterns(false);

        boolean hasGitHooks = patterns.stream()
                .anyMatch(p -> p.contains(".git/hooks"));
        assertTrue(hasGitHooks,
                "mandatory deny patterns should contain .git/hooks path");
    }

    // --------------------------------------------------------------- T24

    @Test
    void mandatoryDenyPatterns_allowGitConfig() {
        // When allowGitConfig=true, .git/config should NOT be blocked
        List<String> allowedPatterns = MacOSSandboxBackend.macGetMandatoryDenyPatterns(true);

        boolean hasGitConfigWhenAllowed = allowedPatterns.stream()
                .anyMatch(p -> p.contains(".git/config"));
        assertFalse(hasGitConfigWhenAllowed,
                "when allowGitConfig=true, .git/config should NOT be in deny patterns");

        // .git/hooks should always be present regardless
        boolean hasGitHooks = allowedPatterns.stream()
                .anyMatch(p -> p.contains(".git/hooks"));
        assertTrue(hasGitHooks,
                ".git/hooks should always be in deny patterns even when allowGitConfig=true");

        // When allowGitConfig=false, .git/config SHOULD be blocked
        List<String> deniedPatterns = MacOSSandboxBackend.macGetMandatoryDenyPatterns(false);

        boolean hasGitConfigWhenDenied = deniedPatterns.stream()
                .anyMatch(p -> p.contains(".git/config"));
        assertTrue(hasGitConfigWhenDenied,
                "when allowGitConfig=false, .git/config should be in deny patterns");
    }
}
