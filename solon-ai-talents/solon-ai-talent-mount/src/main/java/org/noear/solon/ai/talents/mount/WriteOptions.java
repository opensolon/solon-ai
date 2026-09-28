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

/** 文件写入选项。 */
public final class WriteOptions {
    private final boolean append;
    private final boolean createParents;
    private final String expectedVersion;

    private WriteOptions(Builder builder) {
        this.append = builder.append;
        this.createParents = builder.createParents;
        this.expectedVersion = builder.expectedVersion;
    }

    public boolean isAppend() { return append; }
    public boolean isCreateParents() { return createParents; }
    public String getExpectedVersion() { return expectedVersion; }
    public static Builder builder() { return new Builder(); }
    public static WriteOptions replace() { return builder().build(); }

    public static final class Builder {
        private boolean append;
        private boolean createParents = true;
        private String expectedVersion;
        public Builder append(boolean value) { this.append = value; return this; }
        public Builder createParents(boolean value) { this.createParents = value; return this; }
        public Builder expectedVersion(String value) { this.expectedVersion = value; return this; }
        public WriteOptions build() { return new WriteOptions(this); }
    }
}
