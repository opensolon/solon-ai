package org.noear.solon.ai.harness.extension.goal.validate;

/**
 * 验证结果 — 不可变值对象。
 *
 * @author noear
 * @since 3.9.4
 */
public class ValidationResult {
    /** 结论种类 */
    public enum Kind {
        /** 条件成立：验证通过 */
        PASSED,
        /** 条件不成立：验证未通过（不可重试成功，算完成失败） */
        FAILED,
        /** 无法判定（超时、执行异常、沙盒不可用、无执行目录）：由 {@link ValidatorPolicy} 决定去向 */
        INCONCLUSIVE
    }

    private final Kind kind;
    private final String detail;

    private ValidationResult(Kind kind, String detail) {
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
    }

    public static ValidationResult passed() {
        return new ValidationResult(Kind.PASSED, "");
    }

    public static ValidationResult failed(String detail) {
        return new ValidationResult(Kind.FAILED, detail);
    }

    /** @since 4.2.0 */
    public static ValidationResult inconclusive(String detail) {
        return new ValidationResult(Kind.INCONCLUSIVE, detail);
    }

    /** 仅 PASSED 为 true（INCONCLUSIVE 不算通过，去向由策略决定） */
    public boolean isPassed() {
        return kind == Kind.PASSED;
    }

    /** @since 4.2.0 */
    public boolean isInconclusive() {
        return kind == Kind.INCONCLUSIVE;
    }

    /** @since 4.2.0 */
    public Kind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }
}