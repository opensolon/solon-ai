package org.noear.solon.ai.talents.mount.catalog;

import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.MountSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 已发现的 Agent 来源描述，记录其名称、所属挂载和来源路径，并提供读取来源内容的能力。
 * <p>此对象不表示已解析的 AgentDefinition，也不表示运行时 Agent 实例。</p>
 */
public class AgentDescriptor {
    private final String name;
    private final String mountAlias;
    private final MountSource source;
    private final String sourcePath;

    public AgentDescriptor(String name, String mountAlias, MountSource source, String sourcePath) {
        this.name = name;
        this.mountAlias = mountAlias;
        this.source = source;
        this.sourcePath = source == null ? "" : source.normalize(sourcePath);
    }

    /**
     * 基于本地文件创建 Agent 来源描述。
     *
     * @param name Agent 名称
     * @param mountAlias 来源挂载别名
     * @param filePath Markdown 文件路径
     */
    public AgentDescriptor(String name, String mountAlias, Path filePath) {
        this(name, mountAlias, FileMountSource.of(filePath.getParent()), filePath.getFileName().toString());
    }

    public String getName() { return name; }
    public String getMountAlias() { return mountAlias; }
    public MountSource getSource() { return source; }
    public String getSourcePath() { return sourcePath; }

    public InputStream open() throws IOException {
        return source.openRead(sourcePath);
    }

    /**
     * 返回来源对应的本地文件路径。
     *
     * @return 本地来源路径；来源没有对应的本地文件时返回 {@code null}
     */
    public Path getFilePath() {
        Optional<Path> path = source.getLocalPath(sourcePath);
        return path.orElse(null);
    }
}
