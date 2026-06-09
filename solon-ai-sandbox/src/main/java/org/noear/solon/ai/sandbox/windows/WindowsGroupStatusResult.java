package org.noear.solon.ai.sandbox.windows;

/**
 * Result of querying the Windows discriminator group status.
 *
 * <p>Port of {@code WindowsGroupStatusResult} from windows-sandbox-utils.ts.
 */
public class WindowsGroupStatusResult {

    private final WindowsGroupStatus state;
    private final String sid;
    private final String warning;
    private final String error;

    public WindowsGroupStatusResult(WindowsGroupStatus state, String sid, String warning, String error) {
        this.state = state;
        this.sid = sid;
        this.warning = warning;
        this.error = error;
    }

    /** The group state (absent, created-not-on-token, or ready). */
    public WindowsGroupStatus getState() {
        return state;
    }

    /** Group SID in {@code S-1-…} form, if available. */
    public String getSid() {
        return sid;
    }

    /** Optional warning message from srt-win. */
    public String getWarning() {
        return warning;
    }

    /** Optional error message from srt-win. */
    public String getError() {
        return error;
    }

    @Override
    public String toString() {
        return "WindowsGroupStatusResult{state=" + state
            + ", sid=" + sid
            + ", warning=" + warning
            + ", error=" + error + "}";
    }
}
