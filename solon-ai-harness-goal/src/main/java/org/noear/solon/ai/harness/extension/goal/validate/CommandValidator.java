package org.noear.solon.ai.harness.extension.goal.validate;

import org.noear.solon.ai.talents.cli.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
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
     * @return 命令；null 表示没有可验证的对象（由 {@link #whenNothingToRun()} 决定结论）
     */
    protected abstract String detectCommand(Path workDir);

    /** 没有可执行命令时的结论 */
    protected abstract ValidationResult whenNothingToRun();

    /** 失败文案前缀（如「编译失败」） */
    protected abstract String failurePrefix();

    @Override
    public ValidationResult validate(String condition, String workDir) {
        if (workDir == null || workDir.isEmpty()) {
            return ValidationResult.inconclusive("工作目录为空，无法验证");
        }

        String command;
        try {
            command = detectCommand(Paths.get(workDir));
        } catch (RuntimeException e) {
            return ValidationResult.inconclusive("探测项目类型失败: " + e.getMessage());
        }
        if (command == null) {
            return whenNothingToRun();
        }

        log.info("{} 运行: {} (工作目录: {})", getClass().getSimpleName(), command, workDir);
        CommandResult r;
        try {
            r = runner.run(command, workDir);
        } catch (RuntimeException e) {
            return ValidationResult.inconclusive("执行 '" + command + "' 失败: " + e.getMessage());
        }
        if (r == null) {
            return ValidationResult.inconclusive("执行 '" + command + "' 无结果");
        }

        switch (r.getStatus()) {
            case TIMED_OUT:
                return ValidationResult.inconclusive("执行 '" + command + "' 超时");
            case FAILED:
                return ValidationResult.inconclusive(tail(r.getText(), 300));
            default:
                if (r.getExitCode() == 0) {
                    return ValidationResult.passed();
                }
                return ValidationResult.failed(failurePrefix() + " (exit=" + r.getExitCode() + "): "
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
