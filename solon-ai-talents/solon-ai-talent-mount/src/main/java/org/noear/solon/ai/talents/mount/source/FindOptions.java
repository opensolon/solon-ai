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
 * 来源无关的查找参数。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class FindOptions {
    private final String glob;
    private final int maxDepth;
    private final int maxEntries;
    private final boolean filesOnly;
    private final boolean directoriesOnly;

    /**
     * 根据构建器创建查找选项。
     *
     * @param builder 已配置的构建器
     */
    private FindOptions(Builder builder) {
        this.glob = builder.glob;
        this.maxDepth = builder.maxDepth;
        this.maxEntries = builder.maxEntries;
        this.filesOnly = builder.filesOnly;
        this.directoriesOnly = builder.directoriesOnly;
    }

    /**
     * 获取路径匹配模式。
     *
     * @return 路径匹配模式
     */
    public String getGlob() { return glob; }
    /**
     * 获取最大查找深度。
     *
     * @return 最大查找深度
     */
    public int getMaxDepth() { return maxDepth; }
    /**
     * 获取最多返回的条目数。
     *
     * @return 最大条目数
     */
    public int getMaxEntries() { return maxEntries; }
    /**
     * 是否仅查找文件。
     *
     * @return 仅查找文件时为 true
     */
    public boolean isFilesOnly() { return filesOnly; }
    /**
     * 是否仅查找目录。
     *
     * @return 仅查找目录时为 true
     */
    public boolean isDirectoriesOnly() { return directoriesOnly; }

    /**
     * 创建查找选项构建器。
     *
     * @return 构建器
     */
    public static Builder builder() { return new Builder(); }
    /**
     * 创建默认查找选项。
     *
     * @return 默认选项
     */
    public static FindOptions defaults() { return builder().build(); }
    /**
     * 创建指定路径匹配模式的查找选项。
     *
     * @param glob 路径匹配模式
     * @return 查找选项
     */
    public static FindOptions glob(String glob) { return builder().glob(glob).build(); }

    /**
     * 查找选项构建器；未设置限制时不限制深度和条目数。
     */
    public static final class Builder {
        private String glob;
        private int maxDepth = Integer.MAX_VALUE;
        private int maxEntries = Integer.MAX_VALUE;
        private boolean filesOnly;
        private boolean directoriesOnly;

        /**
         * 设置路径匹配模式。
         *
         * @param value 路径匹配模式
         * @return 当前构建器
         */
        public Builder glob(String value) { this.glob = value; return this; }
        /**
         * 设置最大查找深度。
         *
         * @param value 非负最大深度
         * @return 当前构建器
         */
        public Builder maxDepth(int value) { this.maxDepth = value; return this; }
        /**
         * 设置最多返回的条目数。
         *
         * @param value 非负最大条目数
         * @return 当前构建器
         */
        public Builder maxEntries(int value) { this.maxEntries = value; return this; }
        /**
         * 设置是否仅查找文件。
         *
         * @param value 仅查找文件时为 true
         * @return 当前构建器
         */
        public Builder filesOnly(boolean value) { this.filesOnly = value; return this; }
        /**
         * 设置是否仅查找目录。
         *
         * @param value 仅查找目录时为 true
         * @return 当前构建器
         */
        public Builder directoriesOnly(boolean value) { this.directoriesOnly = value; return this; }
        /**
         * 校验配置并创建查找选项。
         *
         * @return 查找选项
         * @throws IllegalArgumentException 深度或条目数为负，或同时限定仅文件和仅目录时
         */
        public FindOptions build() {
            if (maxDepth < 0 || maxEntries < 0) {
                throw new IllegalArgumentException("maxDepth/maxEntries must not be negative");
            }
            if (filesOnly && directoriesOnly) {
                throw new IllegalArgumentException("filesOnly and directoriesOnly are mutually exclusive");
            }
            return new FindOptions(this);
        }
    }
}
