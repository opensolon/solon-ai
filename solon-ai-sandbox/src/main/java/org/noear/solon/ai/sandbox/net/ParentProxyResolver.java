package org.noear.solon.ai.sandbox.net;

import org.noear.solon.ai.sandbox.SandboxLog;
import org.noear.solon.ai.sandbox.config.ParentProxyConfig;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves and manages parent/upstream HTTP proxy configuration.
 * <p>
 * Resolves proxy settings from explicit config or environment variables
 * (HTTP_PROXY, HTTPS_PROXY, NO_PROXY, and their lowercase equivalents),
 * and provides NO_PROXY matching with CIDR and hostname suffix support
 * following golang's httpproxy semantics.
 * <p>
 * Ports the logic from parent-proxy.ts faithfully.
 */
public final class ParentProxyResolver {

    // --- Regex patterns used during parsing ---
    private static final Pattern SCHEME_PATTERN = Pattern.compile("^[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
    private static final Pattern BRACKETED_WITH_PORT = Pattern.compile("^\\[([^\\]]+)\\](?::\\d+)?$");

    // --- Static loopback CIDR entries (always checked) ---
    private static final List<CidrEntry> LOOPBACK_CIDRS;

    static {
        List<CidrEntry> list = new ArrayList<CidrEntry>();
        // 127.0.0.0/8 — whole IPv4 loopback block
        CidrEntry v4Loopback = createCidrEntry("127.0.0.0", 8);
        if (v4Loopback != null) list.add(v4Loopback);
        // ::1/128 — IPv6 loopback exact
        CidrEntry v6Loopback = createCidrEntry("::1", 128);
        if (v6Loopback != null) list.add(v6Loopback);
        // ::ffff:127.0.0.0/104 — IPv4-mapped loopback range
        CidrEntry v4Mapped = createCidrEntry("::ffff:127.0.0.0", 104);
        if (v4Mapped != null) list.add(v4Mapped);
        LOOPBACK_CIDRS = Collections.unmodifiableList(list);
    }

    // --- Resolved state ---
    private final String httpProxyUrl;
    private final String httpsProxyUrl;
    private final NoProxyRules noProxyRules;

    // =========================================================================
    // Constructor — resolveParentProxy()
    // =========================================================================

    public ParentProxyResolver(ParentProxyConfig config) {
        // Resolve HTTP proxy: config.http → HTTP_PROXY env → http_proxy env
        String httpRaw = null;
        if (config != null && config.getHttp() != null && !config.getHttp().isEmpty()) {
            httpRaw = config.getHttp();
        }
        if (httpRaw == null) {
            httpRaw = System.getenv("HTTP_PROXY");
        }
        if (httpRaw == null) {
            httpRaw = System.getenv("http_proxy");
        }

        // Resolve HTTPS proxy: config.https → HTTPS_PROXY env → https_proxy env → fallback to http
        String httpsRaw = null;
        if (config != null && config.getHttps() != null && !config.getHttps().isEmpty()) {
            httpsRaw = config.getHttps();
        }
        if (httpsRaw == null) {
            httpsRaw = System.getenv("HTTPS_PROXY");
        }
        if (httpsRaw == null) {
            httpsRaw = System.getenv("https_proxy");
        }
        if (httpsRaw == null) {
            // Fall back to the HTTP proxy value — matches curl behaviour
            httpsRaw = httpRaw;
        }

        // Resolve NO_PROXY: config.noProxy → NO_PROXY env → no_proxy env → ""
        String noProxyRaw = null;
        if (config != null && config.getNoProxy() != null && !config.getNoProxy().isEmpty()) {
            noProxyRaw = config.getNoProxy();
        }
        if (noProxyRaw == null) {
            noProxyRaw = System.getenv("NO_PROXY");
        }
        if (noProxyRaw == null) {
            noProxyRaw = System.getenv("no_proxy");
        }
        if (noProxyRaw == null) {
            noProxyRaw = "";
        }

        // Parse proxy URLs
        this.httpProxyUrl = parseProxyUrl(httpRaw);
        this.httpsProxyUrl = parseProxyUrl(httpsRaw);

        // Parse NO_PROXY rules
        this.noProxyRules = parseNoProxy(noProxyRaw);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the resolved HTTP proxy URL string, or null if not configured.
     */
    public String getHttpProxyUrl() {
        return httpProxyUrl;
    }

    /**
     * Returns the resolved HTTPS proxy URL string, or null if not configured.
     */
    public String getHttpsProxyUrl() {
        return httpsProxyUrl;
    }

    /**
     * Select the appropriate parent proxy URL based on whether the target
     * connection is HTTPS.
     * <p>
     * For HTTPS targets: returns httpsUrl if set, falls back to httpUrl.
     * For plain HTTP targets: returns httpUrl only (does NOT fall back to
     * httpsUrl), matching curl semantics where HTTP requests go direct if
     * only HTTPS_PROXY is configured.
     */
    public String selectParentProxyUrl(boolean isHttps) {
        if (isHttps) {
            return httpsProxyUrl != null ? httpsProxyUrl : httpProxyUrl;
        }
        // For plain HTTP, only use the HTTP proxy URL — no fallback to HTTPS
        return httpProxyUrl;
    }

    /**
     * Returns true if the given host should bypass the parent proxy and
     * connect directly. Always bypasses localhost and loopback addresses
     * (127.0.0.0/8, ::1, ::ffff:127.0.0.0/104).
     * <p>
     * The port parameter is accepted for API compatibility but is NOT used
     * in the bypass decision — port-specific NO_PROXY entries have their
     * port suffix stripped during parsing.
     */
    public boolean shouldBypassProxy(String host, int port) {
        if (noProxyRules == null) return false;

        // Normalize: lowercase, strip trailing dot, strip brackets
        String h = HostUtils.stripBrackets(host.toLowerCase());
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }

        // Always bypass localhost (hostname check)
        if ("localhost".equals(h)) return true;

        // Always bypass loopback addresses (full 127/8, ::1, v4-mapped loopback)
        boolean isIp = HostUtils.isIPAddress(h);
        if (isIp && matchesAnyCidr(h, LOOPBACK_CIDRS)) return true;

        // Check the "bypass all" flag (NO_PROXY=*)
        if (noProxyRules.all) return true;

        // Check user-configured CIDR entries
        if (isIp && !noProxyRules.cidrs.isEmpty()) {
            if (matchesAnyCidr(h, noProxyRules.cidrs)) return true;
        }

        // Check suffix entries (golang semantics)
        for (String v : noProxyRules.suffixes) {
            if (v.startsWith(".")) {
                // .example.com matches foo.example.com and example.com
                if (h.equals(v.substring(1)) || h.endsWith(v)) return true;
            } else {
                // example.com matches example.com and foo.example.com
                if (h.equals(v) || h.endsWith("." + v)) return true;
            }
        }

        return false;
    }

    // =========================================================================
    // URL parsing
    // =========================================================================

    /**
     * Parse and validate a proxy URL string. Accepts schemeless host:port
     * (like curl does), rejects non-http/https schemes.
     *
     * @return the normalized URL string, or null if invalid
     */
    private static String parseProxyUrl(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        // Accept schemeless host:port like curl
        boolean hasScheme = SCHEME_PATTERN.matcher(raw).find();
        String withScheme = hasScheme ? raw : "http://" + raw;

        try {
            URI uri = new URI(withScheme);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            String schemeLower = scheme.toLowerCase();
            if (!"http".equals(schemeLower) && !"https".equals(schemeLower)) {
                SandboxLog.debug("Invalid parent proxy URL, ignoring (unsupported scheme): " + redactUserinfo(raw));
                return null;
            }
            String hostName = uri.getHost();
            if (hostName == null || hostName.isEmpty()) {
                SandboxLog.debug("Invalid parent proxy URL, ignoring (empty host): " + redactUserinfo(raw));
                return null;
            }
            return uri.toString();
        } catch (Exception e) {
            SandboxLog.debug("Invalid parent proxy URL, ignoring: " + redactUserinfo(raw));
            return null;
        }
    }

    // =========================================================================
    // NO_PROXY parsing — parseNoProxy()
    // =========================================================================

    private static NoProxyRules parseNoProxy(String raw) {
        NoProxyRules rules = new NoProxyRules();

        if (raw == null || raw.isEmpty()) return rules;

        String[] entries = raw.split(",");
        for (String entry : entries) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;

            // Wildcard — bypass everything
            if ("*".equals(entry)) {
                rules.all = true;
                continue;
            }

            // CIDR entry (contains '/')
            int slash = entry.indexOf('/');
            if (slash != -1) {
                String ip = entry.substring(0, slash);
                String prefixStr = entry.substring(slash + 1);
                // Validate: IP must be valid, prefix must be numeric and non-empty
                if (HostUtils.isIPAddress(ip) && !prefixStr.isEmpty() && DIGITS_ONLY.matcher(prefixStr).matches()) {
                    try {
                        int prefix = Integer.parseInt(prefixStr);
                        // Determine max prefix based on IP family
                        byte[] ipBytes = InetAddress.getByName(HostUtils.stripBrackets(ip)).getAddress();
                        int max = ipBytes.length == 16 ? 128 : 32;
                        if (prefix >= 0 && prefix <= max) {
                            CidrEntry cidr = createCidrEntry(HostUtils.stripBrackets(ip), prefix);
                            if (cidr != null) {
                                rules.cidrs.add(cidr);
                            }
                            continue;
                        }
                    } catch (Exception e) {
                        // Malformed CIDR — ignore, do NOT treat as suffix
                    }
                }
                // Malformed CIDR → ignore entirely (/ is not a valid hostname char)
                continue;
            }

            // Hostname suffix entry — normalize
            String v = entry.toLowerCase();

            // Strip bracketed form [v6]:port → extract inner host
            java.util.regex.Matcher bracketed = BRACKETED_WITH_PORT.matcher(v);
            if (bracketed.matches()) {
                v = bracketed.group(1);
            }

            // Strip leading "*. " → "."
            if (v.startsWith("*.")) {
                v = v.substring(1); // becomes ".example.com"
            }

            // Check if it's a bare IP address
            if (HostUtils.isIPAddress(v)) {
                // Bare IP literal — store as /32 or /128 CIDR for exact match
                try {
                    byte[] addrBytes = InetAddress.getByName(HostUtils.stripBrackets(v)).getAddress();
                    int prefixLen = addrBytes.length == 16 ? 128 : 32;
                    CidrEntry cidr = createCidrEntry(HostUtils.stripBrackets(v), prefixLen);
                    if (cidr != null) {
                        rules.cidrs.add(cidr);
                        continue;
                    }
                } catch (Exception e) {
                    // Fall through to suffix push
                }
            }

            // Not an IP — strip trailing :port (but only if the part after colon is all digits)
            if (!HostUtils.isIPAddress(v)) {
                int colon = v.lastIndexOf(':');
                if (colon != -1) {
                    String afterColon = v.substring(colon + 1);
                    if (DIGITS_ONLY.matcher(afterColon).matches()) {
                        v = v.substring(0, colon);
                    }
                }
            }

            rules.suffixes.add(v);
        }

        return rules;
    }

    // =========================================================================
    // CIDR matching helpers
    // =========================================================================

    /**
     * Check if the given host address matches any CIDR entry in the list.
     */
    private static boolean matchesAnyCidr(String host, List<CidrEntry> cidrs) {
        try {
            byte[] hostBytes = InetAddress.getByName(host).getAddress();
            for (CidrEntry cidr : cidrs) {
                if (cidr.matches(hostBytes)) return true;
            }
        } catch (Exception e) {
            // Cannot resolve host as IP — no CIDR match possible
        }
        return false;
    }

    /**
     * Create a CidrEntry from an IP string and prefix length.
     * Returns null if the IP cannot be resolved.
     */
    private static CidrEntry createCidrEntry(String ip, int prefixLength) {
        try {
            byte[] networkBytes = InetAddress.getByName(ip).getAddress();
            return new CidrEntry(networkBytes, prefixLength);
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Redaction helper
    // =========================================================================

    /**
     * Best-effort redaction of userinfo from a raw URL string for safe logging.
     * Replaces credentials in the form //user:pass@ with //***:***@
     */
    private static String redactUserinfo(String raw) {
        if (raw == null) return "null";
        return raw.replaceAll("//[^@/]*@", "//***:***@");
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    /**
     * Parsed NO_PROXY rules containing:
     * - all: true if '*' was specified (bypass everything)
     * - cidrs: list of CIDR entries for IP range matching
     * - suffixes: list of hostname suffix patterns for domain matching
     */
    static final class NoProxyRules {
        boolean all;
        final List<CidrEntry> cidrs;
        final List<String> suffixes;

        NoProxyRules() {
            this.all = false;
            this.cidrs = new ArrayList<CidrEntry>();
            this.suffixes = new ArrayList<String>();
        }
    }

    /**
     * A CIDR network entry (network address bytes + prefix length).
     * Performs byte-level matching for IPv4 and IPv6 addresses.
     */
    static final class CidrEntry {
        private final byte[] networkBytes;
        private final int prefixLength;

        CidrEntry(byte[] networkBytes, int prefixLength) {
            // Defensive copy
            this.networkBytes = new byte[networkBytes.length];
            System.arraycopy(networkBytes, 0, this.networkBytes, 0, networkBytes.length);
            this.prefixLength = prefixLength;
        }

        /**
         * Check if the given host address bytes fall within this CIDR range.
         */
        boolean matches(byte[] hostBytes) {
            if (hostBytes.length != networkBytes.length) return false;

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            // Compare full bytes
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != hostBytes[i]) return false;
            }

            // Compare remaining bits within the next byte
            if (remainingBits > 0 && fullBytes < networkBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((networkBytes[fullBytes] & mask) != (hostBytes[fullBytes] & mask)) return false;
            }

            return true;
        }
    }
}
