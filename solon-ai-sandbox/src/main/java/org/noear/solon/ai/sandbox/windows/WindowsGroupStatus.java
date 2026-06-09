package org.noear.solon.ai.sandbox.windows;

/**
 * State of the Windows discriminator group in SAM and in the current process's token.
 *
 * <p>Port of {@code WindowsGroupStatus} from windows-sandbox-utils.ts.
 *
 * <ul>
 *   <li>{@link #ABSENT} — group does not exist in SAM.</li>
 *   <li>{@link #CREATED_NOT_ON_TOKEN} — group exists in SAM but is not enabled in the
 *       caller's token (logout/login needed).</li>
 *   <li>{@link #READY} — group exists AND is enabled in the caller's token; sandbox is usable.</li>
 * </ul>
 */
public enum WindowsGroupStatus {
    ABSENT("absent"),
    CREATED_NOT_ON_TOKEN("created-not-on-token"),
    READY("ready");

    private final String jsonValue;

    WindowsGroupStatus(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /** The value produced by {@code srt-win group status} JSON output. */
    public String getJsonValue() {
        return jsonValue;
    }

    /**
     * Parse the JSON string value into the enum constant.
     *
     * @param value the JSON string (e.g. "absent", "created-not-on-token", "ready")
     * @return the corresponding enum constant
     * @throws IllegalArgumentException if the value does not match any constant
     */
    public static WindowsGroupStatus fromJsonValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("WindowsGroupStatus value is null");
        }
        for (WindowsGroupStatus status : values()) {
            if (status.jsonValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown WindowsGroupStatus: " + value);
    }
}
