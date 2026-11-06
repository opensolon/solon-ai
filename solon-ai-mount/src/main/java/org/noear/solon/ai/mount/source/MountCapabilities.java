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
package org.noear.solon.ai.mount.source;

import org.noear.solon.lang.Preview;

/** 
 * 挂载来源支持的操作能力（只能经 {@link #builder()} 构建，未声明的能力一律为 false）。
 *
 * <p>4.2.0 起删除了 {@code editable}（与 writable 重叠：编辑 = 读 + 写）与 {@code watchable}
 * （没有配套的监听接口；本地监听请以 {@link #isLocalPathAccessible()} 判断）。</p>
 *
 * @author noear
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class MountCapabilities {
    private final boolean readable;
    private final boolean writable;
    private final boolean searchable;
    private final boolean deletable;
    private final boolean movable;
    private final boolean shellAccessible;
    private final boolean localPathAccessible;
    private final boolean materializable;
    private final boolean versioned;
    private final boolean atomicWrite;

    private MountCapabilities(Builder b) {
        this.readable = b.readable;
        this.writable = b.writable;
        this.searchable = b.searchable;
        this.deletable = b.deletable;
        this.movable = b.movable;
        this.shellAccessible = b.shellAccessible;
        this.localPathAccessible = b.localPathAccessible;
        this.materializable = b.materializable;
        this.versioned = b.versioned;
        this.atomicWrite = b.atomicWrite;
    }

    /**
     * 能力构建器（全部默认 false）。
     *
     * @since 4.2.0
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 只读来源的常用组合（可读、可搜索）。
     *
     * @since 4.2.0
     */
    public static MountCapabilities readOnly() {
        return builder().readable(true).searchable(true).build();
    }

    /** @return 是否可读取 */
    public boolean isReadable() { return readable; }
    /** @return 是否可写入 */
    public boolean isWritable() { return writable; }
    /** @return 是否可搜索 */
    public boolean isSearchable() { return searchable; }
    /** @return 是否可删除 */
    public boolean isDeletable() { return deletable; }
    /** @return 是否可移动 */
    public boolean isMovable() { return movable; }
    /** @return 是否可通过 Shell 访问 */
    public boolean isShellAccessible() { return shellAccessible; }
    /** @return 是否可访问本地路径 */
    public boolean isLocalPathAccessible() { return localPathAccessible; }
    /** @return 是否可物化 */
    public boolean isMaterializable() { return materializable; }

    /**
     * 是否提供可靠版本：{@link MountEntry#getVersion()} 非空，且 {@link WriteOptions#getExpectedVersion()}
     * 不匹配时 {@code openWrite} 抛出 {@link MountConflictException}（乐观锁）。
     *
     * @return 是否支持版本
     * @since 4.2.0
     */
    public boolean isVersioned() { return versioned; }

    /**
     * 覆盖写是否原子可见：写入流 close 成功前，读者只能看到旧内容；写入失败不留残留。
     *
     * @return 是否原子写
     * @since 4.2.0
     */
    public boolean isAtomicWrite() { return atomicWrite; }

    /**
     * 能力构建器。
     *
     * @since 4.2.0
     */
    public static final class Builder {
        private boolean readable;
        private boolean writable;
        private boolean searchable;
        private boolean deletable;
        private boolean movable;
        private boolean shellAccessible;
        private boolean localPathAccessible;
        private boolean materializable;
        private boolean versioned;
        private boolean atomicWrite;

        public Builder readable(boolean v) { this.readable = v; return this; }
        public Builder writable(boolean v) { this.writable = v; return this; }
        public Builder searchable(boolean v) { this.searchable = v; return this; }
        public Builder deletable(boolean v) { this.deletable = v; return this; }
        public Builder movable(boolean v) { this.movable = v; return this; }
        public Builder shellAccessible(boolean v) { this.shellAccessible = v; return this; }
        public Builder localPathAccessible(boolean v) { this.localPathAccessible = v; return this; }
        public Builder materializable(boolean v) { this.materializable = v; return this; }
        public Builder versioned(boolean v) { this.versioned = v; return this; }
        public Builder atomicWrite(boolean v) { this.atomicWrite = v; return this; }

        /** 可读写的常用组合：读、写、搜索、删除、移动。 */
        public Builder readWrite() {
            return readable(true).writable(true).searchable(true).deletable(true).movable(true);
        }

        public MountCapabilities build() { return new MountCapabilities(this); }
    }
}
