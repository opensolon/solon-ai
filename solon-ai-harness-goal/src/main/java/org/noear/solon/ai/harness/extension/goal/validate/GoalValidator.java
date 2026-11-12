package org.noear.solon.ai.harness.extension.goal.validate;

import org.noear.solon.lang.Nullable;

import java.util.Locale;

/**
 * Goal 验证器接口 — 在标记目标达成前执行外部校验。
 *
 * <p>默认使用 {@link NoopValidator}（总是通过）；可注册自定义验证器实现客观校验，
 * 如运行测试套件、检查文件是否存在等。</p>
 *
 * <p><b>实例级</b>：注册表由 {@code GoalRuntime} 实例持有，不存在全局静态状态。
 * 验证器本身不绑定目录：执行目录随每次调用传入（取自本轮会话工作目录），
 * 同一引擎下不同会话可以在不同项目里验证。</p>
 *
 * @author noear
 * @since 3.9.4
 */
@FunctionalInterface
public interface GoalValidator {

    /**
     * 验证目标是否真实完成。
     *
     * @param condition 目标条件（如 "all tests pass"）
     * @param workDir   执行目录：本轮会话工作目录（工具上下文 {@code __cwd}，缺省为会话工作目录）；可能为 null
     * @return 验证结果
     */
    ValidationResult validate(String condition, String workDir);

    /**
     * 验证目标是否真实完成（带说明文案的语言）。
     *
     * <p>默认忽略语言、转调 {@link #validate(String, String)}；内置验证器按该语言生成说明。</p>
     *
     * @param locale 说明文案的语言（null 为默认语言）
     * @since 4.2.0
     */
    default ValidationResult validate(String condition, String workDir, @Nullable Locale locale) {
        return validate(condition, workDir);
    }
}
