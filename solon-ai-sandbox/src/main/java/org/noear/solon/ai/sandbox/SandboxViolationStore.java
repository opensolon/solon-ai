package org.noear.solon.ai.sandbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe store for sandbox violations that occur during a session.
 * Ports the violation tracking logic from sandbox-manager.ts.
 *
 * Violations are grouped by category (e.g. 'file_read', 'file_write', 'network').
 * The ignoreViolations config can suppress specific violations.
 */
public final class SandboxViolationStore {

    private final Map<String, List<String>> violations = new ConcurrentHashMap<String, List<String>>();
    private final Map<String, List<String>> ignoreViolations;

    public SandboxViolationStore(Map<String, List<String>> ignoreViolations) {
        this.ignoreViolations = ignoreViolations != null ? ignoreViolations : Collections.<String, List<String>>emptyMap();
    }

    /**
     * Record a sandbox violation.
     *
     * @param category the violation category (e.g. 'file_read', 'file_write', 'network')
     * @param message  the violation description
     */
    public void record(String category, String message) {
        if (shouldIgnore(category, message)) {
            SandboxLog.debug("[Sandbox] Ignoring violation: " + category + ": " + message);
            return;
        }
        List<String> list = violations.get(category);
        if (list == null) {
            list = new CopyOnWriteArrayList<String>();
            List<String> existing = violations.putIfAbsent(category, list);
            if (existing != null) {
                list = existing;
            }
        }
        list.add(message);
        SandboxLog.debug("[Sandbox] Violation recorded: " + category + ": " + message);
    }

    /**
     * Get all violations for a category.
     */
    public List<String> getViolations(String category) {
        List<String> list = violations.get(category);
        return list != null ? Collections.unmodifiableList(list) : Collections.<String>emptyList();
    }

    /**
     * Get all violation categories.
     */
    public java.util.Set<String> getCategories() {
        return Collections.unmodifiableSet(violations.keySet());
    }

    /**
     * Check if there are any violations.
     */
    public boolean hasViolations() {
        return !violations.isEmpty();
    }

    /**
     * Clear all violations.
     */
    public void clear() {
        violations.clear();
    }

    private boolean shouldIgnore(String category, String message) {
        List<String> ignorePatterns = ignoreViolations.get(category);
        if (ignorePatterns == null) return false;
        for (String pattern : ignorePatterns) {
            if (message.contains(pattern)) return true;
        }
        return false;
    }
}
