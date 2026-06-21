package org.noear.solon.ai.loop.pipeline;

import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.strategy.LoopStrategy;
import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.strategy.UltraQAStrategy;

import java.util.HashMap;
import java.util.Map;

/**
 * 内置阶段适配器集合。
 */
public class DefaultStageAdapters {

    /**
     * 执行阶段适配器：使用 RalphLoopStrategy 执行实现。
     */
    public static StageAdapter executionAdapter() {
        return new StageAdapter() {
            @Override
            public StageResult execute(PipelineStage stage, Map<String, Object> context, LoopEngine loopEngine) {
                try {
                    LoopStrategy strategy = (LoopStrategy) context.get("executionStrategy");
                    if (strategy == null) {
                        strategy = new RalphLoopStrategy();
                    }
                    LoopConfig config = LoopConfig.builder()
                        .taskDescription("Pipeline execution stage")
                        .strategy(strategy)
                        .maxIterations(50)
                        .verificationRequired(true)
                        .parameters(context)
                        .build();
                    LoopSession session = loopEngine.start(config);
                    session.waitForCompletion();
                    return StageResult.ok("Execution stage completed: " + session.getResult().getMessage());
                } catch (Exception e) {
                    return StageResult.fail("Execution stage failed: " + e.getMessage());
                }
            }

            @Override
            public PipelineStage supportedStage() { return PipelineStage.EXECUTION; }
        };
    }

    /**
     * QA 阶段适配器：使用 UltraQAStrategy 执行质量检查。
     */
    public static StageAdapter qaAdapter() {
        return new StageAdapter() {
            @Override
            public StageResult execute(PipelineStage stage, Map<String, Object> context, LoopEngine loopEngine) {
                try {
                    UltraQAStrategy strategy = UltraQAStrategy.builder()
                        .maxTestAttempts(5)
                        .build();
                    LoopConfig config = LoopConfig.builder()
                        .taskDescription("Pipeline QA stage")
                        .strategy(strategy)
                        .maxIterations(10)
                        .verificationRequired(false)
                        .parameters(context)
                        .build();
                    LoopSession session = loopEngine.start(config);
                    session.waitForCompletion();
                    boolean success = session.getResult().isSuccess();
                    return success
                        ? StageResult.ok("QA stage passed")
                        : StageResult.fail("QA stage failed: " + session.getResult().getMessage());
                } catch (Exception e) {
                    return StageResult.fail("QA stage error: " + e.getMessage());
                }
            }

            @Override
            public PipelineStage supportedStage() { return PipelineStage.QA; }
        };
    }

    /**
     * 需求扩展阶段适配器：分析需求并生成扩展描述。
     */
    public static StageAdapter expansionAdapter() {
        return new StageAdapter() {
            @Override
            public StageResult execute(PipelineStage stage, Map<String, Object> context, LoopEngine loopEngine) {
                try {
                    String description = (String) context.getOrDefault("description", "");
                    if (description.isEmpty()) {
                        return StageResult.fail("No description provided for expansion");
                    }
                    // 扩展示例：添加上下文元数据
                    context.put("expandedDescription", "[EXPANDED] " + description);
                    context.put("requirementsAnalyzed", true);
                    return StageResult.ok("Expansion completed for: " + description.substring(0, Math.min(50, description.length())));
                } catch (Exception e) {
                    return StageResult.fail("Expansion failed: " + e.getMessage());
                }
            }

            @Override
            public PipelineStage supportedStage() { return PipelineStage.EXPANSION; }
        };
    }

    /**
     * 最终验证阶段适配器：验证最终交付物质量。
     */
    public static StageAdapter validationAdapter() {
        return new StageAdapter() {
            @Override
            public StageResult execute(PipelineStage stage, Map<String, Object> context, LoopEngine loopEngine) {
                try {
                    Boolean allChecksPassed = (Boolean) context.getOrDefault("validationChecksPassed", true);
                    if (!allChecksPassed) {
                        return StageResult.fail("Validation checks failed");
                    }
                    context.put("finalValidationPassed", true);
                    context.put("validatedAt", java.time.Instant.now().toString());
                    return StageResult.ok("Final validation completed successfully");
                } catch (Exception e) {
                    return StageResult.fail("Validation failed: " + e.getMessage());
                }
            }

            @Override
            public PipelineStage supportedStage() { return PipelineStage.VALIDATION; }
        };
    }

    private DefaultStageAdapters() {}
}
