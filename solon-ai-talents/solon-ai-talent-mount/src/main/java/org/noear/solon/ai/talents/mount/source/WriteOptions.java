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

/** 
 * 文件写入选项。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class WriteOptions {
    private final boolean append;
    private final boolean createParents;
    private final String expectedVersion;

    /**
     * 根据构建器创建文件写入选项。
     *
     * @param builder 写入选项构建器
     */
    private WriteOptions(Builder builder) {
        this.append = builder.append;
        this.createParents = builder.createParents;
        this.expectedVersion = builder.expectedVersion;
    }

    /** @return 是否追加写入 */
    public boolean isAppend() { return append; }
    /** @return 是否创建父目录 */
    public boolean isCreateParents() { return createParents; }
    /** @return 预期版本，未设置时为 null */
    public String getExpectedVersion() { return expectedVersion; }
    /** @return 文件写入选项构建器 */
    public static Builder builder() { return new Builder(); }
    /** @return 非追加写入、默认创建父目录的写入选项 */
    public static WriteOptions replace() { return builder().build(); }

    public static final class Builder {
        private boolean append;
        private boolean createParents = true;
        private String expectedVersion;
        /**
         * 设置是否追加写入。
         *
         * @param value 是否追加写入
         * @return 当前构建器
         */
        public Builder append(boolean value) { this.append = value; return this; }
        /**
         * 设置是否创建父目录。
         *
         * @param value 是否创建父目录
         * @return 当前构建器
         */
        public Builder createParents(boolean value) { this.createParents = value; return this; }
        /**
         * 设置预期版本。
         *
         * @param value 预期版本
         * @return 当前构建器
         */
        public Builder expectedVersion(String value) { this.expectedVersion = value; return this; }
        /** @return 根据当前配置创建的文件写入选项 */
        public WriteOptions build() { return new WriteOptions(this); }
    }
}
