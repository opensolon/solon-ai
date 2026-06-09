package org.noear.solon.ai.sandbox.windows;

/**
 * State of the Windows Filtering Platform (WFP) filter set under a given sublayer.
 *
 * <p>Port of {@code WindowsWfpStatus} from windows-sandbox-utils.ts.
 *
 * <ul>
 *   <li>{@link #ABSENT} — no srt-win-tagged filters found under the sublayer.</li>
 *   <li>{@link #INSTALLED} — both {@code permit-group} and {@code block} filters are present.</li>
 * </ul>
 */
public enum WindowsWfpStatus {
    ABSENT("absent"),
    INSTALLED("installed");

    private final String jsonValue;

    WindowsWfpStatus(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /** The value produced by {@code srt-win wfp status} JSON output. */
    public String getJsonValue() {
        return jsonValue;
    }

    /**
     * Parse the JSON string value into the enum constant.
     *
     * @param value the JSON string (e.g. "absent", "installed")
     * @return the corresponding enum constant
     * @throws IllegalArgumentException if the value does not match any constant
     */
    public static WindowsWfpStatus fromJsonValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("WindowsWfpStatus value is null");
        }
        for (WindowsWfpStatus status : values()) {
            if (status.jsonValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown WindowsWfpStatus: " + value);
    }
}
