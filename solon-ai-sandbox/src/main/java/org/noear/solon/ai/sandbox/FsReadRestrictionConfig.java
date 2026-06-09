package org.noear.solon.ai.sandbox;

import java.util.Collections;
import java.util.List;

/**
 * Read restriction config using a "deny then allow-back" pattern.
 *
 * Semantics:
 * - null (no config) = no restrictions (allow all reads)
 * - denyOnly=[] = no restrictions (empty deny list = allow all reads)
 * - denyOnly=[...paths] = deny reads from these paths, allow all others
 * - denyOnly=[...paths], allowWithinDeny=[...paths] = deny reads from denyOnly paths,
 *   but re-allow reads within allowWithinDeny paths. allowWithinDeny takes precedence.
 *
 * This is maximally permissive by default.
 */
public class FsReadRestrictionConfig {
    private final List<String> denyOnly;
    private final List<String> allowWithinDeny;

    public FsReadRestrictionConfig(List<String> denyOnly, List<String> allowWithinDeny) {
        this.denyOnly = denyOnly != null ? denyOnly : Collections.<String>emptyList();
        this.allowWithinDeny = allowWithinDeny != null ? allowWithinDeny : Collections.<String>emptyList();
    }

    public List<String> getDenyOnly() {
        return denyOnly;
    }

    public List<String> getAllowWithinDeny() {
        return allowWithinDeny;
    }
}
