package org.noear.solon.ai.loop.pipeline;

import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.LoopResult;
import org.noear.solon.ai.loop.engine.LoopSession;
import org.noear.solon.ai.loop.strategy.LoopStrategy;
import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.validator.Validator;
import org.noear.solon.ai.loop.validator.ValidationCriteria;
import org.noear.solon.ai.loop.validator.ValidationResult;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段适配器 SPI 接口 —— 插件化的阶段处理器。
 *
 * <p>对标 oh-my-claudecode 的 PipelineStageAdapter 设计，
 * 允许为每个 Pipeline 阶段注入自定义处理逻辑。</p>
 *
 * @since 4.0.3
 */
public interface StageAdapter {

    /**
     * 执行该阶段。
     *
     * @param stage       当前阶段
     * @param context     阶段上下文数据
     * @param loopEngine  循环引擎
     * @return 执行结果
     */
    StageResult execute(PipelineStage stage, Map<String, Object> context, LoopEngine loopEngine);

    /**
     * 获取适配器支持的阶段。
     */
    PipelineStage supportedStage();

    /**
     * 阶段执行结果。
     */
    class StageResult {
        private final boolean success;
        private final String message;
        private final Map<String, Object> data;

        public StageResult(boolean success, String message) {
            this(success, message, new HashMap<>());
        }

        public StageResult(boolean success, String message, Map<String, Object> data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public Map<String, Object> getData() { return data; }

        public static StageResult ok(String message) { return new StageResult(true, message); }
        public static StageResult fail(String message) { return new StageResult(false, message); }
    }
}
