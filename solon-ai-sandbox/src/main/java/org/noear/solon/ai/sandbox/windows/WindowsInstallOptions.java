package org.noear.solon.ai.sandbox.windows;

/**
 * Options for {@link org.noear.solon.ai.sandbox.platform.WindowsSandboxBackend#installWindowsSandbox}.
 *
 * <p>Port of {@code WindowsInstallOptions} from windows-sandbox-utils.ts.
 * Extends the group reference ({@code groupName}/{@code groupSid}) with install-specific fields.
 */
public class WindowsInstallOptions {

    /** Local or domain group name. Default: {@code sandbox-runtime-net}. */
    private String groupName;

    /** Group SID in {@code S-1-…} form. Takes precedence over {@code groupName}. */
    private String groupSid;

    /** Add this user (instead of the current user) to the group. */
    private String userSid;

    /** WFP sublayer GUID. Omit for srt-win's compile-time default. */
    private String sublayerGuid;

    /**
     * Loopback PERMIT port range {@code [low, high]}.
     * Must match what the proxy is configured to use.
     * Default: {@code [60080, 60089]}.
     */
    private int[] proxyPortRange;

    /**
     * Replace an existing install whose configuration differs (different group SID
     * or port range under the same sublayer). Without this, install refuses with
     * "already installed with different config" rather than silently overwriting.
     */
    private boolean force;

    public WindowsInstallOptions() {
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupSid() {
        return groupSid;
    }

    public void setGroupSid(String groupSid) {
        this.groupSid = groupSid;
    }

    public String getUserSid() {
        return userSid;
    }

    public void setUserSid(String userSid) {
        this.userSid = userSid;
    }

    public String getSublayerGuid() {
        return sublayerGuid;
    }

    public void setSublayerGuid(String sublayerGuid) {
        this.sublayerGuid = sublayerGuid;
    }

    public int[] getProxyPortRange() {
        return proxyPortRange;
    }

    public void setProxyPortRange(int[] proxyPortRange) {
        this.proxyPortRange = proxyPortRange;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }
}
