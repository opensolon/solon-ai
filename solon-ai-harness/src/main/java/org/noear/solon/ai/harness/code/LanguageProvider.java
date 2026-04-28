package org.noear.solon.ai.harness.code;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public interface LanguageProvider {
    /** 语言 ID (如 "Maven", "Go") */
    String id();

    /** 模块类型描述 (如 "Maven 模块", "Go 模块") */
    String typeName();

    /** 静态标志文件列表。用于 isSupported 的快速全局判断。如果是 C#，可以返回空数组，由 isMatch 处理。 */
    String[] markers();

    /** 该语言特有的忽略目录 (如 Python 的 __pycache__) */
    default String[] ignoreFolders() { return new String[0]; }

    /** 核心匹配逻辑。默认通过 markers 匹配，子类可重写以支持复杂逻辑 (如 .sln) */
    default boolean isMatch(Path dir) {
        return Arrays.stream(markers()).anyMatch(m -> Files.exists(dir.resolve(m)));
    }

    /** 根项目指令生成 */
    void appendRootCommands(StringBuilder buf);

    /** 子模块指令生成 */
    void appendModuleCommands(StringBuilder buf, String moduleName);
}