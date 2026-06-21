package org.noear.solon.ai.loop.strategy;

import org.noear.solon.ai.loop.engine.IterationResult;
import org.noear.solon.ai.loop.state.LoopState;
import org.noear.solon.ai.loop.validator.QualityGate;
import org.noear.solon.ai.loop.validator.ValidationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * UltraQA 循环策略（重写版）—— 对标 oh-my-claudecode 的 ultraqa/index.ts。
 *
 * <p>质量门禁循环，重复测试直到通过。</p>
 *
 * <p>新增对标特性：</p>
 * <ul>
 *   <li>目标类型：TESTS / BUILD / LINT / TYPECHECK / CUSTOM</li>
 *   <li>失败追踪：failures[] 数组 + 同失败检测（阈值 3 次）</li>
 *   <li>退出原因：GOAL_MET / MAX_CYCLES / SAME_FAILURE / ENV_ERROR / CANCELLED</li>
 * </ul>
 *
 * @since 4.0.3
 */
public class UltraQAStrategy extends AbstractLoopStrategy {

    public static final String NAME = "ultra-qa";
    public static final String DESCRIPTION = "质量门禁循环，重复测试直到通过";
    public static final int SAME_FAILURE_THRESHOLD = 3;

    private final List<QualityGate> gates;
    private final boolean parallelTesting;
    private final int maxTestAttempts;
    private final UltraQAGoalType goalType;

    public UltraQAStrategy() {
        this(Arrays.asList(QualityGate.build(), QualityGate.test()), false, 10, UltraQAGoalType.TESTS);
    }

    public UltraQAStrategy(List<QualityGate> gates, boolean parallelTesting,
                           int maxTestAttempts, UltraQAGoalType goalType) {
        super(NAME, DESCRIPTION, 100, parallelTesting);
        this.gates = gates != null ? gates : Arrays.asList(QualityGate.build(), QualityGate.test());
        this.parallelTesting = parallelTesting;
        this.maxTestAttempts = maxTestAttempts;
        this.goalType = goalType;
    }

    @Override
    protected boolean shouldContinueInternal(LoopContext context) {
        // 检查退出条件
        UltraQAExitReason exitReason = getExitReason(context);
        if (exitReason != null) return false;

        // 检查最大循环次数
        int cycles = getCycleCount(context);
        if (cycles >= maxTestAttempts) {
            setExitReason(context, UltraQAExitReason.MAX_CYCLES);
            return false;
        }

        // 检查同失败检测
        if (hasSameFailure(context)) {
            setExitReason(context, UltraQAExitReason.SAME_FAILURE);
            return false;
        }

        return !allGatesPassed(context);
    }

    @Override
    protected IterationResult executeIterationInternal(LoopContext context) {
        Instant startTime = Instant.now();
        incrementCycleCount(context);

        // 获取下一个未通过的门禁
        QualityGate gate = getNextGate(context);
        if (gate == null) {
            setExitReason(context, UltraQAExitReason.GOAL_MET);
            return createCompletedResult(context, startTime, "All quality gates passed");
        }

        // 执行质量门禁
        ValidationResult result = executeQualityGate(gate, context);
        updateGateStatus(context, gate, result.isPassed());

        // 记录失败（用于同失败检测）
        if (!result.isPassed()) {
            recordFailure(context, normalizeFailure(gate.getName() + ": " + result.getMessage()));
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("gate", gate.getName());
        metadata.put("passed", result.isPassed());
        metadata.put("goalType", goalType.name());
        metadata.put("cycle", getCycleCount(context));
        metadata.put("failures", getFailureCount(context));

        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);

        return new IterationResult(
                context.getIterationCount() + 1,
                context.getMaxIterations(),
                result.isPassed() ? LoopState.VERIFYING : LoopState.FIXING,
                result,
                result.isPassed(),
                result.getMessage(),
                duration,
                startTime,
                endTime,
                metadata
        );
    }

    // ===== 目标类型相关 =====

    /**
     * 获取适用于当前目标类型的命令描述。
     */
    public String getGoalCommand() {
        switch (goalType) {
            case TESTS:
                return "mvn test";
            case BUILD:
                return "mvn compile";
            case LINT:
                return "mvn checkstyle:check";
            case TYPECHECK:
                return "mvn compile"; // Java 编译时检查
            case CUSTOM:
                return "custom check";
            default:
                return "mvn test";
        }
    }

    // ===== 同失败检测 =====

    @SuppressWarnings("unchecked")
    private void recordFailure(LoopContext context, String failureDescription) {
        List<String> failures = (List<String>) context.get("failures");
        if (failures == null) {
            failures = new ArrayList<>();
            context.getContextData().put("failures", failures);
        }
        failures.add(failureDescription);
    }

    @SuppressWarnings("unchecked")
    private boolean hasSameFailure(LoopContext context) {
        List<String> failures = (List<String>) context.get("failures");
        if (failures == null || failures.size() < SAME_FAILURE_THRESHOLD) {
            return false;
        }

        // 检查最近 SAME_FAILURE_THRESHOLD 次失败是否相同
        int size = failures.size();
        String lastFailure = failures.get(size - 1);
        int count = 0;
        for (int i = size - 1; i >= Math.max(0, size - SAME_FAILURE_THRESHOLD); i--) {
            if (failures.get(i).equals(lastFailure)) {
                count++;
            }
        }
        return count >= SAME_FAILURE_THRESHOLD;
    }

    @SuppressWarnings("unchecked")
    private int getFailureCount(LoopContext context) {
        List<String> failures = (List<String>) context.get("failures");
        return failures != null ? failures.size() : 0;
    }

    /**
     * 规范化失败描述（去除时间戳、行号等变量部分，使可比较）。
     */
    private String normalizeFailure(String failure) {
        if (failure == null) return "";
        // 去除行号信息
        return failure.replaceAll(":\\d+", ":N")
                .replaceAll("line \\d+", "line N")
                .replaceAll("\\[\\d+ms\\]", "[Nms]")
                .replaceAll("\\d+\\.\\d+\\.\\d+", "X.Y.Z");
    }

    // ===== 退出原因管理 =====

    private UltraQAExitReason getExitReason(LoopContext context) {
        String reason = (String) context.get("exitReason");
        if (reason == null) return null;
        try {
            return UltraQAExitReason.valueOf(reason);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void setExitReason(LoopContext context, UltraQAExitReason reason) {
        context.getContextData().put("exitReason", reason.name());
    }

    /**
     * 获取退出原因。
     */
    public UltraQAExitReason getFinalExitReason(LoopContext context) {
        UltraQAExitReason reason = getExitReason(context);
        if (reason != null) return reason;
        // 如果所有门禁通过则返回 GOAL_MET
        if (allGatesPassed(context)) return UltraQAExitReason.GOAL_MET;
        return UltraQAExitReason.MAX_CYCLES;
    }

    // ===== 周期计数 =====

    private int getCycleCount(LoopContext context) {
        Object count = context.get("qaCycleCount");
        return count instanceof Integer ? (Integer) count : 0;
    }

    private void incrementCycleCount(LoopContext context) {
        int count = getCycleCount(context) + 1;
        context.getContextData().put("qaCycleCount", count);
    }

    // ===== 门禁管理 =====

    private boolean allGatesPassed(LoopContext context) {
        Map<String, Boolean> gateStatus = getGateStatus(context);
        for (QualityGate gate : gates) {
            if (!gateStatus.getOrDefault(gate.getName(), false)) {
                return false;
            }
        }
        return true;
    }

    private QualityGate getNextGate(LoopContext context) {
        Map<String, Boolean> gateStatus = getGateStatus(context);
        for (QualityGate gate : gates) {
            if (!gateStatus.getOrDefault(gate.getName(), false)) {
                return gate;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Boolean> getGateStatus(LoopContext context) {
        Object statusObj = context.get("gateStatus");
        if (statusObj instanceof Map) {
            return (Map<String, Boolean>) statusObj;
        }
        return new HashMap<>();
    }

    private void updateGateStatus(LoopContext context, QualityGate gate, boolean passed) {
        Map<String, Boolean> gateStatus = getGateStatus(context);
        Map<String, Object> contextData = context.getContextData();
        gateStatus.put(gate.getName(), passed);
        contextData.put("gateStatus", gateStatus);
    }

    // ===== 质量门禁执行 =====

    private ValidationResult executeQualityGate(QualityGate gate, LoopContext context) {
        List<String> checks = gate.getChecks();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String check : checks) {
            try {
                boolean passed = executeCheck(check, gate, context);
                if (!passed) {
                    if (gate.isBlocking()) {
                        errors.add("Check failed: " + check);
                    } else {
                        warnings.add("Check failed: " + check);
                    }
                }
            } catch (Exception e) {
                errors.add("Check error: " + check + " - " + e.getMessage());
            }
        }

        if (errors.isEmpty()) {
            return ValidationResult.passed("All checks passed for gate: " + gate.getName());
        } else {
            return ValidationResult.needsFix("Gate failed: " + gate.getName(), errors);
        }
    }

    private boolean executeCheck(String check, QualityGate gate, LoopContext context) {
        switch (check) {
            case "compilation": return executeCompilationCheck(context);
            case "dependencies": return executeDependencyCheck(context);
            case "unit-tests": return executeUnitTestCheck(context);
            case "integration-tests": return executeIntegrationTestCheck(context);
            case "style": return executeStyleCheck(context);
            case "complexity": return executeComplexityCheck(context);
            case "duplication": return executeDuplicationCheck(context);
            default: return true;
        }
    }

    // 可覆盖的方法
    protected boolean executeCompilationCheck(LoopContext context) { return true; }
    protected boolean executeDependencyCheck(LoopContext context) { return true; }
    protected boolean executeUnitTestCheck(LoopContext context) { return true; }
    protected boolean executeIntegrationTestCheck(LoopContext context) { return true; }
    protected boolean executeStyleCheck(LoopContext context) { return true; }
    protected boolean executeComplexityCheck(LoopContext context) { return true; }
    protected boolean executeDuplicationCheck(LoopContext context) { return true; }

    // ===== 辅助方法 =====

    private IterationResult createCompletedResult(LoopContext context, Instant startTime, String message) {
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put("goalType", goalType.name());
        metadata.put("exitReason", String.valueOf(getFinalExitReason(context)));
        metadata.put("totalCycles", getCycleCount(context));
        return new IterationResult(
                context.getIterationCount() + 1,
                context.getMaxIterations(),
                LoopState.COMPLETED,
                null, true, message, duration, startTime, endTime,
                metadata
        );
    }

    public List<QualityGate> getGates() { return gates; }
    public boolean isParallelTesting() { return parallelTesting; }
    public int getMaxTestAttempts() { return maxTestAttempts; }
    public UltraQAGoalType getGoalType() { return goalType; }

    // ===== 枚举定义 =====

    /**
     * UltraQA 目标类型。
     */
    public enum UltraQAGoalType {
        TESTS("运行测试"),
        BUILD("构建检查"),
        LINT("代码风格"),
        TYPECHECK("类型检查"),
        CUSTOM("自定义");

        private final String description;
        UltraQAGoalType(String description) { this.description = description; }
        public String getDescription() { return description; }
    }

    /**
     * UltraQA 退出原因。
     */
    public enum UltraQAExitReason {
        GOAL_MET("目标达成"),
        MAX_CYCLES("达到最大循环数"),
        SAME_FAILURE("相同的失败重复出现"),
        ENV_ERROR("环境错误"),
        CANCELLED("被取消");

        private final String description;
        UltraQAExitReason(String description) { this.description = description; }
        public String getDescription() { return description; }
    }

    // ===== Builder =====

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private List<QualityGate> gates = Arrays.asList(QualityGate.build(), QualityGate.test());
        private boolean parallelTesting = false;
        private int maxTestAttempts = 10;
        private UltraQAGoalType goalType = UltraQAGoalType.TESTS;

        public Builder gates(List<QualityGate> gates) { this.gates = gates; return this; }
        public Builder parallelTesting(boolean parallelTesting) { this.parallelTesting = parallelTesting; return this; }
        public Builder maxTestAttempts(int maxTestAttempts) { this.maxTestAttempts = maxTestAttempts; return this; }
        public Builder goalType(UltraQAGoalType goalType) { this.goalType = goalType; return this; }
        public UltraQAStrategy build() { return new UltraQAStrategy(gates, parallelTesting, maxTestAttempts, goalType); }
    }
}
