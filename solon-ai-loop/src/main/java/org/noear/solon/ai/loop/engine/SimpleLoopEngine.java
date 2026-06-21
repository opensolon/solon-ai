package org.noear.solon.ai.loop.engine;

import org.noear.solon.ai.loop.config.LoopConfig;
import org.noear.solon.ai.loop.config.LoopEngineConfig;
import org.noear.solon.ai.loop.prd.PRDFileManager;
import org.noear.solon.ai.loop.prd.PRDStatusCalculator;
import org.noear.solon.ai.loop.progress.ProgressManager;
import org.noear.solon.ai.loop.state.*;
import org.noear.solon.ai.loop.state.disk.DiskStateManager;
import org.noear.solon.ai.loop.strategy.LoopContext;
import org.noear.solon.ai.loop.strategy.LoopStrategy;
import org.noear.solon.ai.loop.strategy.RalphLoopStrategy;
import org.noear.solon.ai.loop.validator.ValidationCriteria;
import org.noear.solon.ai.loop.validator.ValidationResult;
import org.noear.solon.ai.loop.validator.Validator;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单循环引擎实现（增强版）
 * 支持 PRD 组件注入、磁盘状态持久化和策略互斥。
 *
 * @author noear
 * @since 4.0.3
 */
public class SimpleLoopEngine implements LoopEngine {

    private final Map<String, SimpleLoopSession> sessions = new ConcurrentHashMap<>();
    private final StateManager stateManager;
    private final boolean monitoringEnabled;
    private final boolean debuggingEnabled;

    // 增强组件
    private DiskStateManager diskStateManager;
    private MutualExclusionGuard mutualExclusionGuard;
    private String projectRootDirectory;

    public SimpleLoopEngine() {
        this(new LoopEngineConfig());
    }

    public SimpleLoopEngine(LoopEngineConfig config) {
        this.stateManager = config.getStateManager();
        this.monitoringEnabled = config.isMonitoringEnabled();
        this.debuggingEnabled = config.isDebuggingEnabled();

        if (stateManager != null) {
            this.diskStateManager = new DiskStateManager(System.getProperty("user.dir", "."));
            this.mutualExclusionGuard = new MutualExclusionGuard(diskStateManager);
        }
    }

    /**
     * 设置项目根目录（用于磁盘状态持久化）。
     */
    public void setProjectRootDirectory(String dir) {
        this.projectRootDirectory = dir;
        this.diskStateManager = new DiskStateManager(dir);
        this.mutualExclusionGuard = new MutualExclusionGuard(diskStateManager);
    }

    @Override
    public LoopSession start(LoopConfig config) {
        String sessionId = UUID.randomUUID().toString();

        // 创建循环上下文
        Map<String, Object> contextData = new HashMap<>();
        contextData.put("taskDescription", config.getTaskDescription());
        contextData.put("verificationRequired", config.isVerificationRequired());
        contextData.put("sessionId", sessionId);

        if (config.getParameters() != null) {
            contextData.putAll(config.getParameters());
        }

        LoopContext context = new LoopContext(
            sessionId,
            config.getTaskDescription(),
            LoopState.IDLE,
            0,
            config.getMaxIterations(),
            Instant.now(),
            config.getParameters(),
            new ArrayList<>(),
            contextData
        );

        // 如果是 RalphLoopStrategy，注入 PRD 组件
        LoopStrategy strategy = config.getStrategy();
        if (strategy instanceof RalphLoopStrategy && diskStateManager != null) {
            PRDFileManager prdFileManager = new PRDFileManager(diskStateManager);
            PRDStatusCalculator statusCalculator = new PRDStatusCalculator();
            ProgressManager progressManager = new ProgressManager(diskStateManager);

            ((RalphLoopStrategy) strategy).injectPrdComponents(
                    prdFileManager, statusCalculator, progressManager, diskStateManager
            );
        }

        // 创建会话
        SimpleLoopSession session = new SimpleLoopSession(
            sessionId,
            strategy,
            config.getValidator(),
            context
        );

        sessions.put(sessionId, session);

        // 同步设置初始状态为 PLANNING，确保 start() 返回后 isRunning() 返回 true
        session.state = LoopState.PLANNING;
        session.running = true;

        // 保存状态
        if (config.isStatePersistenceEnabled()) {
            saveState(session);
        }

        // 启动执行线程
        startExecutionThread(session);

        return session;
    }

    @Override
    public void pause(String sessionId) {
        SimpleLoopSession session = sessions.get(sessionId);
        if (session != null) {
            session.pause();
        }
    }

    @Override
    public void resume(String sessionId) {
        SimpleLoopSession session = sessions.get(sessionId);
        if (session != null) {
            session.resume();
        }
    }

    @Override
    public void stop(String sessionId) {
        SimpleLoopSession session = sessions.get(sessionId);
        if (session != null) {
            session.stop();
        }
    }

    @Override
    public LoopState getState(String sessionId) {
        SimpleLoopSession session = sessions.get(sessionId);
        return session != null ? session.getState() : null;
    }

    @Override
    public LoopSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public boolean isRunning(String sessionId) {
        SimpleLoopSession session = sessions.get(sessionId);
        return session != null && session.getState().isActive();
    }

    @Override
    public List<LoopSession> getActiveSessions() {
        return sessions.values().stream()
                .filter(session -> session.getState().isActive())
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 获取互斥守卫。
     */
    public MutualExclusionGuard getMutualExclusionGuard() {
        return mutualExclusionGuard;
    }

    /**
     * 获取磁盘状态管理器。
     */
    public DiskStateManager getDiskStateManager() {
        return diskStateManager;
    }

    private void startExecutionThread(SimpleLoopSession session) {
        Thread thread = new Thread(() -> {
            try {
                session.execute();
            } catch (Exception e) {
                if (debuggingEnabled) {
                    e.printStackTrace();
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void saveState(SimpleLoopSession session) {
        LoopStateData stateData = new LoopStateData(
            session.getId(),
            session.getState(),
            session.getIterationCount(),
            (int) (session.getSuccessRate() * session.getIterationCount()),
            session.getStartTime(),
            Instant.now(),
            new HashMap<>(session.getContext().getContextData()),
            new HashMap<>()
        );

        stateManager.saveState(session.getId(), stateData);
    }

    // ===== 会话实现 =====

    private static class SimpleLoopSession implements LoopSession {

        private final String id;
        private final LoopStrategy strategy;
        private final Validator validator;
        private final LoopContext context;
        private volatile LoopState state = LoopState.IDLE;
        private volatile boolean running = false;
        private volatile boolean paused = false;
        private final List<IterationResult> iterationHistory = new ArrayList<>();
        private final List<ValidationResult> validationResults = new ArrayList<>();
        private final List<java.util.function.Consumer<LoopState>> stateListeners = new ArrayList<>();
        private final List<java.util.function.Consumer<IterationResult>> iterationListeners = new ArrayList<>();
        private final List<java.util.function.Consumer<ValidationResult>> validationListeners = new ArrayList<>();
        private volatile LoopResult result = null;
        private final Instant startTime;
        private volatile Instant endTime = null;

        SimpleLoopSession(String id, LoopStrategy strategy, Validator validator, LoopContext context) {
            this.id = id;
            this.strategy = strategy;
            this.validator = validator;
            this.context = context;
            this.startTime = Instant.now();
        }

        @Override
        public String getId() { return id; }

        @Override
        public LoopState getState() { return state; }

        @Override
        public LoopContext getContext() { return context; }

        @Override
        public boolean shouldContinue() {
            return strategy.shouldContinue(context);
        }

        @Override
        public IterationResult executeIteration() {
            return strategy.executeIteration(context);
        }

        @Override
        public void waitForCompletion() {
            while (state.isActive()) {
                try { Thread.sleep(100); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        @Override
        public void waitForCompletion(java.time.Duration timeout) {
            Instant deadline = Instant.now().plus(timeout);
            while (state.isActive() && Instant.now().isBefore(deadline)) {
                try { Thread.sleep(100); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        @Override
        public LoopResult getResult() { return result; }

        @Override
        public List<IterationResult> getIterationHistory() {
            return Collections.unmodifiableList(iterationHistory);
        }

        @Override
        public List<ValidationResult> getValidationResults() {
            return Collections.unmodifiableList(validationResults);
        }

        @Override
        public int getIterationCount() { return iterationHistory.size(); }

        @Override
        public java.time.Duration getExecutionTime() {
            Instant end = endTime != null ? endTime : Instant.now();
            return java.time.Duration.between(startTime, end);
        }

        @Override
        public double getSuccessRate() {
            if (iterationHistory.isEmpty()) return 0.0;
            long successCount = iterationHistory.stream()
                    .filter(IterationResult::isSuccess).count();
            return (double) successCount / iterationHistory.size();
        }

        @Override
        public Instant getStartTime() { return startTime; }

        @Override
        public Instant getEndTime() { return endTime; }

        @Override
        public void onStateChange(java.util.function.Consumer<LoopState> listener) {
            stateListeners.add(listener);
        }

        @Override
        public void onIterationComplete(java.util.function.Consumer<IterationResult> listener) {
            iterationListeners.add(listener);
        }

        @Override
        public void onValidationResult(java.util.function.Consumer<ValidationResult> listener) {
            validationListeners.add(listener);
        }

        @Override
        public void pause() {
            if (state.isActive()) {
                state = LoopState.PAUSED;
                paused = true;
                notifyStateChange(state);
            }
        }

        @Override
        public void resume() {
            if (state == LoopState.PAUSED) {
                state = LoopState.EXECUTING;
                paused = false;
                notifyStateChange(state);
            }
        }

        @Override
        public void stop() {
            if (state.isActive() || state == LoopState.PAUSED) {
                state = LoopState.FAILED;
                running = false;
                endTime = Instant.now();
                notifyStateChange(state);
            }
        }

        @Override
        public void enterFixLoop() {
            if (state == LoopState.VERIFYING) {
                state = LoopState.FIXING;
                notifyStateChange(state);
            }
        }

        @Override
        public void updateState(ValidationResult validation) {
            validationResults.add(validation);
            notifyValidationResult(validation);

            if (validation.isPassed()) {
                state = shouldContinue() ? LoopState.EXECUTING : LoopState.COMPLETED;
                if (state == LoopState.COMPLETED) endTime = Instant.now();
            } else if (validation.needsFix()) {
                state = LoopState.FIXING;
            } else {
                state = LoopState.FAILED;
                endTime = Instant.now();
            }
            notifyStateChange(state);
        }

        private void execute() {
            running = true;
            state = LoopState.PLANNING;
            notifyStateChange(state);

            while (running && shouldContinue() && !paused) {
                try {
                    IterationResult iterationResult = executeIteration();
                    iterationHistory.add(iterationResult);
                    notifyIterationComplete(iterationResult);

                    if (!iterationResult.isSuccess()) {
                        state = LoopState.FAILED;
                        endTime = Instant.now();
                        break;
                    }

                    // 验证
                    state = LoopState.VERIFYING;
                    notifyStateChange(state);

                    ValidationCriteria criteria = ValidationCriteria.simple(
                        "iteration-validation",
                        Collections.singletonList(context.getTaskDescription())
                    );

                    ValidationResult validationResult = validator != null
                            ? validator.validate(iterationResult.getResult(), criteria)
                            : ValidationResult.passed("No validator configured");

                    updateState(validationResult);

                    if (state == LoopState.COMPLETED || state == LoopState.FAILED) {
                        break;
                    }
                } catch (Exception e) {
                    state = LoopState.FAILED;
                    endTime = Instant.now();
                    break;
                }
            }

            // 如果循环从未执行（shouldContinue 立即返回 false），标记为已完成
            if (state == LoopState.PLANNING) {
                state = LoopState.COMPLETED;
                endTime = Instant.now();
                notifyStateChange(state);
            }

            result = new LoopResult(
                id, state, null,
                state == LoopState.COMPLETED,
                state == LoopState.COMPLETED ? "Loop completed successfully" : "Loop failed",
                getExecutionTime(),
                iterationHistory.size(),
                (int) (getSuccessRate() * iterationHistory.size()),
                getSuccessRate(),
                startTime, endTime,
                iterationHistory, new HashMap<>()
            );
        }

        private void notifyStateChange(LoopState newState) {
            for (java.util.function.Consumer<LoopState> listener : stateListeners) {
                try { listener.accept(newState); } catch (Exception ignored) {}
            }
        }

        private void notifyIterationComplete(IterationResult result) {
            for (java.util.function.Consumer<IterationResult> listener : iterationListeners) {
                try { listener.accept(result); } catch (Exception ignored) {}
            }
        }

        private void notifyValidationResult(ValidationResult result) {
            for (java.util.function.Consumer<ValidationResult> listener : validationListeners) {
                try { listener.accept(result); } catch (Exception ignored) {}
            }
        }
    }
}
