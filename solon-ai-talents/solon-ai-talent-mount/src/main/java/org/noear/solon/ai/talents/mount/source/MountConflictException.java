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

import java.io.IOException;

/**
 * 乐观锁冲突：写入时的 {@link WriteOptions#getExpectedVersion()} 与当前版本不一致（内容已被他人修改）。
 *
 * <p>继承 {@link IOException}，按 IO 失败处理的既有调用方无需改动；需要区分冲突的调用方可单独捕获。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public class MountConflictException extends IOException {
    private final String path;
    private final String expectedVersion;
    private final String actualVersion;

    public MountConflictException(String path, String expectedVersion, String actualVersion) {
        super("Version conflict on '" + path + "': expected " + expectedVersion + ", actual " + actualVersion);
        this.path = path;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    /** 来源内路径。 */
    public String getPath() {
        return path;
    }

    /** 调用方期望的版本。 */
    public String getExpectedVersion() {
        return expectedVersion;
    }

    /** 当前实际版本（条目不存在时为 null）。 */
    public String getActualVersion() {
        return actualVersion;
    }
}
