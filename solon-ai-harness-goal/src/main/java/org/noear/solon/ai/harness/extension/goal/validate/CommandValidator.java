package org.noear.solon.ai.harness.extension.goal.validate;

import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18n;
import org.noear.solon.ai.harness.extension.goal.i18n.GoalI18nKeys;
import org.noear.solon.ai.talents.cli.CommandResult;
import org.noear.solon.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Objects;

/**
 * 基于「探测项目类型 → 跑一条命令 → 看退出码」的验证器基类
 *
 * <p>命令一律经 {@link CommandRunner}（引擎 TerminalTalent 执行链：OS 沙盒、超时整树终止）执行；
 * 超时 / 执行失败 / 无执行目录一律返回 INCONCLUSIVE，去向由 {@link ValidatorPolicy} 决定，
 * 不再在这里「异常即通过」。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public abstract class CommandValidator implements GoalValidator {
    private static final Logger log = LoggerFactory.getLogger(CommandValidator.class);

    private final CommandRunner runner;

    protected CommandValidator(CommandRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * 按目录探测要执行的命令
     *
     * @return 命令；null 表示没有可验证的对象（由 {@link #whenNothingToRun(Locale)} 决定结论）
     */
    protected abstract String detectCommand(Path workDir);

    /**
     * 没有可执行命令时的结论
     *
     * @param locale 说明文案的语言（null 为默认语言）
     */
    protected abstract ValidationResult whenNothingToRun(@Nullable Locale locale);

    /** 失败文案前缀（如「编译失败」） */
    protected abstract String failurePrefix(@Nullable Locale locale);

    @Override
    public ValidationResult validate(String condition, String workDir) {
        return validate(condition, workDir, null);
    }

    @Override
    public ValidationResult validate(String condition, String workDir, @Nullable Locale locale) {
        if (workDir == null || workDir.isEmpty()) {
            return ValidationResult.inconclusive(GoalI18n.get(locale, GoalI18nKeys.GOAL_VALIDATE_WORKDIR_EMPTY));
        }

        String command;
        try {
            command = detectCommand(Paths.get(workDir));
        } catch (RuntimeException e) {
            return ValidationResult.inconclusive(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_VALIDATE_DETECT_FAILED, "error", e.getMessage()));
        }
        if (command == null) {
            return whenNothingToRun(locale);
        }

        log.info("{} 运行: {} (工作目录: {})", getClass().getSimpleName(), command, workDir);
        CommandResult r;
        try {
            r = runner.run(command, workDir);
        } catch (RuntimeException e) {
            return ValidationResult.inconclusive(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_VALIDATE_RUN_FAILED,
                    "command", command, "error", e.getMessage()));
        }
        if (r == null) {
            return ValidationResult.inconclusive(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_VALIDATE_RUN_NO_RESULT, "command", command));
        }

        switch (r.getStatus()) {
            case TIMED_OUT:
                return ValidationResult.inconclusive(GoalI18n.fmt(locale, GoalI18nKeys.GOAL_VALIDATE_RUN_TIMED_OUT, "command", command));
            case FAILED:
                return ValidationResult.inconclusive(tail(r.getText(), 300));
            default:
                if (r.getExitCode() == 0) {
                    return ValidationResult.passed();
                }
                return ValidationResult.failed(failurePrefix(locale) + " (exit=" + r.getExitCode() + "): "
                        + tail(r.getText(), 500));
        }
    }

    static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() > max ? t.substring(t.length() - max) : t;
    }
}
