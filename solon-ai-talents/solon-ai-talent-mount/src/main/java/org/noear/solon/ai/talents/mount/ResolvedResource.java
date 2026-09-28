/*
 * Copyright 2017-2025 noear.org and authors
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

import java.nio.file.Path;
import java.util.Optional;

/** 用户逻辑路径解析后的来源资源。 */
public final class ResolvedResource {
    private final String logicalPath;
    private final String mountAlias;
    private final Mount mount;
    private final MountSource source;
    private final String sourcePath;

    public ResolvedResource(String logicalPath, String mountAlias, Mount mount,
                            MountSource source, String sourcePath) {
        this.logicalPath = logicalPath;
        this.mountAlias = mountAlias;
        this.mount = mount;
        this.source = source;
        this.sourcePath = sourcePath;
    }

    public String getLogicalPath() { return logicalPath; }
    public String getMountAlias() { return mountAlias; }
    public Mount getMount() { return mount; }
    public MountSource getSource() { return source; }
    public String getSourcePath() { return sourcePath; }
    public Optional<Path> getLocalPath() { return source.getLocalPath(sourcePath); }
}
