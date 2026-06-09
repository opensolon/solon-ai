package org.noear.solon.ai.sandbox.windows;

/**
 * Result of a Windows sandbox install operation.
 *
 * <p>Port of {@code WindowsInstallResult} from windows-sandbox-utils.ts.
 */
public class WindowsInstallResult {

    /** Post-install group state. */
    private final WindowsGroupStatusResult group;

    /** Post-install WFP state. */
    private final WindowsWfpStatusResult wfp;

    /**
     * {@code true} if the user dismissed the UAC prompt.
     * Not an error — the install simply didn't happen.
     * Re-run when the user is ready to grant elevation.
     */
    private final boolean cancelled;

    public WindowsInstallResult(WindowsGroupStatusResult group, WindowsWfpStatusResult wfp, boolean cancelled) {
        this.group = group;
        this.wfp = wfp;
        this.cancelled = cancelled;
    }

    /** Post-install group state. */
    public WindowsGroupStatusResult getGroup() {
        return group;
    }

    /** Post-install WFP state. */
    public WindowsWfpStatusResult getWfp() {
        return wfp;
    }

    /**
     * Whether the user cancelled the UAC elevation prompt.
     * If {@code true}, the install did not succeed and should be retried.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public String toString() {
        return "WindowsInstallResult{group=" + group
            + ", wfp=" + wfp
            + ", cancelled=" + cancelled + "}";
    }
}
