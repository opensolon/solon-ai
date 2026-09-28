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

/** 来源无关的查找参数。 */
public final class FindOptions {
    private final String glob;
    private final int maxDepth;
    private final int maxEntries;
    private final boolean filesOnly;
    private final boolean directoriesOnly;

    private FindOptions(Builder builder) {
        this.glob = builder.glob;
        this.maxDepth = builder.maxDepth;
        this.maxEntries = builder.maxEntries;
        this.filesOnly = builder.filesOnly;
        this.directoriesOnly = builder.directoriesOnly;
    }

    public String getGlob() { return glob; }
    public int getMaxDepth() { return maxDepth; }
    public int getMaxEntries() { return maxEntries; }
    public boolean isFilesOnly() { return filesOnly; }
    public boolean isDirectoriesOnly() { return directoriesOnly; }

    public static Builder builder() { return new Builder(); }
    public static FindOptions defaults() { return builder().build(); }
    public static FindOptions glob(String glob) { return builder().glob(glob).build(); }

    public static final class Builder {
        private String glob;
        private int maxDepth = Integer.MAX_VALUE;
        private int maxEntries = Integer.MAX_VALUE;
        private boolean filesOnly;
        private boolean directoriesOnly;

        public Builder glob(String value) { this.glob = value; return this; }
        public Builder maxDepth(int value) { this.maxDepth = value; return this; }
        public Builder maxEntries(int value) { this.maxEntries = value; return this; }
        public Builder filesOnly(boolean value) { this.filesOnly = value; return this; }
        public Builder directoriesOnly(boolean value) { this.directoriesOnly = value; return this; }
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
