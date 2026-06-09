package org.noear.solon.ai.sandbox.net;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DomainPatternMatcherTest {

    // === Exact match tests ===
    @Test
    public void testExactMatch() {
        assertTrue(DomainPatternMatcher.matchesDomainPattern("example.com", "example.com"));
    }

    @Test
    public void testExactMatchCaseInsensitive() {
        assertTrue(DomainPatternMatcher.matchesDomainPattern("Example.COM", "example.com"));
        assertTrue(DomainPatternMatcher.matchesDomainPattern("example.com", "Example.COM"));
    }

    @Test
    public void testExactMismatch() {
        assertFalse(DomainPatternMatcher.matchesDomainPattern("other.com", "example.com"));
    }

    // === Wildcard match tests ===
    @Test
    public void testWildcardMatchesSubdomain() {
        assertTrue(DomainPatternMatcher.matchesDomainPattern("sub.example.com", "*.example.com"));
    }

    @Test
    public void testWildcardMatchesDeepSubdomain() {
        assertTrue(DomainPatternMatcher.matchesDomainPattern("a.b.c.example.com", "*.example.com"));
    }

    @Test
    public void testWildcardDoesNotMatchBareDomain() {
        assertFalse(DomainPatternMatcher.matchesDomainPattern("example.com", "*.example.com"));
    }

    @Test
    public void testWildcardDoesNotMatchOtherDomain() {
        assertFalse(DomainPatternMatcher.matchesDomainPattern("evil.com", "*.example.com"));
    }

    // === IP address wildcard blocking ===
    @Test
    public void testWildcardNeverMatchesIPv4() {
        assertFalse(DomainPatternMatcher.matchesDomainPattern("10.0.0.1", "*.0.0.1"));
    }

    @Test
    public void testWildcardNeverMatchesIPv6() {
        assertFalse(DomainPatternMatcher.matchesDomainPattern("::1", "*.1"));
    }

    // === Edge cases ===
    @Test
    public void testLocalhost() {
        assertTrue(DomainPatternMatcher.matchesDomainPattern("localhost", "localhost"));
    }

    @Test
    public void testWildcardLocalhostDoesNotMatchBare() {
        assertFalse(DomainPatternMatcher.matchesDomainPattern("localhost", "*.localhost"));
    }
}
