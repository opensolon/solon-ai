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

import lombok.Builder;

import java.io.Serializable;
import java.nio.file.Path;

/**
 * 挂载目录
 *
 * @author noear
 * @since 4.0.0
 */
@Builder
public class MountDir implements Serializable {
    //虚拟别名
    private String alias;
    //描述
    private String description;
    //挂载类型
    private MountType type;
    //配置地址支持 "~/"（用户目录相对位置） 和 "./"（工作区相对位置）
    private String path;

    //是否原始（不可删除）
    private boolean primary = false;
    //是否启用
    private boolean enabled = true;
    //是否可写
    private boolean writeable = false;

    //真实地址
    private transient Path realPath;


    public String getAlias() {
        return alias;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public MountType getType() {
        return type;
    }

    public boolean isPrimary() {
        return primary;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWriteable() {
        return writeable;
    }

    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    /**
     * 获取配置路径（原始值，如 "~/skills" 或 "./my-pool"）
     */
    public String getPath() {
        return path;
    }

    /**
     * 获取标准化后的物理路径
     */
    public Path getRealPath() {
        return realPath;
    }

    protected void setRealPath(Path realPath) {
        this.realPath = realPath;
    }
}