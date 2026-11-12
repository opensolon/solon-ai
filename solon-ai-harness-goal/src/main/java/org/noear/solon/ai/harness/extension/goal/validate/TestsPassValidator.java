package org.noear.solon.ai.harness.extension.goal.validate;

import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18n;
import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18nKeys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 测试通过验证器 — 自动检测项目类型并运行测试。
 *
 * <p>支持的项目类型：
 * <ul>
 *   <li>Maven (pom.xml → {@code mvn test})</li>
 *   <li>Gradle (build.gradle / build.gradle.kts → {@code gradle test})</li>
 *   <li>Node (package.json → {@code npm test})</li>
 *   <li>Python (setup.py / pyproject.toml → {@code python3 -m pytest})</li>
 * </ul>
 *
 * <p>仅在目标条件包含 test/pass/check/ensure/verify 等关键字时自动触发。未检测到项目类型时判为未通过
 * （目标要求测试通过，却没有可跑的测试）；无执行目录、超时、执行失败返回 INCONCLUSIVE。
 * 命令经 {@link CommandRunner} 在 OS 沙盒内执行。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class TestsPassValidator extends CommandValidator {

    public TestsPassValidator(CommandRunner runner) {
        super(runner);
    }

    @Override
    protected String detectCommand(Path workDir) {
        if (Files.exists(workDir.resolve("pom.xml"))) {
            return "mvn test";
        }
        if (Files.exists(workDir.resolve("build.gradle")) || Files.exists(workDir.resolve("build.gradle.kts"))) {
            return "gradle test";
        }
        if (Files.exists(workDir.resolve("package.json"))) {
            return "npm test";
        }
        if (Files.exists(workDir.resolve("setup.py")) || Files.exists(workDir.resolve("pyproject.toml"))) {
            return "python3 -m pytest";
        }
        return null;
    }

    @Override
    protected ValidationResult whenNothingToRun(Locale locale) {
        return ValidationResult.failed(GoalI18n.get(locale, GoalI18nKeys.GOAL_VALIDATE_UNKNOWN_PROJECT));
    }

    @Override
    protected String failurePrefix(Locale locale) {
        return GoalI18n.get(locale, GoalI18nKeys.GOAL_VALIDATE_TESTS_FAILED);
    }
}
