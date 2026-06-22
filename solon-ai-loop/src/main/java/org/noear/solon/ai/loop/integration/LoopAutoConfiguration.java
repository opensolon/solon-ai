package org.noear.solon.ai.loop.integration;

import org.noear.solon.ai.loop.config.LoopEngineConfig;
import org.noear.solon.ai.loop.engine.LoopEngine;
import org.noear.solon.ai.loop.engine.SimpleLoopEngine;
import org.noear.solon.ai.loop.state.InMemoryStateManager;
import org.noear.solon.ai.loop.state.StateManager;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;

/**
 * Loop 引擎自动配置 —— 提供便利的引擎初始化方法。
 *
 * <p>支持通过代码一行初始化所有组件。</p>
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
     * 使用自定义状态管理器。
     */
    public LoopAutoConfiguration stateManager(StateManager stateManager) {
        this.stateManager = stateManager;
        return this;
    }

    /**
     * 使用磁盘状态管理器。
     * <p>将循环状态持久化到指定目录，支持跨重启恢复。</p>
     *
     * @param rootDirectory 状态持久化根目录
     * @return 当前配置实例（链式调用）
     */
    public LoopAutoConfiguration useDiskState(String rootDirectory) {
        this.stateManager = new DiskStateManager(rootDirectory);
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
     * <p>创建 LoopEngine 并包装为三个集成组件（Agent/Flow/Harness）。
     * 如果配置了磁盘状态管理器，会在集成层中传递。</p>
     *
     * @return 包含所有集成组件的容器
     */
    public IntegratedComponents buildWithIntegrations() {
        LoopEngine engine = build();
        SolonAgentIntegration agentIntegration = new SolonAgentIntegration(engine);
        SolonFlowIntegration flowIntegration = new SolonFlowIntegration(engine);
        SolonHarnessIntegration harnessIntegration = new SolonHarnessIntegration(engine);

        // 如果使用磁盘状态管理器，标记集成层已具备磁盘持久化能力
        if (stateManager instanceof DiskStateManager) {
            // DiskStateManager 已通过 LoopEngineConfig 注入到引擎中
            // 集成层可通过 engine 获取状态管理器，无需额外注入
        }

        return new IntegratedComponents(
                engine,
                agentIntegration,
                flowIntegration,
                harnessIntegration
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
