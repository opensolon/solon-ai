package org.noear.solon.ai.sandbox.config;

import java.util.List;

public class FilesystemConfig {
    private final List<String> denyRead;
    private final List<String> allowRead;
    private final List<String> allowWrite;
    private final List<String> denyWrite;
    private final Boolean allowGitConfig;

    public FilesystemConfig(List<String> denyRead, List<String> allowRead,
                            List<String> allowWrite, List<String> denyWrite,
                            Boolean allowGitConfig) {
        this.denyRead = denyRead;
        this.allowRead = allowRead;
        this.allowWrite = allowWrite;
        this.denyWrite = denyWrite;
        this.allowGitConfig = allowGitConfig;
    }

    public List<String> getDenyRead() { return denyRead; }
    public List<String> getAllowRead() { return allowRead; }
    public List<String> getAllowWrite() { return allowWrite; }
    public List<String> getDenyWrite() { return denyWrite; }
    public Boolean getAllowGitConfig() { return allowGitConfig; }
}
