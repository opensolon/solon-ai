package org.noear.solon.ai.harness.code.impl;

import org.noear.solon.ai.harness.code.LanguageProvider;

/**
 * @author noear 2026/4/28 created
 */
public class PythonProvider implements LanguageProvider {
    @Override public String id() { return "Python"; }
    @Override public String typeName() { return "Python 项目"; }
    @Override public String[] markers() { return new String[]{"requirements.txt", "pyproject.toml", "setup.py"}; }

    @Override
    public String[] ignoreFolders() {
        return new String[]{"venv", ".venv", "__pycache__"};
    }

    @Override
    public void appendRootCommands(StringBuilder buf) {
        buf.append("### 根项目 (Python)\n")
                .append("- 环境: 优先检查并激活 `venv` 或 `.venv`\n")
                .append("- 依赖: `pip install -r requirements.txt` (或使用 poetry/pdm)\n")
                .append("- 全量测试: `pytest` 或 `python -m unittest discover`\n")
                .append("- 单文件测试: `pytest path/to/test_file.py` (替换为实际路径)\n\n");
    }

    @Override
    public void appendModuleCommands(StringBuilder buf, String moduleName) {
        buf.append("### 模块 (Module): ").append(moduleName).append(" (Python)\n")
                .append("- 环境: 优先检查并激活 `venv` 或 `.venv`\n")
                .append("- 依赖: `cd ").append(moduleName).append(" && pip install -r requirements.txt`\n")
                .append("- 全量测试: `cd ").append(moduleName).append(" && pytest`\n")
                .append("- 单文件测试: `cd ").append(moduleName).append(" && pytest path/to/test_file.py` (替换为实际路径)\n\n");
    }
}