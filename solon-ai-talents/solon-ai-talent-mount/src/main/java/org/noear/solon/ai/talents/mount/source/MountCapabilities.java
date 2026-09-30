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

    /**
     * 创建挂载来源的操作能力配置。
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
     * 创建仅支持读取和搜索的虚拟挂载能力配置。
     *
     * @return 只读虚拟挂载能力配置
     */
    public static MountCapabilities readOnlyVirtual() {
        return new MountCapabilities(true, false, true, false, false, false,
                false, false, false, false);
    }
}
