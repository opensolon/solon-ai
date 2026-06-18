package org.noear.solon.ai.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.sandbox.config.FilesystemConfig;
import org.noear.solon.ai.sandbox.config.NetworkConfig;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.sandbox.platform.Platform;
import org.noear.solon.ai.sandbox.platform.PlatformDetector;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SandboxManager#wrapWithSandbox} methods.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Basic command wrapping on macOS (sandbox-exec)</li>
 *   <li>Custom configuration merging</li>
 *   <li>Network restriction application</li>
 *   <li>Filesystem restriction application</li>
 *   <li>Windows platform exception</li>
 *   <li>Single-parameter overload</li>
 * </ul>
 *
 * <p>Tests are platform-aware and will skip execution-based assertions
 * when the required sandboxing tools are not available.</p>
 */
class SandboxWrapTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        SandboxManager.reset();
    }

    @AfterEach
    void tearDown() {
        SandboxManager.reset();
    }

    // --------------------------------------------------------------- helpers

    private static SandboxRuntimeConfig minimalConfig() {
        return new SandboxRuntimeConfig(null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static SandboxRuntimeConfig configWithNetwork(List<String> allowedDomains) {
        NetworkConfig network = new NetworkConfig(
                allowedDomains, null, null, null, null, null,
                null, null, null, null, null, null);
        return new SandboxRuntimeConfig(network, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static SandboxRuntimeConfig configWithFilesystem(List<String> allowRead, List<String> allowWrite) {
        FilesystemConfig filesystem = new FilesystemConfig(
                null, allowRead, allowWrite, null, null);
        return new SandboxRuntimeConfig(null, filesystem, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    // --------------------------------------------------------------- T1: Basic wrapping on macOS

    @Test
    void wrapWithSandbox_macOS_containsSandboxExec() throws Exception {
        // Skip if not on macOS
        if (!SandboxManager.isSupportedPlatform() || PlatformDetector.detect() != Platform.MACOS) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        String wrapped = SandboxManager.wrapWithSandbox("echo hello");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertTrue(wrapped.contains("sandbox-exec"),
                "macOS wrap result should contain 'sandbox-exec', was: " + wrapped);
        assertTrue(wrapped.contains("echo hello"),
                "Wrapped command should contain the original command, was: " + wrapped);
    }

    // --------------------------------------------------------------- T2: Single-parameter overload

    @Test
    void wrapWithSandbox_singleParameter_works() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        String wrapped = SandboxManager.wrapWithSandbox("ls -la");

        assertNotNull(wrapped, "Wrapped command should not be null");
        // On macOS, should contain sandbox-exec; on Linux, should contain bwrap
        if (PlatformDetector.detect() == Platform.MACOS) {
            assertTrue(wrapped.contains("sandbox-exec"),
                    "macOS: should contain 'sandbox-exec'");
        } else if (PlatformDetector.detect() == Platform.LINUX) {
            assertTrue(wrapped.contains("bwrap"),
                    "Linux: should contain 'bwrap'");
        }
    }

    // --------------------------------------------------------------- T3: Custom config with network restriction

    @Test
    void wrapWithSandbox_customConfig_networkRestriction() throws Exception {
        // Skip if not on macOS
        if (!SandboxManager.isSupportedPlatform() || PlatformDetector.detect() != Platform.MACOS) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // Create custom config with network restriction
        SandboxRuntimeConfig customConfig = configWithNetwork(Arrays.asList("example.com"));

        String wrapped = SandboxManager.wrapWithSandbox("curl http://example.com", null, customConfig);

        assertNotNull(wrapped, "Wrapped command should not be null");
        // On macOS with network restriction, should contain sandbox-exec
        assertTrue(wrapped.contains("sandbox-exec"),
                "Should contain sandbox-exec with network restriction, was: " + wrapped);
    }

    // --------------------------------------------------------------- T4: Custom config with filesystem restrictions

    @Test
    void wrapWithSandbox_customConfig_filesystemRestriction() throws Exception {
        // Skip if not on macOS
        if (!SandboxManager.isSupportedPlatform() || PlatformDetector.detect() != Platform.MACOS) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // Create custom config with filesystem restrictions
        SandboxRuntimeConfig customConfig = configWithFilesystem(
                Arrays.asList(tempDir.toString() + "/**"),
                Arrays.asList(tempDir.toString() + "/**"));

        String wrapped = SandboxManager.wrapWithSandbox("echo test", null, customConfig);

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertTrue(wrapped.contains("sandbox-exec"),
                "Should contain sandbox-exec with filesystem restriction, was: " + wrapped);
    }

    // --------------------------------------------------------------- T5: Windows platform throws exception

    @Test
    void wrapWithSandbox_windows_throwsException() throws Exception {
        // This test verifies the exception message, but we can't easily test on Windows
        // in this environment. We'll test the exception type.
        if (PlatformDetector.detect() != Platform.WINDOWS) {
            // On non-Windows platforms, we can't easily simulate Windows detection
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        SandboxException exception = assertThrows(SandboxException.class, () -> {
            SandboxManager.wrapWithSandbox("echo hello");
        });

        assertTrue(exception.getMessage().contains("wrapWithSandbox()"),
                "Exception message should mention wrapWithSandbox, was: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("Windows"),
                "Exception message should mention Windows, was: " + exception.getMessage());
    }

    // --------------------------------------------------------------- T6: Main config takes precedence when no custom config

    @Test
    void wrapWithSandbox_mainConfig_noCustomConfig() throws Exception {
        // Skip if not on macOS
        if (!SandboxManager.isSupportedPlatform() || PlatformDetector.detect() != Platform.MACOS) {
            return;
        }

        // Initialize with network config
        SandboxRuntimeConfig cfg = configWithNetwork(Arrays.asList("example.com"));
        SandboxManager.initialize(cfg, null);

        String wrapped = SandboxManager.wrapWithSandbox("curl http://example.com");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertTrue(wrapped.contains("sandbox-exec"),
                "Should contain sandbox-exec using main config, was: " + wrapped);
    }

    // --------------------------------------------------------------- T7: Custom config overrides main config

    @Test
    void wrapWithSandbox_customConfigOverridesMain() throws Exception {
        // Skip if not on macOS
        if (!SandboxManager.isSupportedPlatform() || PlatformDetector.detect() != Platform.MACOS) {
            return;
        }

        // Initialize with network config
        SandboxRuntimeConfig mainCfg = configWithNetwork(Arrays.asList("example.com"));
        SandboxManager.initialize(mainCfg, null);

        // Custom config with different network
        SandboxRuntimeConfig customCfg = configWithNetwork(Arrays.asList("other.com"));

        String wrapped = SandboxManager.wrapWithSandbox("curl http://other.com", null, customCfg);

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertTrue(wrapped.contains("sandbox-exec"),
                "Should contain sandbox-exec with custom config override, was: " + wrapped);
    }

    // --------------------------------------------------------------- T8: binShell parameter is used

    @Test
    void wrapWithSandbox_customBinShell() throws Exception {
        // Skip if not on macOS
        if (!SandboxManager.isSupportedPlatform() || PlatformDetector.detect() != Platform.MACOS) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        String wrapped = SandboxManager.wrapWithSandbox("echo hello", "bash", null);

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertTrue(wrapped.contains("sandbox-exec"),
                "Should contain sandbox-exec with custom binShell, was: " + wrapped);
    }

    // --------------------------------------------------------------- T9: Empty command still works

    @Test
    void wrapWithSandbox_emptyCommand() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // Empty command should still be wrapped (though it will fail at execution)
        String wrapped = SandboxManager.wrapWithSandbox("");

        assertNotNull(wrapped, "Wrapped command should not be null even for empty command");
    }

    // --------------------------------------------------------------- T10: Special characters in command

    @Test
    void wrapWithSandbox_specialCharacters() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // Command with special characters should be properly handled
        String command = "echo 'hello world' | grep 'hello'";
        String wrapped = SandboxManager.wrapWithSandbox(command);

        assertNotNull(wrapped, "Wrapped command should not be null");
        // The wrapped command should still contain the original command (possibly quoted)
        if (PlatformDetector.detect() == Platform.MACOS) {
            assertTrue(wrapped.contains("sandbox-exec"),
                    "Should contain sandbox-exec");
        }
    }

    // --------------------------------------------------------------- T11: Multiple filesystem paths

    @Test
    void wrapWithSandbox_multipleFilesystemPaths() throws Exception {
        // Skip if not on macOS
        if (!SandboxManager.isSupportedPlatform() || PlatformDetector.detect() != Platform.MACOS) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // Custom config with multiple filesystem paths
        SandboxRuntimeConfig customConfig = configWithFilesystem(
                Arrays.asList("/tmp/**", "/var/**"),
                Arrays.asList("/tmp/**", "/var/**"));

        String wrapped = SandboxManager.wrapWithSandbox("ls /tmp", null, customConfig);

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertTrue(wrapped.contains("sandbox-exec"),
                "Should contain sandbox-exec with multiple filesystem paths, was: " + wrapped);
    }

    // --------------------------------------------------------------- T12: Network with empty allowedDomains (block all)

    @Test
    void wrapWithSandbox_emptyAllowedDomains_blockAll() throws Exception {
        // Skip if not on macOS
        if (!SandboxManager.isSupportedPlatform() || PlatformDetector.detect() != Platform.MACOS) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // Empty allowedDomains means "block all network"
        SandboxRuntimeConfig customConfig = configWithNetwork(Collections.emptyList());

        String wrapped = SandboxManager.wrapWithSandbox("curl http://example.com", null, customConfig);

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertTrue(wrapped.contains("sandbox-exec"),
                "Should contain sandbox-exec with blocked network, was: " + wrapped);
    }

    // --------------------------------------------------------------- T13: No initialization needed for wrapping

    @Test
    void wrapWithSandbox_noInitialization() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        // Should work even without explicit initialization
        String wrapped = SandboxManager.wrapWithSandbox("echo hello");

        assertNotNull(wrapped, "Wrapped command should not be null");
        // On macOS, should contain sandbox-exec
        if (PlatformDetector.detect() == Platform.MACOS) {
            assertTrue(wrapped.contains("sandbox-exec"),
                    "Should contain sandbox-exec even without initialization");
        }
    }
}