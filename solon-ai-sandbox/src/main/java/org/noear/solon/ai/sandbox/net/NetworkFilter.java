package org.noear.solon.ai.sandbox.net;

import org.noear.solon.ai.sandbox.NetworkHostPattern;
import org.noear.solon.ai.sandbox.SandboxAskCallback;
import org.noear.solon.ai.sandbox.SandboxLog;
import org.noear.solon.ai.sandbox.config.NetworkConfig;
import org.noear.solon.ai.sandbox.config.SandboxRuntimeConfig;

import java.util.List;

public final class NetworkFilter {

    /**
     * Filter a network request based on the sandbox configuration.
     * Returns true if allowed, false if denied.
     *
     * Ports filterNetworkRequest from sandbox-manager.ts exactly:
     * 1. No config -> deny
     * 2. Invalid host -> deny
     * 3. Canonicalize host
     * 4. Check deniedDomains first
     * 5. Check allowedDomains
     * 6. No match + no callback -> deny
     * 7. Callback throws -> deny
     */
    public static boolean filter(int port, String host, SandboxRuntimeConfig config,
                                  SandboxAskCallback callback) {
        if (config == null) {
            SandboxLog.debug("No config available, denying network request");
            return false;
        }

        if (!HostUtils.isValidHost(host)) {
            SandboxLog.error("Denying malformed host: " + host + ":" + port);
            return false;
        }

        String canonicalHost = HostUtils.canonicalizeHost(host);
        if (canonicalHost == null) canonicalHost = host;

        NetworkConfig network = config.getNetwork();
        if (network == null) return false;

        // Check denied domains first
        List<String> deniedDomains = network.getDeniedDomains();
        if (deniedDomains != null) {
            for (String deniedDomain : deniedDomains) {
                if (DomainPatternMatcher.matchesDomainPattern(canonicalHost, deniedDomain)) {
                    SandboxLog.debug("Denied by config rule: " + host + ":" + port);
                    return false;
                }
            }
        }

        // Check allowed domains
        List<String> allowedDomains = network.getAllowedDomains();
        if (allowedDomains != null) {
            for (String allowedDomain : allowedDomains) {
                if (DomainPatternMatcher.matchesDomainPattern(canonicalHost, allowedDomain)) {
                    SandboxLog.debug("Allowed by config rule: " + host + ":" + port);
                    return true;
                }
            }
        }

        // No matching rules
        if (callback == null) {
            SandboxLog.debug("No matching config rule, denying: " + host + ":" + port);
            return false;
        }

        SandboxLog.debug("No matching config rule, asking user: " + host + ":" + port);
        try {
            boolean userAllowed = callback.ask(new NetworkHostPattern(host, port));
            if (userAllowed) {
                SandboxLog.debug("User allowed: " + host + ":" + port);
                return true;
            } else {
                SandboxLog.debug("User denied: " + host + ":" + port);
                return false;
            }
        } catch (Exception e) {
            SandboxLog.error("Error in permission callback: " + e.getMessage());
            return false;
        }
    }
}
