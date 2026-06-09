package org.noear.solon.ai.sandbox.config;

public class WindowsConfig {
    public static final String DEFAULT_GROUP_NAME = "sandbox-runtime-net";
    public static final int[] DEFAULT_PROXY_PORT_RANGE = {60080, 60089};

    private final String groupName;
    private final String groupSid;
    private final String wfpSublayerGuid;
    private final int[] proxyPortRange; // [lo, hi]

    public WindowsConfig(String groupName, String groupSid, String wfpSublayerGuid, int[] proxyPortRange) {
        this.groupName = groupName;
        this.groupSid = groupSid;
        this.wfpSublayerGuid = wfpSublayerGuid;
        this.proxyPortRange = proxyPortRange;
    }

    public String getGroupName() { return groupName; }
    public String getGroupSid() { return groupSid; }
    public String getWfpSublayerGuid() { return wfpSublayerGuid; }
    public int[] getProxyPortRange() { return proxyPortRange; }
}
