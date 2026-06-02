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

import java.nio.file.Path;

/**
 * 技能目录
 *
 * @author noear
 * @since 3.11.0
 */
public class SkillDir {
    private final String name;
    private final String mountAlias;
    private final String aliasPath;
    private final Path realPath;
    private final String description;

    SkillDir(String name, String mountAlias, String aliasPath, Path realPath, String description) {
        this.name = name;
        this.mountAlias = mountAlias;
        this.aliasPath = aliasPath;
        this.realPath = realPath;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    /**
     * 持载别名
     */
    public String getMountAlias() {
        return mountAlias;
    }

    public String getAliasPath() {
        return aliasPath;
    }

    public Path getRealPath() {
        return realPath;
    }

    public String getDescription() {
        return description;
    }
}