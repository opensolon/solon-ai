package org.noear.solon.ai.sandbox.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SandboxPathUtilsTest {

    // --- DANGEROUS_FILES ---

    @Test
    void dangerousFiles_containsGitconfig() {
        assertTrue(SandboxPathUtils.DANGEROUS_FILES.contains(".gitconfig"));
    }

    @Test
    void dangerousFiles_containsBashrc() {
        assertTrue(SandboxPathUtils.DANGEROUS_FILES.contains(".bashrc"));
    }

    @Test
    void dangerousFiles_containsMcpJson() {
        assertTrue(SandboxPathUtils.DANGEROUS_FILES.contains(".mcp.json"));
    }

    @Test
    void dangerousFiles_doesNotContainRandomFile() {
        assertFalse(SandboxPathUtils.DANGEROUS_FILES.contains("random.txt"));
    }

    @Test
    void dangerousFiles_isUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () ->
                SandboxPathUtils.DANGEROUS_FILES.add("should-fail"));
    }

    // --- DANGEROUS_DIRECTORIES ---

    @Test
    void dangerousDirectories_containsGit() {
        assertTrue(SandboxPathUtils.DANGEROUS_DIRECTORIES.contains(".git"));
    }

    @Test
    void dangerousDirectories_containsVscode() {
        assertTrue(SandboxPathUtils.DANGEROUS_DIRECTORIES.contains(".vscode"));
    }

    @Test
    void dangerousDirectories_containsIdea() {
        assertTrue(SandboxPathUtils.DANGEROUS_DIRECTORIES.contains(".idea"));
    }

    @Test
    void dangerousDirectories_isUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () ->
                SandboxPathUtils.DANGEROUS_DIRECTORIES.add("should-fail"));
    }

    // --- getDangerousDirectories ---

    @Test
    void getDangerousDirectories_excludesGit() {
        List<String> dirs = SandboxPathUtils.getDangerousDirectories();
        assertFalse(dirs.contains(".git"));
    }

    @Test
    void getDangerousDirectories_includesVscode() {
        List<String> dirs = SandboxPathUtils.getDangerousDirectories();
        assertTrue(dirs.contains(".vscode"));
    }

    @Test
    void getDangerousDirectories_includesIdea() {
        List<String> dirs = SandboxPathUtils.getDangerousDirectories();
        assertTrue(dirs.contains(".idea"));
    }

    @Test
    void getDangerousDirectories_includesClaudeCommands() {
        List<String> dirs = SandboxPathUtils.getDangerousDirectories();
        assertTrue(dirs.contains(".claude/commands"));
    }

    @Test
    void getDangerousDirectories_includesClaudeAgents() {
        List<String> dirs = SandboxPathUtils.getDangerousDirectories();
        assertTrue(dirs.contains(".claude/agents"));
    }

    // --- getDefaultWritePaths ---

    @Test
    void getDefaultWritePaths_containsDevNull() {
        List<String> paths = SandboxPathUtils.getDefaultWritePaths();
        assertTrue(paths.contains("/dev/null"));
    }

    @Test
    void getDefaultWritePaths_containsDevStdout() {
        List<String> paths = SandboxPathUtils.getDefaultWritePaths();
        assertTrue(paths.contains("/dev/stdout"));
    }

    @Test
    void getDefaultWritePaths_containsTmpClaude() {
        List<String> paths = SandboxPathUtils.getDefaultWritePaths();
        assertTrue(paths.contains("/tmp/claude"));
    }

    @Test
    void getDefaultWritePaths_containsPrivateTmpClaude() {
        List<String> paths = SandboxPathUtils.getDefaultWritePaths();
        assertTrue(paths.contains("/private/tmp/claude"));
    }

    // --- normalizeCaseForComparison ---

    @Test
    void normalizeCaseForComparison_lowercase() {
        assertEquals("hello", SandboxPathUtils.normalizeCaseForComparison("hello"));
    }

    @Test
    void normalizeCaseForComparison_uppercaseToLower() {
        assertEquals("hello", SandboxPathUtils.normalizeCaseForComparison("HELLO"));
    }

    @Test
    void normalizeCaseForComparison_mixedCase() {
        assertEquals("/users/test/file.txt", SandboxPathUtils.normalizeCaseForComparison("/Users/Test/File.txt"));
    }

    // --- isSymlinkOutsideBoundary ---

    @Test
    void isSymlinkOutsideBoundary_samePath_returnsFalse() {
        assertFalse(SandboxPathUtils.isSymlinkOutsideBoundary("/home/user/project", "/home/user/project"));
    }

    @Test
    void isSymlinkOutsideBoundary_subPath_returnsFalse() {
        // resolved is inside original boundary
        assertFalse(SandboxPathUtils.isSymlinkOutsideBoundary("/home/user", "/home/user/project"));
    }

    @Test
    void isSymlinkOutsideBoundary_macTmpToPrivateTmp_returnsFalse() {
        assertFalse(SandboxPathUtils.isSymlinkOutsideBoundary("/tmp/claude", "/private/tmp/claude"));
    }

    @Test
    void isSymlinkOutsideBoundary_outsidePath_returnsTrue() {
        assertTrue(SandboxPathUtils.isSymlinkOutsideBoundary("/home/user/project", "/etc/passwd"));
    }

    @Test
    void isSymlinkOutsideBoundary_resolvedToRoot_returnsTrue() {
        assertTrue(SandboxPathUtils.isSymlinkOutsideBoundary("/home/user/project", "/"));
    }

    // --- normalizePathForSandbox ---

    @Test
    void normalizePathForSandbox_tildeExpandsToHome() {
        String home = System.getProperty("user.home");
        assertEquals(home, SandboxPathUtils.normalizePathForSandbox("~"));
    }

    @Test
    void normalizePathForSandbox_tildeSlashExpands() {
        String home = System.getProperty("user.home");
        assertEquals(home + "/Documents", SandboxPathUtils.normalizePathForSandbox("~/Documents"));
    }

    @Test
    void normalizePathForSandbox_relativePathResolvesAgainstCwd() {
        String cwd = System.getProperty("user.dir");
        String result = SandboxPathUtils.normalizePathForSandbox("subdir");
        assertEquals(new java.io.File(cwd, "subdir").getAbsolutePath(), result);
    }

    @Test
    void normalizePathForSandbox_absolutePathUnchanged() {
        // For paths that don't exist, it stays as-is
        String result = SandboxPathUtils.normalizePathForSandbox("/nonexistent/absolute/path");
        assertEquals("/nonexistent/absolute/path", result);
    }
}
