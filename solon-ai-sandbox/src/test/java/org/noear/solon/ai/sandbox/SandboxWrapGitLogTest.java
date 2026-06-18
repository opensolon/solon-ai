package org.noear.solon.ai.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.sandbox.platform.Platform;
import org.noear.solon.ai.sandbox.platform.PlatformDetector;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for specific command wrapping scenarios, including the reported issue
 * with "git log --oneline -20" causing errors in wrapWithSandbox.
 */
class SandboxWrapGitLogTest {

    @BeforeEach
    void setUp() {
        SandboxManager.reset();
    }

    @AfterEach
    void tearDown() {
        SandboxManager.reset();
    }

    private static SandboxRuntimeConfig minimalConfig() {
        return new SandboxRuntimeConfig(null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    /**
     * Test that "git log --oneline -20" can be wrapped without errors.
     * This addresses the reported issue where this command caused errors
     * in SandboxManager.wrapWithSandbox.
     */
    @Test
    void wrapWithSandbox_gitLogOneline() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git log --oneline -20");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
        
        // On macOS, should contain sandbox-exec
        if (PlatformDetector.detect() == Platform.MACOS) {
            assertTrue(wrapped.contains("sandbox-exec"),
                    "macOS wrap result should contain 'sandbox-exec', was: " + wrapped);
        }
        
        // The wrapped command should still contain the original command
        assertTrue(wrapped.contains("git log --oneline -20"),
                "Wrapped command should contain the original command, was: " + wrapped);
    }

    /**
     * Test that "git log --oneline -10" can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitLogOneline10() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git log --oneline -10");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }

    /**
     * Test that "git log --oneline" (without -20) can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitLogOnelineNoLimit() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git log --oneline");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }

    /**
     * Test that "git log" can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitLog() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git log");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }

    /**
     * Test that "git status" can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitStatus() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git status");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }

    /**
     * Test that "git diff" can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitDiff() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git diff");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }

    /**
     * Test that "git branch" can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitBranch() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git branch");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }

    /**
     * Test that "git add ." can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitAdd() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git add .");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }

    /**
     * Test that "git commit -m 'test'" can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitCommit() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git commit -m 'test'");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }

    /**
     * Test that "git push" can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitPush() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git push");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }

    /**
     * Test that "git pull" can be wrapped without errors.
     */
    @Test
    void wrapWithSandbox_gitPull() throws Exception {
        // Skip if not on supported platform
        if (!SandboxManager.isSupportedPlatform()) {
            return;
        }

        SandboxRuntimeConfig cfg = minimalConfig();
        SandboxManager.initialize(cfg, null);

        // This should not throw an exception
        String wrapped = SandboxManager.wrapWithSandbox("git pull");

        assertNotNull(wrapped, "Wrapped command should not be null");
        assertFalse(wrapped.isEmpty(), "Wrapped command should not be empty");
    }
}