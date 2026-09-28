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

import java.time.Instant;

/** 挂载来源中的虚拟文件或目录条目。 */
public final class MountEntry {
    private final String path;
    private final String name;
    private final boolean directory;
    private final long size;
    private final Instant lastModified;
    private final String version;

    public MountEntry(String path, String name, boolean directory, long size,
                      Instant lastModified, String version) {
        this.path = path;
        this.name = name;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
        this.version = version;
    }

    public String getPath() { return path; }
    public String getName() { return name; }
    public boolean isDirectory() { return directory; }
    public long getSize() { return size; }
    public Instant getLastModified() { return lastModified; }
    public String getVersion() { return version; }
}
