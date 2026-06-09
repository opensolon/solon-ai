package org.noear.solon.ai.sandbox.net;

public final class DomainPatternMatcher {

    /**
     * Check if a hostname matches a domain pattern.
     * Ports matchesDomainPattern from sandbox-manager.ts exactly.
     *
     * - *.example.com matches sub.example.com but NOT example.com
     * - Wildcards never match IP literals
     * - Non-wildcard patterns are exact match (case-insensitive)
     */
    public static boolean matchesDomainPattern(String hostname, String pattern) {
        String h = hostname.toLowerCase();

        if (pattern.startsWith("*.")) {
            // Never apply wildcard suffix matching to IP literals
            if (HostUtils.isIPAddress(HostUtils.stripBrackets(h))) return false;
            String baseDomain = pattern.substring(2).toLowerCase();
            return h.endsWith("." + baseDomain);
        }

        return h.equals(pattern.toLowerCase());
    }
}
