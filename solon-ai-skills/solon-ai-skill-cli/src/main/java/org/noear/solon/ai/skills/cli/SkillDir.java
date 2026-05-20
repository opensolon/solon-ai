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

/**
 * 技能目录
 *
 * @author noear
 * @since 3.11.0
 */
public class SkillDir {
    public final String name;
    public final String aliasPath;
    public final Path realPath;
    public final String description;

    SkillDir(String name, String aliasPath, Path realPath, String description) {
        this.name = name;
        this.aliasPath = aliasPath;
        this.realPath = realPath;
        this.description = description;
    }

    public String getName() {
        return name;
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