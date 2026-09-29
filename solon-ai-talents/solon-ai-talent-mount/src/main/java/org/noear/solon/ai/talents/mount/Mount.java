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
package org.noear.solon.ai.talents.mount;

import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.lang.Preview;

/** 
 * 挂载配置与内容来源。 
 *
 * @author noear 
 * @since 3.9.5
 * @since 4.1.1
 */
@Preview("4.1.1")
public final class Mount {
    private final String alias;
    private final String description;
    private final MountType type;
    private final boolean primary;
    private final boolean enabled;
    private final boolean writeable;
    private final boolean visible;
    private final MountSource source;

    private Mount(Builder builder) {
        this.alias = builder.alias;
        this.description = builder.description;
        this.type = builder.type;
        this.primary = builder.primary;
        this.enabled = builder.enabled;
        this.writeable = builder.writeable;
        this.visible = builder.visible;
        this.source = builder.source;
    }

    public String getAlias() { return alias; }
    public String getDescription() { return description; }
    public MountType getType() { return type; }
    public boolean isPrimary() { return primary; }
    public boolean isEnabled() { return enabled; }
    public boolean isWriteable() { return writeable; }
    /** 是否在上层展示；不影响挂载的启用、解析和运行时使用。 */
    public boolean isVisible() { return visible; }
    public MountSource getSource() { return source; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String alias;
        private String description;
        private MountType type = MountType.FILES;
        private boolean primary;
        private boolean enabled = true;
        private boolean writeable;
        private boolean visible = true;
        private MountSource source;

        public Builder alias(String value) { this.alias = value; return this; }
        public Builder description(String value) { this.description = value; return this; }
        public Builder type(MountType value) { this.type = value; return this; }
        public Builder primary(boolean value) { this.primary = value; return this; }
        public Builder enabled(boolean value) { this.enabled = value; return this; }
        public Builder writeable(boolean value) { this.writeable = value; return this; }
        public Builder visible(boolean value) { this.visible = value; return this; }
        public Builder source(MountSource value) { this.source = value; return this; }
        public Mount build() {
            if (source == null) throw new IllegalArgumentException("source must not be null");
            return new Mount(this);
        }
    }
}
