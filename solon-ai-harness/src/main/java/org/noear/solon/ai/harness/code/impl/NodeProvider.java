package org.noear.solon.ai.harness.code.impl;

import org.noear.solon.ai.harness.code.LanguageProvider;

/**
 * @author noear 2026/4/28 created
 */
public class NodeProvider implements LanguageProvider {
    @Override
    public String id() {
        return "Node";
    }

    @Override
    public String typeName() {
        return "Node 项目";
    }

    @Override
    public String[] markers() {
        return new String[]{"package.json"};
    }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"node_modules"};
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (Node/TS)\n")
                .append("- 安装: `npm install`\n")
                .append("- 构建: `npm run build`\n")
                .append("- 全量测试: `npm test`\n")
                .append("- 单文件测试: `npm test -- path/to/file.test.ts` (替换为实际文件路径)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (Node/TS)\n")
                .append("- 安装: `cd ").append(moduleName).append(" && npm install`\n")
                .append("- 构建: `cd ").append(moduleName).append(" && npm run build`\n")
                .append("- 全量测试: `cd ").append(moduleName).append(" && npm test`\n")
                .append("- 单文件测试: `cd ").append(moduleName).append(" && npm test -- path/to/file.test.ts` (替换为实际文件路径)\n\n");
    }
}