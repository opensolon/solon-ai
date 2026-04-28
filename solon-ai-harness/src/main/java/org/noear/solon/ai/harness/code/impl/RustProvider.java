package org.noear.solon.ai.harness.code.impl;

import org.noear.solon.ai.harness.code.LanguageProvider;

/**
 *
 * @author noear 2026/4/28 created
 *
 */
public class RustProvider implements LanguageProvider {
    @Override
    public String id() {
        return "Rust";
    }

    @Override
    public String typeName() {
        return "Rust 项目";
    }

    @Override
    public String[] markers() {
        return new String[]{"Cargo.toml"};
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (Rust)\n")
                .append("- 构建: `cargo build`\n")
                .append("- 测试: `cargo test`\n")
                .append("- 运行: `cargo run`\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (Rust)\n")
                .append("- 构建: `cd ").append(moduleName).append(" && cargo build`\n")
                .append("- 测试: `cd ").append(moduleName).append(" && cargo test`\n\n");
    }
}