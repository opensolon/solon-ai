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
 * 
 * 挂载来源支持的操作能力。 
 *
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class MountCapabilities {
    private final boolean readable;
    private final boolean writable;
    private final boolean searchable;
    private final boolean editable;
    private final boolean deletable;
    private final boolean movable;
    private final boolean watchable;
    private final boolean shellAccessible;
    private final boolean localPathAccessible;
    private final boolean materializable;
    private final boolean versioned;
    private final boolean atomicWrite;

    /**
     * 创建挂载来源的操作能力配置（versioned / atomicWrite 为 false；新代码建议用 {@link #builder()}）。
     *
     * @param readable 是否可读取
     * @param writable 是否可写入
     * @param searchable 是否可搜索
     * @param editable 是否可编辑
     * @param deletable 是否可删除
     * @param movable 是否可移动
     * @param watchable 是否可监听变化
     * @param shellAccessible 是否可通过 Shell 访问
     * @param localPathAccessible 是否可访问本地路径
     * @param materializable 是否可物化
     */
    public MountCapabilities(boolean readable, boolean writable, boolean searchable,
                             boolean editable, boolean deletable, boolean movable,
                             boolean watchable, boolean shellAccessible,
                             boolean localPathAccessible, boolean materializable) {
        this.readable = readable;
        this.writable = writable;
        this.searchable = searchable;
        this.editable = editable;
        this.deletable = deletable;
        this.movable = movable;
        this.watchable = watchable;
        this.shellAccessible = shellAccessible;
        this.localPathAccessible = localPathAccessible;
        this.materializable = materializable;
        this.versioned = false;
        this.atomicWrite = false;
    }

    private MountCapabilities(Builder b) {
        this.readable = b.readable;
        this.writable = b.writable;
        this.searchable = b.searchable;
        this.editable = b.editable;
        this.deletable = b.deletable;
        this.movable = b.movable;
        this.watchable = b.watchable;
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
    /** @return 是否可编辑 */
    public boolean isEditable() { return editable; }
    /** @return 是否可删除 */
    public boolean isDeletable() { return deletable; }
    /** @return 是否可移动 */
    public boolean isMovable() { return movable; }
    /** @return 是否可监听变化 */
    public boolean isWatchable() { return watchable; }
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
        private boolean editable;
        private boolean deletable;
        private boolean movable;
        private boolean watchable;
        private boolean shellAccessible;
        private boolean localPathAccessible;
        private boolean materializable;
        private boolean versioned;
        private boolean atomicWrite;

        public Builder readable(boolean v) { this.readable = v; return this; }
        public Builder writable(boolean v) { this.writable = v; return this; }
        public Builder searchable(boolean v) { this.searchable = v; return this; }
        public Builder editable(boolean v) { this.editable = v; return this; }
        public Builder deletable(boolean v) { this.deletable = v; return this; }
        public Builder movable(boolean v) { this.movable = v; return this; }
        public Builder watchable(boolean v) { this.watchable = v; return this; }
        public Builder shellAccessible(boolean v) { this.shellAccessible = v; return this; }
        public Builder localPathAccessible(boolean v) { this.localPathAccessible = v; return this; }
        public Builder materializable(boolean v) { this.materializable = v; return this; }
        public Builder versioned(boolean v) { this.versioned = v; return this; }
        public Builder atomicWrite(boolean v) { this.atomicWrite = v; return this; }

        /** 可读写的常用组合：读、写、搜索、编辑、删除、移动。 */
        public Builder readWrite() {
            return readable(true).writable(true).searchable(true).editable(true).deletable(true).movable(true);
        }

        public MountCapabilities build() { return new MountCapabilities(this); }
    }
}
