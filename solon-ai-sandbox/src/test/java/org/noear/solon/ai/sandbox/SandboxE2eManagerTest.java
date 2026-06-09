package org.noear.solon.ai.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.sandbox.config.NetworkConfig;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.sandbox.platform.SandboxDependencyCheck;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SandboxE2eManagerTest {

    @BeforeEach
    void setUp() {
        SandboxManager.reset();
    }

    @AfterEach
    void tearDown() {
        SandboxManager.reset();
    }

    // --- Helpers ---

    private static SandboxRuntimeConfig minimalConfig() {
        return new SandboxRuntimeConfig(null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    // --- T25: initialize and checkDependencies ---

    @Test
    void managerInitialize_checkDependencies() throws Exception {
        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        assertTrue(SandboxManager.isSandboxingEnabled(),
                "isSandboxingEnabled should be true after initialize");

        SandboxDependencyCheck deps = SandboxManager.checkDependencies();
        assertFalse(deps.hasErrors(),
                "macOS should have no required external dependencies");
    }

    // --- T26: wrapCommand produces sandbox-exec on macOS ---

    @Test
    void managerWrapCommand_macOS() throws Exception {
        NetworkConfig network = new NetworkConfig(
                Arrays.asList("example.com"),
                null, null, null, null, null,
                null, null, null, null, null, null);
        SandboxRuntimeConfig cfg = new SandboxRuntimeConfig(network, null, null, null,
                null, null, null, null, null, null, null, null, null);

        SandboxManager.initialize(cfg, null);
        SandboxManager.setProxyPorts(18080, 18081);

        String wrapped = SandboxManager.wrapWithSandbox("echo hello");
        assertTrue(wrapped.contains("sandbox-exec"),
                "macOS wrap result should contain 'sandbox-exec'");
    }

    // --- T27: updateConfig swaps the live configuration ---

    @Test
    void managerUpdateConfig() throws Exception {
        NetworkConfig network1 = new NetworkConfig(
                Arrays.asList("example.com"),
                null, null, null, null, null,
                null, null, null, null, null, null);
        SandboxRuntimeConfig cfg1 = new SandboxRuntimeConfig(network1, null, null, null,
                null, null, null, null, null, null, null, null, null);
        SandboxManager.initialize(cfg1, null);

        NetworkConfig network2 = new NetworkConfig(
                Arrays.asList("other.com"),
                null, null, null, null, null,
                null, null, null, null, null, null);
        SandboxRuntimeConfig cfg2 = new SandboxRuntimeConfig(network2, null, null, null,
                null, null, null, null, null, null, null, null, null);
        SandboxManager.updateConfig(cfg2);

        assertTrue(SandboxManager.getConfig().getNetwork().getAllowedDomains()
                        .contains("other.com"),
                "updateConfig should replace the configuration");
    }

    // --- T28: reset clears all state ---

    @Test
    void managerReset() throws Exception {
        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        assertTrue(SandboxManager.isSandboxingEnabled(),
                "should be enabled after initialize");

        SandboxManager.reset();

        assertFalse(SandboxManager.isSandboxingEnabled(),
                "should be disabled after reset");
        assertNull(SandboxManager.getConfig(),
                "config should be null after reset");
    }

    // --- T29: cleanupAfterCommand is a safe no-op on macOS ---

    @Test
    void managerCleanupAfterCommand() {
        assertDoesNotThrow(() -> SandboxManager.cleanupAfterCommand(),
                "cleanupAfterCommand should not throw on macOS");
    }
}
