package org.noear.solon.ai.loop.state;

import org.noear.solon.ai.loop.state.disk.DiskStateManager;

/**
 * Session 身份验证器 —— 确保 session_id + project_path 双重隔离
 *
 * <p>对标 oh-my-claudecode 的 session_id + project_path 绑定机制。
 * 防止跨项目、跨工作区的 session 访问泄漏。</p>
 *
 * @since 4.0.3
 */
public class SessionIdentityValidator {

    private final DiskStateManager stateManager;

    public SessionIdentityValidator(DiskStateManager stateManager) {
        this.stateManager = stateManager;
    }

    /**
     * 验证 Session 身份。
     *
     * @param sessionId   会话 ID
     * @param mode        模式名
     * @param projectPath 项目路径
     * @return 验证结果
     */
    public ValidationResult validate(String sessionId, String mode, String projectPath) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return ValidationResult.invalid("Session ID cannot be empty");
        }
        if (mode == null || mode.trim().isEmpty()) {
            return ValidationResult.invalid("Mode cannot be empty");
        }
        if (projectPath == null || projectPath.trim().isEmpty()) {
            return ValidationResult.invalid("Project path cannot be empty");
        }

        // 验证 session 所有权
        boolean owned = stateManager.validateSession(sessionId, mode, projectPath);
        if (!owned && stateManager.getAllActiveSessionIds().contains(sessionId)) {
            return ValidationResult.invalid(
                    "Session " + sessionId + " does not belong to project: " + projectPath
            );
        }

        return ValidationResult.valid();
    }

    /**
     * 验证一个 session 是否属于当前执行环境。
     *
     * @param sessionId   会话 ID
     * @param projectPath 项目路径
     * @return true 如果 session 属于该项目
     */
    public boolean belongsToProject(String sessionId, String projectPath) {
        return stateManager.validateSession(sessionId, null, projectPath);
    }

    /**
     * 严格的 Session 创建前校验。
     * 确保该项目下没有同名 mode 的活跃会话。
     *
     * @param sessionId   会话 ID
     * @param mode        模式名
     * @param projectPath 项目路径
     * @return 校验结果
     */
    public ValidationResult canCreateSession(String sessionId, String mode, String projectPath) {
        // 基本校验
        ValidationResult basic = validate(sessionId, mode, projectPath);
        if (!basic.isValid()) {
            return basic;
        }

        // 检查该项目下是否已有同模式活跃会话
        boolean stateExists = stateManager.hasState(mode, sessionId);
        if (stateExists) {
            return ValidationResult.invalid(
                    "Session already exists for mode '" + mode + "' in project: " + projectPath
            );
        }

        return ValidationResult.valid();
    }

    /**
     * 验证结果。
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String reason;

        private ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }

        public boolean isValid() { return valid; }
        public String getReason() { return reason; }
    }
}
