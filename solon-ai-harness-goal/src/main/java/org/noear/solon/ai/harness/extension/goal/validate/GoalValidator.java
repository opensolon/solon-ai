package org.noear.solon.ai.harness.extension.goal.validate;

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
     * 验证结果 — 不可变值对象。
     */
    final class ValidationResult {
        /** 结论种类 */
        public enum Kind {
            PASSED,
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

        public String detail() {
            return detail;
        }
    }
}
