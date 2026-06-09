package org.noear.solon.ai.sandbox;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.sandbox.config.NetworkConfig;
import org.noear.solon.ai.sandbox.config.ParentProxyConfig;
import org.noear.solon.ai.sandbox.config.SandboxConfigValidator;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;
import org.noear.solon.ai.sandbox.net.DomainPatternMatcher;
import org.noear.solon.ai.sandbox.net.HostUtils;
import org.noear.solon.ai.sandbox.net.NetworkFilter;
import org.noear.solon.ai.sandbox.net.ParentProxyResolver;
import org.noear.solon.ai.sandbox.util.GlobUtils;
import org.noear.solon.ai.sandbox.util.ShellQuote;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SandboxE2eLogicTest {

    // =========================================================================
    // Helper methods
    // =========================================================================

    private static SandboxRuntimeConfig configWithNetwork(NetworkConfig network) {
        return new SandboxRuntimeConfig(network, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static NetworkConfig networkConfig(List<String> allowedDomains, List<String> deniedDomains) {
        return new NetworkConfig(allowedDomains, deniedDomains, null, null, null, null, null, null, null, null, null, null);
    }

    // =========================================================================
    // T1: NetworkFilter — deniedDomains takes priority over allowedDomains
    // =========================================================================

    @Test
    void networkFilter_allowDenyPriority() {
        NetworkConfig net = networkConfig(
                Collections.singletonList("example.com"),
                Collections.singletonList("example.com")
        );
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "example.com", cfg, null));
    }

    // =========================================================================
    // T2: NetworkFilter — empty allowedDomains blocks everything
    // =========================================================================

    @Test
    void networkFilter_emptyAllowList_blocksAll() {
        NetworkConfig net = networkConfig(Collections.emptyList(), null);
        SandboxRuntimeConfig cfg = configWithNetwork(net);
        assertFalse(NetworkFilter.filter(80, "example.com", cfg, null));
    }

    // =========================================================================
    // T3: DomainPatternMatcher — wildcard *.example.com semantics
    // =========================================================================

    @Test
    void networkFilter_wildcardDomain() {
        assertTrue(DomainPatternMatcher.matchesDomainPattern("sub.example.com", "*.example.com"));
        assertFalse(DomainPatternMatcher.matchesDomainPattern("example.com", "*.example.com"));
        assertTrue(DomainPatternMatcher.matchesDomainPattern("deep.sub.example.com", "*.example.com"));
    }

    // =========================================================================
    // T4: DomainPatternMatcher — wildcard does not match IP literals
    // =========================================================================

    @Test
    void networkFilter_ipLiteralNotMatchWildcard() {
        assertFalse(DomainPatternMatcher.matchesDomainPattern("192.168.1.1", "*.example.com"));
    }

    // =========================================================================
    // T5: HostUtils — canonicalize rejects octal IP
    // =========================================================================

    @Test
    void hostUtils_canonicalizeRejectsOctalIP() {
        assertNull(HostUtils.canonicalizeHost("0177.0.0.1"));
    }

    // =========================================================================
    // T6: HostUtils — canonicalize rejects control characters
    // =========================================================================

    @Test
    void hostUtils_rejectsControlChars() {
        assertNull(HostUtils.canonicalizeHost("hello\u0000world"));
    }

    // =========================================================================
    // T7: ParentProxyResolver — NO_PROXY=* bypasses everything
    // =========================================================================

    @Test
    void parentProxy_noProxyWildcard() {
        ParentProxyConfig config = new ParentProxyConfig(null, null, "*");
        ParentProxyResolver resolver = new ParentProxyResolver(config);
        assertTrue(resolver.shouldBypassProxy("example.com", 443));
    }

    // =========================================================================
    // T8: ParentProxyResolver — CIDR bypass
    // =========================================================================

    @Test
    void parentProxy_cidrBypass() {
        ParentProxyConfig config = new ParentProxyConfig("http://proxy:8080", null, "10.0.0.0/8");
        ParentProxyResolver resolver = new ParentProxyResolver(config);
        assertTrue(resolver.shouldBypassProxy("10.1.2.3", 80));
        assertFalse(resolver.shouldBypassProxy("192.168.1.1", 80));
    }

    // =========================================================================
    // T9: ParentProxyResolver — suffix bypass (.example.com)
    // =========================================================================

    @Test
    void parentProxy_suffixBypass() {
        ParentProxyConfig config = new ParentProxyConfig("http://proxy:8080", null, ".example.com");
        ParentProxyResolver resolver = new ParentProxyResolver(config);
        assertTrue(resolver.shouldBypassProxy("sub.example.com", 80));
        assertTrue(resolver.shouldBypassProxy("example.com", 80));
        assertFalse(resolver.shouldBypassProxy("other.com", 80));
    }

    // =========================================================================
    // T10: SandboxConfigValidator — null config throws SandboxException
    // =========================================================================

    @Test
    void configValidator_rejectInvalidConfig() {
        assertThrows(SandboxException.class, () -> SandboxConfigValidator.validate(null));
    }

    // =========================================================================
    // T11: SandboxViolationStore — record and retrieve
    // =========================================================================

    @Test
    void violationStore_recordAndRetrieve() {
        SandboxViolationStore store = new SandboxViolationStore(null);
        store.record("file_read", "read /etc/passwd");
        assertTrue(store.hasViolations());
        assertTrue(store.getViolations("file_read").contains("read /etc/passwd"));
        assertTrue(store.getCategories().contains("file_read"));
    }

    // =========================================================================
    // T12: SandboxViolationStore — ignore patterns suppress matching violations
    // =========================================================================

    @Test
    void violationStore_ignorePatterns() {
        Map<String, List<String>> ignore = new HashMap<>();
        ignore.put("file_read", Collections.singletonList("/tmp/"));
        SandboxViolationStore store = new SandboxViolationStore(ignore);
        store.record("file_read", "read /tmp/test");
        assertFalse(store.hasViolations());
    }

    // =========================================================================
    // T13: ShellQuote — special character handling
    // =========================================================================

    @Test
    void shellQuote_specialChars() {
        assertEquals("'hello world'", ShellQuote.quoteArg("hello world"));
        assertEquals("'$HOME'", ShellQuote.quoteArg("$HOME"));
        assertEquals("'it'\"'\"'s'", ShellQuote.quoteArg("it's"));
        assertEquals("simple", ShellQuote.quoteArg("simple"));
    }

    // =========================================================================
    // T14: GlobUtils — globToRegex produces correct regex patterns
    // =========================================================================

    @Test
    void globUtils_toRegex() {
        Pattern p1 = Pattern.compile(GlobUtils.globToRegex("*.java"));
        assertTrue(p1.matcher("Test.java").matches());

        Pattern p2 = Pattern.compile(GlobUtils.globToRegex("src/**/*.xml"));
        assertTrue(p2.matcher("src/foo/bar.xml").matches());

        Pattern p3 = Pattern.compile(GlobUtils.globToRegex("test?"));
        assertTrue(p3.matcher("test1").matches());
        assertFalse(p3.matcher("test12").matches());
    }
}
