/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.mount;

import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.lang.Preview;

import java.nio.file.Path;
import java.util.Optional;

/** 
 * 用户逻辑路径解析后的来源资源。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class ResolvedResource {
    private final String logicalPath;
    private final String mountAlias;
    private final Mount mount;
    private final MountSource source;
    private final String sourcePath;

    /**
     * 创建逻辑路径解析结果。
     *
     * @param logicalPath 原始逻辑路径
     * @param mountAlias 挂载别名
     * @param mount 对应挂载；工作区路径为 null
     * @param source 内容来源
     * @param sourcePath 来源内路径
     */
    public ResolvedResource(String logicalPath, String mountAlias, Mount mount,
                            MountSource source, String sourcePath) {
        this.logicalPath = logicalPath;
        this.mountAlias = mountAlias;
        this.mount = mount;
        this.source = source;
        this.sourcePath = sourcePath;
    }

    /** 获取原始逻辑路径。 */
    public String getLogicalPath() { return logicalPath; }
    /** 获取挂载别名。 */
    public String getMountAlias() { return mountAlias; }
    /** 获取对应挂载；工作区路径返回 null。 */
    public Mount getMount() { return mount; }
    /** 获取内容来源。 */
    public MountSource getSource() { return source; }
    /** 获取来源内路径。 */
    public String getSourcePath() { return sourcePath; }
    /** 获取本地路径；来源不支持本地路径时返回空值。 */
    public Optional<Path> getLocalPath() { return source.getLocalPath(sourcePath); }
}
