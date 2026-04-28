/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.code.impl;

import org.noear.solon.ai.harness.code.LanguageProvider;

/**
 * @author noear
 * @since 3.10.5
 */
public class PhpProvider implements LanguageProvider {
    @Override public String id() { return "PHP"; }
    @Override public String typeName() { return "PHP 项目"; }
    @Override public String[] markers() { return new String[]{"composer.json"}; }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"vendor"};
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (PHP/Composer)\n")
                .append("- 依赖: `composer install`\n")
                .append("- 全量测试: `./vendor/bin/phpunit`\n")
                .append("- 单文件测试: `./vendor/bin/phpunit path/to/TestFile.php` (替换为实际路径)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (PHP/Composer)\n")
                .append("- 依赖: `cd ").append(moduleName).append(" && composer install`\n")
                .append("- 全量测试: `cd ").append(moduleName).append(" && ./vendor/bin/phpunit`\n")
                .append("- 单文件测试: `cd ").append(moduleName).append(" && ./vendor/bin/phpunit path/to/TestFile.php`\n\n");
    }
}