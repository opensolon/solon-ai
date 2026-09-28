package org.noear.solon.ai.talents.mount;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/** 代理 Markdown 描述及其来源位置。 */
public class AgentMd {
    private final String name;
    private final String mountAlias;
    private final MountSource source;
    private final String sourcePath;

    public AgentMd(String name, String mountAlias, MountSource source, String sourcePath) {
        this.name = name;
        this.mountAlias = mountAlias;
        this.source = source;
        this.sourcePath = source == null ? "" : source.normalize(sourcePath);
    }

    /** 旧 File 挂载构造。 */
    public AgentMd(String name, String mountAlias, Path filePath) {
        this(name, mountAlias, FileMountSource.of(filePath.getParent()), filePath.getFileName().toString());
    }

    public String getName() { return name; }
    public String getMountAlias() { return mountAlias; }
    public MountSource getSource() { return source; }
    public String getSourcePath() { return sourcePath; }

    public InputStream open() throws IOException {
        return source.openRead(sourcePath);
    }

    /** 兼容旧 API；非本地来源返回空。 */
    public Path getFilePath() {
        Optional<Path> path = source.getLocalPath(sourcePath);
        return path.orElse(null);
    }
}
