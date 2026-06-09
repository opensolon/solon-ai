package org.noear.solon.ai.sandbox.windows;

/**
 * Result of querying the WFP filter status.
 *
 * <p>Port of {@code WindowsWfpStatusResult} from windows-sandbox-utils.ts.
 */
public class WindowsWfpStatusResult {

    private final WindowsWfpStatus state;
    private final int filters;
    private final int[] portRange; // nullable [low, high]

    public WindowsWfpStatusResult(WindowsWfpStatus state, int filters, int[] portRange) {
        this.state = state;
        this.filters = filters;
        this.portRange = portRange;
    }

    /** The WFP filter state (absent or installed). */
    public WindowsWfpStatus getState() {
        return state;
    }

    /** Number of srt-win-tagged filters found under the sublayer. */
    public int getFilters() {
        return filters;
    }

    /**
     * Loopback PERMIT port range {@code [low, high]} from the filter's tag,
     * or {@code null} if not present.
     */
    public int[] getPortRange() {
        return portRange;
    }

    @Override
    public String toString() {
        String pr = portRange != null ? portRange[0] + "-" + portRange[1] : "null";
        return "WindowsWfpStatusResult{state=" + state
            + ", filters=" + filters
            + ", portRange=" + pr + "}";
    }
}
