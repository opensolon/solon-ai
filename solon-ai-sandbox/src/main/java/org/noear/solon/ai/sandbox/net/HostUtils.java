package org.noear.solon.ai.sandbox.net;

import java.net.URI;
import java.util.regex.Pattern;

public final class HostUtils {

    private static final Pattern DNS_LABEL_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern IPV6_CHARS_PATTERN = Pattern.compile("^[0-9a-fA-F:]+$");
    private static final Pattern IPV4_PARTS_PATTERN = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$");

    public static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * Check if a string is a valid IP address (IPv4 or IPv6) WITHOUT DNS resolution.
     */
    public static boolean isIPAddress(String host) {
        if (host == null) return false;
        String bare = stripBrackets(host);
        return isValidIPv4(bare) || isValidIPv6Literal(bare);
    }

    private static boolean isValidIPv4(String s) {
        if (!IPV4_PARTS_PATTERN.matcher(s).matches()) return false;
        String[] parts = s.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            // Reject octal (leading zero with more than one digit) and hex
            if (part.length() > 1 && part.startsWith("0")) return false;
            try {
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidIPv6Literal(String s) {
        if (!IPV6_CHARS_PATTERN.matcher(s).matches()) return false;
        // Must have at least 2 colons to be distinguishable from a hostname
        long colonCount = s.chars().filter(ch -> ch == ':').count();
        return colonCount >= 2;
    }

    /**
     * Validate a hostname. Rejects control chars, %, etc.
     * Ports isValidHost from parent-proxy.ts.
     */
    public static boolean isValidHost(String h) {
        if (h == null || h.isEmpty() || h.length() > 255) return false;
        String bare = stripBrackets(h);
        if (bare.contains("%")) return false;
        if (isIPAddress(bare)) return true;
        return DNS_LABEL_PATTERN.matcher(bare).matches();
    }

    /**
     * Canonicalize a host string. Handles inet_aton shorthand, IPv6 compression.
     * Rejects control characters and validates strictly without DNS resolution for IPs.
     * Ports canonicalizeHost from parent-proxy.ts.
     */
    public static String canonicalizeHost(String h) {
        if (h == null) return null;
        try {
            String bare = stripBrackets(h);

            // Reject control characters (0x00-0x1f, 0x7f) and '%'
            for (int i = 0; i < bare.length(); i++) {
                char c = bare.charAt(i);
                if (c <= 0x1f || c == 0x7f || c == '%') {
                    return null;
                }
            }

            // IPv4: manually parse and validate dotted-quad
            if (isValidIPv4(bare)) {
                return bare; // already canonical 4-octet form
            }

            // Reject non-standard IPv4 forms (shorthand, octal, hex, partial)
            if (bare.matches("^[0-9.]+$") && bare.contains(".")) {
                return null; // had dots but wasn't valid 4-octet
            }

            // IPv6: validate characters
            if (bare.contains(":")) {
                if (!IPV6_CHARS_PATTERN.matcher(bare).matches()) return null;
                long colonCount = bare.chars().filter(ch -> ch == ':').count();
                if (colonCount < 2) return null;
                return bare; // return as-is (compressed form is canonical)
            }

            // Hostname: use URI parsing for canonical form
            try {
                URI uri = new URI("http://" + bare + "/");
                String host = uri.getHost();
                if (host != null) {
                    String result = stripBrackets(host);
                    if (result.endsWith(".")) {
                        result = result.substring(0, result.length() - 1);
                    }
                    return result;
                }
            } catch (Exception e) {
                // Fall through
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
