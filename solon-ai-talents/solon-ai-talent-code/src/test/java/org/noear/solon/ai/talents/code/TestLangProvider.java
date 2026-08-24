package org.noear.solon.ai.talents.code;

import java.nio.file.Path;

/**
 * 仅用于测试 SPI 扩展点：通过 META-INF/services 注册后应自动参与识别。
 */
public class TestLangProvider implements LanguageProvider {
    @Override
    public String id() {
        return "MyLang";
    }

    @Override
    public String typeName() {
        return "MyLang 项目";
    }

    @Override
    public String[] markers() {
        return new String[]{"mytool.cfg"};
    }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"mytool-out"};
    }

    @Override
    public String detectVersion(Path dir) {
        String cfg = LanguageProvider.readText(dir, "mytool.cfg");
        String v = LanguageProvider.find(cfg, "version\\s*=\\s*\"([^\"]+)\"");
        return (v == null) ? null : "MyLang " + v;
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (MyLang)\n- 构建: `mytool build`\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (MyLang)\n")
                .append("- 构建: `cd ").append(moduleName).append(" && mytool build`\n\n");
    }
}
