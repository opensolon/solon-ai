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

    private DefaultStageAdapters() {}
}
