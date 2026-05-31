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
package org.noear.solon.ai.skills.cli;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 挂载池目录
 *
 * @author noear
 * @since 4.0.0
 */
public class PoolDir {
    private String alias;
    //是否原生性的
    private boolean primary;
    //是否启用
    private boolean enabled = true;

    //配置地址支持 "~/"（用户目录相对位置） 和 "./"（工作区相对位置）
    private String path;
    //真实地址
    private Path realPath;

    public PoolDir(String alias, boolean primary, String path, Path realPath) {
        this.alias = alias.startsWith("@") ? alias : "@" + alias;
        this.primary = primary;
        this.path = path;
        this.realPath = realPath.toAbsolutePath().normalize();
    }

    public String getAlias() {
        return alias;
    }

    public boolean isPrimary() {
        return primary;
    }

    public boolean isEnabled() {
        return enabled;
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
}