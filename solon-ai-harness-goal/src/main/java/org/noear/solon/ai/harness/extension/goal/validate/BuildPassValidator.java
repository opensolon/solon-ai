package org.noear.solon.ai.harness.extension.goal.validate;

import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18n;
import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18nKeys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 构建通过验证器 — 自动检测项目类型并运行编译检查。
 *
 * <p>支持的项目类型：
 * <ul>
 *   <li>Maven (pom.xml → {@code mvn compile -q -o})</li>
 *   <li>Gradle (build.gradle / build.gradle.kts → {@code gradle compileJava -q})</li>
 * </ul>
 *
 * <p>没有已知构建工具时视为通过（没有可编译的对象，例如写文档类目标）；无执行目录、超时、
 * 执行失败返回 INCONCLUSIVE（由 {@link ValidatorPolicy} 决定去向）。命令经 {@link CommandRunner}
 * 在 OS 沙盒内执行。</p>
 *
 * <p>匹配含 build/compile/implement/refactor/fix/create/add/write 等关键字的目标。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class BuildPassValidator extends CommandValidator {

    public BuildPassValidator(CommandRunner runner) {
        super(runner);
    }

    @Override
    protected String detectCommand(Path workDir) {
        if (Files.exists(workDir.resolve("pom.xml"))) {
            return "mvn compile -q -o";
        }
        if (Files.exists(workDir.resolve("build.gradle")) || Files.exists(workDir.resolve("build.gradle.kts"))) {
            return "gradle compileJava -q";
        }
        return null;
    }

    @Override
    protected ValidationResult whenNothingToRun(Locale locale) {
        return ValidationResult.passed();
    }

    @Override
    protected String failurePrefix(Locale locale) {
        return GoalI18n.get(locale, GoalI18nKeys.GOAL_VALIDATE_BUILD_FAILED);
    }
}
