/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.extension.loop;

import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;
import org.noear.solon.scheduling.simple.JobManager;

import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Loop（会话定时任务）一键装配扩展 —— loop 能力从头到尾只接触这一个类。
 *
 * <p><b>注意</b>：本模块是 harness 的「会话定时任务」（/loop、loop_* 工具），与会话目标（goal 插件）无关。</p>
 *
 * <pre>{@code
 * // 最简：一行装配，缺省执行器经 engine.handle(...) 同步跑一轮
 * engine.addExtensionIfAbsent(LoopExtension.builder().store(engine.getDataSource(), "tasks.json").build());
 *
 * // 完整：
 * LoopExtension loop = engine.addExtensionIfAbsent(LoopExtension.builder()
 *         .store(engine.getDataSource(), "tasks.json")             // 本地/db/http；缺省 = 只在内存
 *         .sessionAlive(id -> Files.isDirectory(sessionsRoot.resolve(id)))   // 孤儿清理；缺省恒 true
 *         .maxErrors(() -> 3)                                      // 任务级连续失败阈值；每次实时读
 *         .triggerWrapper(r -> withLogScope(r))                    // 调度线程包装；缺省原样执行
 *         .build());                                               // onAttach 内注册 /loop 并加载 tasks.json
 * loop.addExecutor("web", id -> id.startsWith("web-"), (sid, prompt) -> LoopOutcome.done(runRound(sid, prompt)));
 *
 * // 宿主的增删改查（请求参数原样传入，null = 未设置/保持）：
 * LoopTask t = loop.schedule(sid, LoopSpec.of(prompt).everyMinutes(interval).cron(cron).runNow(runNow));
 * loop.update(sid, t.getId(), LoopPatch.create().prompt(newPrompt).cron(newCron));
 * loop.pause(sid, t.getId()); loop.resume(sid, t.getId()); loop.remove(sid, t.getId());
 * Map<String, Object> view = t.toMap();
 * }</pre>
 *
 * <p><b>钩子</b>：{@code onAttach} 绑定引擎、注册 /loop、加载并调度 tasks.json；{@code onRequestAssemble}
 * 给顶层请求挂 {@link LoopTalent}（委派不挂：子代理走临时会话，建出来的任务会成为孤儿）；
 * {@code contributeSessionStatus} 贡献 {@link #STATUS_TASKS}；{@code onSessionRemove} 停止并删除该会话全部任务；
 * {@code onDetach} 卸载全部 job 但保留记录。
 * 不实现 {@code onSessionInterrupt}：用户中断不应删除或暂停定时任务。</p>
 *
 * <p><b>开关语义</b>：{@link #setEnabled} 只关模型工具（引擎对停用扩展不回调 {@code onRequestAssemble}）；
 * /loop 命令与已有定时任务照常运行。</p>
 *
 * <p><b>执行器</b>：按注册顺序取首个认领者（谓词命中即认领，结论不穿透）；全部不认领时走缺省执行器
 * （{@code engine.handle(...).call()}，来源 {@code channel("loop")}；可 {@link Builder#defaultExecutor(boolean)} 关闭）；
 * 仍无认领则只记日志跳过本轮。结论语义见 {@link LoopOutcome}：done 记成功、skip 不计错误、
 * failed/抛异常记失败（退避重试，连续达到阈值自动停用）。会话忙由插件在调用执行器之前统一判定
 * （{@code engine.isIdle}），执行器只管「我这个通道此刻能不能跑」（如终端是否空闲）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
public class LoopExtension extends AbsHarnessExtension {

    /**
     * 会话状态事实：本会话的定时任务数（含已暂停）。
     *
     * <p>经 {@code engine.status(session).get(LoopExtension.STATUS_TASKS)} 读取；只供展示/宿主业务，内核不读。</p>
     */
    public static final SessionStatus.Key<Integer> STATUS_TASKS = SessionStatus.Key.count("loop.tasks");

    /** 本扩展实例任务总量缺省上限。 */
    public static final int DEFAULT_MAX_TASKS = 200;
    /** 任务级连续失败缺省阈值。 */
    public static final int DEFAULT_MAX_ERRORS = 3;

    private final LoopScheduler scheduler;
    private final LoopTalent loopTalent;
    private final LoopCommand loopCommand;
    private final boolean defaultExecutorEnabled;

    private LoopExtension(Builder b) {
        IJobManager jm = b.jobManagerSet ? b.jobManager : resolveJobManager();
        if (jm == null && !b.jobManagerSet) {
            // 未显式关闭调度却取不到 JobManager：任务「看起来在、实际不跑」，必须让宿主看见
            org.slf4j.LoggerFactory.getLogger(LoopExtension.class).warn(
                    "[Loop] No JobManager available (is solon-scheduling-simple started?); "
                            + "loop tasks will be stored but never fire. "
                            + "Use builder.jobManager(null) to disable scheduling explicitly.");
        }
        this.scheduler = new LoopScheduler(new LoopStore(b.storeSource, b.storePath), jm,
                b.maxTasks, b.maxErrors, b.sessionAlive, b.triggerWrapper);
        this.loopTalent = new LoopTalent(scheduler);
        this.loopCommand = new LoopCommand(scheduler);
        this.defaultExecutorEnabled = b.defaultExecutor;
    }

    /** 无 Solon 容器（纯 JUnit）时为 null，调度能力随之失效（任务仅作元数据）。 */
    private static IJobManager resolveJobManager() {
        try {
            return JobManager.getInstance();
        } catch (Throwable e) {
            return null;
        }
    }

    /** builder 入口（job 名前缀按实例自动生成，多个实例共用 JobManager 也不会撞名）。 */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== 钩子 ====================

    @Override
    public void onAttach(HarnessEngine engine) {
        scheduler.bindEngine(engine);
        if (defaultExecutorEnabled) {
            scheduler.setDefaultExecutor(defaultExecutor(engine));
        }
        engine.getCommandRegistry().register(loopCommand);
        scheduler.load();
    }

    @Override
    public void onRequestAssemble(HarnessEngine engine, String agentName, boolean isDelegated, ReActOptionsAmend optionsAmend) {
        if (isDelegated == false) {
            optionsAmend.talentAdd(loopTalent);
        }
    }

    /** 事实口径，不判开关；只读内存计数（可能在会话锁内调用，无 IO、无其它锁）。 */
    @Override
    public void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder builder) {
        builder.put(STATUS_TASKS, scheduler.count(session.getSessionId()));
    }

    /** 会话被永久删除：停止并删除该会话全部任务（清理类，不判开关；幂等）。 */
    @Override
    public void onSessionRemove(HarnessEngine engine, String sessionId) {
        scheduler.removeAll(sessionId);
    }

    /** 卸载全部 job（JobManager 是 JVM 单例，不卸则带着已废引擎空转），tasks.json 记录保留。 */
    @Override
    public void onDetach(HarnessEngine engine) {
        scheduler.unloadAll();
    }

    /** 缺省执行器：经唯一输入入口同步取回最终文本（程序生成的 prompt 不解析命令与 @提及）。 */
    private static LoopExecutor defaultExecutor(HarnessEngine engine) {
        return (sessionId, prompt) -> {
            try {
                return LoopOutcome.done(engine.handle(HarnessInput.ofProgram(engine.getSession(sessionId), prompt, "loop"))
                        .call()
                        .getContent());
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                // 失败必须抛给调度器（任务级错误计数 + 退避重试），不得吞掉或记成成功
                throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
            }
        };
    }

    // ==================== 运行期门面 ====================

    /**
     * 新建任务并开始调度。
     *
     * @return 新任务（{@code getId()} 即 taskId）
     * @throws IllegalArgumentException 规格不合法（见 {@link LoopSpec}；含 cron 无法解析或已无未来触发时刻）
     * @throws IllegalStateException    任务总数已达上限（{@link Builder#maxTasks}）
     */
    public LoopTask schedule(String sessionId, LoopSpec spec) {
        return scheduler.schedule(sessionId, spec);
    }

    /** 删除任务。@return false 表示不存在 */
    public boolean remove(String sessionId, String taskId) {
        return scheduler.remove(sessionId, taskId);
    }

    /** 启用/停用切换。@return false 表示不存在 */
    public boolean toggle(String sessionId, String taskId) {
        return scheduler.toggle(sessionId, taskId);
    }

    /** 暂停（保留记录、停止调度；已暂停则不动）。@return false 表示不存在 */
    public boolean pause(String sessionId, String taskId) {
        return scheduler.setEnabled(sessionId, taskId, false);
    }

    /** 恢复调度（清零错误计数；已启用则不动）。@return false 表示不存在 */
    public boolean resume(String sessionId, String taskId) {
        return scheduler.setEnabled(sessionId, taskId, true);
    }

    /**
     * 按补丁修改（null 字段保持原值；启停、迭代、一次性属性随之保留）。
     * 任务正在执行时也可修改：新版本不会与在途那一轮并发，本轮结果记到新版本上。
     *
     * @return 修改后的任务；null 表示不存在
     * @throws IllegalArgumentException 补丁不合法（见 {@link LoopPatch}），此时原任务不变
     */
    public LoopTask update(String sessionId, String taskId, LoopPatch patch) {
        return scheduler.update(sessionId, taskId, patch);
    }

    /** 立即异步执行一轮（不消耗 oneShot 配额）。@return false 表示不存在 */
    public boolean trigger(String sessionId, String taskId) {
        return scheduler.trigger(sessionId, taskId);
    }

    public LoopTask get(String sessionId, String taskId) {
        return scheduler.get(sessionId, taskId);
    }

    /** 本会话全部任务（含已暂停）。 */
    public List<LoopTask> list(String sessionId) {
        return scheduler.list(sessionId);
    }

    /** 全部会话的任务快照（按 sessionId 排序）。 */
    public Map<String, List<LoopTask>> listAll() {
        return scheduler.listAll();
    }

    /** 停止会话全部任务并删除记录（用于 /loop stop-all；删除会话走 {@code engine.removeSession} → onSessionRemove）。 */
    public void removeAll(String sessionId) {
        scheduler.removeAll(sessionId);
    }

    /**
     * 是否存在会自动触发的任务（启用且未取消；已暂停的不算）。
     *
     * <p>宿主闲置回收判定用：只剩暂停任务时引擎可以回收（记录保留，下次挂接时恢复）。</p>
     */
    public boolean hasActiveTasks() {
        return scheduler.hasActiveTasks();
    }

    /**
     * 注册通道化执行器（按注册顺序认领，先于缺省执行器）。
     *
     * @param channel 通道名（日志用，如 "cli" / "web"）
     * @param claim   认领谓词（sessionId → 是否本通道的会话）；null = 认领全部。谓词抛异常按不命中计
     * @param handler 执行器；通道此刻不能跑（如终端被占）返回 {@link LoopOutcome#skip}，失败抛异常即可
     */
    public LoopExtension addExecutor(String channel, Predicate<String> claim, LoopExecutor handler) {
        scheduler.addExecutor(channel, claim, handler);
        return this;
    }

    /** 包内测试口。 */
    LoopScheduler scheduler() {
        return scheduler;
    }

    /** 包内测试口。 */
    LoopCommand command() {
        return loopCommand;
    }

    // ==================== Builder ====================

    public static class Builder {
        private MountSource storeSource;
        private String storePath;
        private Predicate<String> sessionAlive = id -> true;
        private IntSupplier maxErrors = () -> DEFAULT_MAX_ERRORS;
        private int maxTasks = DEFAULT_MAX_TASKS;
        private UnaryOperator<Runnable> triggerWrapper;
        private boolean defaultExecutor = true;
        private IJobManager jobManager;
        private boolean jobManagerSet;

        private Builder() {
        }

        /**
         * 任务存储到挂载来源（本地、db、http 均可）。通常传 {@code engine.getDataSource()}，
         * 本地目录用 {@code FileMountSource.of(dir)}；不设置则只在内存、不持久化。
         *
         * @param source 存储来源（null = 只在内存）
         * @param path   来源内路径（如 {@code "tasks.json"}）；source 非 null 时不可为空
         */
        public Builder store(MountSource source, String path) {
            this.storeSource = source;
            this.storePath = path;
            return this;
        }

        /** 加载时的孤儿判定（返回 false 的会话其任务不再恢复并从文件删除）；缺省恒 true。 */
        public Builder sessionAlive(Predicate<String> predicate) {
            if (predicate != null) {
                this.sessionAlive = predicate;
            }
            return this;
        }

        /** 任务级连续失败阈值（每次实时读取，支持热更新）。 */
        public Builder maxErrors(IntSupplier supplier) {
            if (supplier != null) {
                this.maxErrors = supplier;
            }
            return this;
        }

        /** 本扩展实例任务总量上限（跨全部会话）。 */
        public Builder maxTasks(int max) {
            this.maxTasks = Math.max(1, max);
            return this;
        }

        /** 触发线程包装（如给日志打归属标记）；缺省原样执行。 */
        public Builder triggerWrapper(UnaryOperator<Runnable> wrapper) {
            this.triggerWrapper = wrapper;
            return this;
        }

        /** 是否启用缺省执行器（engine.handle 同步回合）；缺省 true。 */
        public Builder defaultExecutor(boolean enabled) {
            this.defaultExecutor = enabled;
            return this;
        }

        /** 自备调度器；传 null 关闭调度（任务仅作元数据，测试友好）。缺省 {@code JobManager.getInstance()}。 */
        public Builder jobManager(IJobManager jobManager) {
            this.jobManager = jobManager;
            this.jobManagerSet = true;
            return this;
        }

        public LoopExtension build() {
            return new LoopExtension(this);
        }
    }
}
