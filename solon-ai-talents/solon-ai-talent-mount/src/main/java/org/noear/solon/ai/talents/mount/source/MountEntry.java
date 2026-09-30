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
package org.noear.solon.ai.talents.mount.source;

import org.noear.solon.lang.Preview;

import java.time.Instant;

/** 
 * 挂载来源中的虚拟文件或目录条目。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class MountEntry {
    private final String path;
    private final String name;
    private final boolean directory;
    private final long size;
    private final Instant lastModified;
    private final String version;

    /**
     * 创建挂载来源中的条目。
     *
     * @param path 条目路径
     * @param name 条目名称
     * @param directory 是否为目录
     * @param size 条目大小
     * @param lastModified 最后修改时间
     * @param version 条目版本
     */
    public MountEntry(String path, String name, boolean directory, long size,
                      Instant lastModified, String version) {
        this.path = path;
        this.name = name;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
        this.version = version;
    }

    /** 获取条目路径。 */
    public String getPath() { return path; }
    /** 获取条目名称。 */
    public String getName() { return name; }
    /** 是否为目录。 */
    public boolean isDirectory() { return directory; }
    /** 获取条目大小。 */
    public long getSize() { return size; }
    /** 获取最后修改时间。 */
    public Instant getLastModified() { return lastModified; }
    /** 获取条目版本。 */
    public String getVersion() { return version; }
}
