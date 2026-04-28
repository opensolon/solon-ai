package org.noear.solon.ai.harness.code.impl;

import org.noear.solon.ai.harness.code.LanguageProvider;

/**
 *
 * @author noear 2026/4/28 created
 *
 */
public class MavenProvider implements LanguageProvider {
    @Override
    public String id() {
        return "Maven";
    }

    @Override
    public String typeName() {
        return "Maven 模块";
    }

    @Override
    public String[] markers() {
        return new String[]{"pom.xml"};
    }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"target"};
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (Maven)\n")
                .append("- 构建: `mvn clean compile`\n")
                .append("- 全量测试: `mvn test`\n")
                .append("- 单测执行: `mvn test -Dtest=ClassName` (替换为实际类名)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (Maven)\n")
                .append("- 构建: `cd ").append(moduleName).append(" && mvn clean compile`\n")
                .append("- 全量测试: `cd ").append(moduleName).append(" && mvn test`\n")
                .append("- 单测执行: `cd ").append(moduleName).append(" && mvn test -Dtest=ClassName` (替换为实际类名)\n\n");
    }
}