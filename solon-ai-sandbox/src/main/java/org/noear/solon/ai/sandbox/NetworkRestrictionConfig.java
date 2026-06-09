package org.noear.solon.ai.sandbox;

import java.util.Collections;
import java.util.List;

/**
 * Network restriction config (internal structure built from permission rules).
 *
 * Uses an "allow-only" pattern:
 * - allowedHosts = hosts that are explicitly allowed
 * - deniedHosts = hosts that are explicitly denied (checked first, before allowedHosts)
 *
 * Semantics:
 * - null (no config) = maximally restrictive (deny all network)
 * - allowedHosts=[], deniedHosts=[] = maximally restrictive (nothing allowed)
 * - allowedHosts=[...], deniedHosts=[...] = apply allow/deny rules
 *
 * Note: Empty allowedHosts means NO hosts are allowed.
 */
public class NetworkRestrictionConfig {
    private final List<String> allowedHosts;
    private final List<String> deniedHosts;

    public NetworkRestrictionConfig(List<String> allowedHosts, List<String> deniedHosts) {
        this.allowedHosts = allowedHosts;
        this.deniedHosts = deniedHosts;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts != null ? allowedHosts : Collections.<String>emptyList();
    }

    public List<String> getDeniedHosts() {
        return deniedHosts != null ? deniedHosts : Collections.<String>emptyList();
    }
}
