package org.noear.solon.ai.harness.extension.goal.validate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空操作验证器 — 总是通过验证。
 *
 * <p>作为默认验证器，确保引入验证机制后不改变现有功能。
 * 当目标条件不匹配任何自定义验证器时使用此实现。
 * 会输出 warn 日志提醒无验证器匹配。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class NoopValidator implements GoalValidator {

    private static final Logger log = LoggerFactory.getLogger(NoopValidator.class);

    @Override
    public ValidationResult validate(String condition) {
        log.warn("No validator matched for goal '{}', complete auto-approved", condition);
        return ValidationResult.passed();
    }
}
