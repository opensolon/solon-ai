package org.noear.solon.ai.sandbox;

import java.util.Collections;
import java.util.List;

/**
 * Write restriction config using an "allow-only" pattern.
 *
 * Semantics:
 * - null (no config) = no restrictions (allow all writes)
 * - allowOnly=[], denyWithinAllow=[] = maximally restrictive (deny ALL writes)
 * - allowOnly=[...paths], denyWithinAllow=[...] = allow writes only to these paths,
 *   with exceptions for denyWithinAllow
 *
 * This is maximally restrictive by default.
 * Note: Empty allowOnly means NO paths are writable (unlike read's empty denyOnly).
 */
public class FsWriteRestrictionConfig {
    private final List<String> allowOnly;
    private final List<String> denyWithinAllow;

    public FsWriteRestrictionConfig(List<String> allowOnly, List<String> denyWithinAllow) {
        this.allowOnly = allowOnly != null ? allowOnly : Collections.<String>emptyList();
        this.denyWithinAllow = denyWithinAllow != null ? denyWithinAllow : Collections.<String>emptyList();
    }

    public List<String> getAllowOnly() {
        return allowOnly;
    }

    public List<String> getDenyWithinAllow() {
        return denyWithinAllow;
    }
}
