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
package org.noear.solon.ai.talents.mount.source;

/** 挂载来源支持的操作能力。 */
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

    public boolean isReadable() { return readable; }
    public boolean isWritable() { return writable; }
    public boolean isSearchable() { return searchable; }
    public boolean isEditable() { return editable; }
    public boolean isDeletable() { return deletable; }
    public boolean isMovable() { return movable; }
    public boolean isWatchable() { return watchable; }
    public boolean isShellAccessible() { return shellAccessible; }
    public boolean isLocalPathAccessible() { return localPathAccessible; }
    public boolean isMaterializable() { return materializable; }

    public static MountCapabilities readOnlyVirtual() {
        return new MountCapabilities(true, false, true, false, false, false,
                false, false, false, false);
    }
}
