package org.noear.solon.ai.loop.integration;

import org.noear.solon.ai.loop.config.LoopEngineConfig;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.SimpleLoopEngine;
import org.noear.solon.ai.loop.state.InMemoryStateManager;
import org.noear.solon.ai.loop.state.StateManager;

/**
 * Loop 引擎自动配置 —— 提供便利的引擎初始化方法。
 *
 * <p>对标 Spring Boot 的 AutoConfiguration 风格，
 * 支持通过代码一行初始化所有组件。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 快速初始化
 * LoopEngine engine = LoopAutoConfiguration.createDefaultEngine();
 *
 * // 或自定义配置
 * LoopAutoConfiguration configurator = new LoopAutoConfiguration();
 * configurator.useInMemoryState()
 *             .enableMonitoring(true)
 *             .enableDebugging(false);
 * LoopEngine engine = configurator.build();
 * }</pre>
 *
 * @since 4.0.3
 */
public class LoopAutoConfiguration {

    private StateManager stateManager;
    private boolean monitoringEnabled = true;
    private boolean debuggingEnabled = false;
    private int cleanupInterval = 300;
    private long stateExpirationTime = 3600000L;

    public LoopAutoConfiguration() {
        this.stateManager = new InMemoryStateManager();
    }

    /**
     * 使用内存状态管理器。
     */
    public LoopAutoConfiguration useInMemoryState() {
        this.stateManager = new InMemoryStateManager();
        return this;
    }

    /**
     * 设置自定义状态管理器。
     */
    public LoopAutoConfiguration stateManager(StateManager stateManager) {
        this.stateManager = stateManager;
        return this;
    }

    /**
     * 启用/禁用监控。
     */
    public LoopAutoConfiguration enableMonitoring(boolean enabled) {
        this.monitoringEnabled = enabled;
        return this;
    }

    /**
     * 启用/禁用调试。
     */
    public LoopAutoConfiguration enableDebugging(boolean enabled) {
        this.debuggingEnabled = enabled;
        return this;
    }

    /**
     * 设置状态清理间隔（秒）。
     */
    public LoopAutoConfiguration cleanupInterval(int intervalSeconds) {
        this.cleanupInterval = intervalSeconds;
        return this;
    }

    /**
     * 设置状态过期时间（毫秒）。
     */
    public LoopAutoConfiguration stateExpirationTime(long expirationMs) {
        this.stateExpirationTime = expirationMs;
        return this;
    }

    /**
     * 构建 LoopEngine。
     */
    public LoopEngine build() {
        LoopEngineConfig config = LoopEngineConfig.builder()
                .stateManager(stateManager)
                .monitoringEnabled(monitoringEnabled)
                .debuggingEnabled(debuggingEnabled)
                .cleanupInterval(cleanupInterval)
                .stateExpirationTime(stateExpirationTime)
                .build();

        return new SimpleLoopEngine(config);
    }

    /**
     * 构建完整的集成层。
     *
     * @return 包含所有集成组件的容器
     */
    public IntegratedComponents buildWithIntegrations() {
        LoopEngine engine = build();
        return new IntegratedComponents(
                engine,
                new SolonAgentIntegration(engine),
                new SolonFlowIntegration(engine),
                new SolonHarnessIntegration(engine)
        );
    }

    /**
     * 创建默认引擎（一行代码初始化）。
     */
    public static LoopEngine createDefaultEngine() {
        return new LoopAutoConfiguration().build();
    }

    /**
     * 创建带集成的默认配置。
     */
    public static IntegratedComponents createDefault() {
        return new LoopAutoConfiguration().buildWithIntegrations();
    }

    /**
     * 集成组件容器。
     */
    public static class IntegratedComponents {
        public final LoopEngine loopEngine;
        public final SolonAgentIntegration agentIntegration;
        public final SolonFlowIntegration flowIntegration;
        public final SolonHarnessIntegration harnessIntegration;

        public IntegratedComponents(LoopEngine loopEngine,
                                     SolonAgentIntegration agentIntegration,
                                     SolonFlowIntegration flowIntegration,
                                     SolonHarnessIntegration harnessIntegration) {
            this.loopEngine = loopEngine;
            this.agentIntegration = agentIntegration;
            this.flowIntegration = flowIntegration;
            this.harnessIntegration = harnessIntegration;
        }
    }
}
