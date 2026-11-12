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

import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActOptionsAmend;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.harness.session.OwnerCheck;
import org.noear.solon.ai.harness.input.HarnessInput;
import org.noear.solon.ai.harness.input.InputOutcome;
import org.noear.solon.ai.harness.session.SessionStatus;
import org.noear.solon.lang.Nullable;
import org.noear.solon.scheduling.scheduled.manager.IJobManager;
import org.noear.solon.scheduling.simple.JobManager;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * Loop（会话定时任务）一键装配扩展 —— loop 能力从头到尾只接触这一个类。
 *
 * <p><b>注意</b>：本模块是 harness 的「会话定时任务」（/loop、loop_* 工具），与会话目标（goal 插件）无关。</p>
 *
 * <pre>{@code
 * // 最简：构建期一行装配（存到引擎状态来源的 loop/ 目录），缺省执行器经 engine.handle(...) 同步跑一轮
 * HarnessEngine.of(ws, ".solon/") ... .extensionAdd(LoopExtension.builder().store("loop").build()).build();
 *
 * // 完整：
 * LoopExtension loop = engine.addExtensionIfAbsent(LoopExtension.builder()
 *         .store(engine.getStateSource(), "loop", "tasks.json")     // 按会话分存，并迁移旧单文件；缺省 = 只在内存
 *         .quota(id -> 20)                                         // 每会话（租户）上限；缺省不限
 *         .sessionAlive(id -> Files.isDirectory(sessionsRoot.resolve(id)))   // 孤儿清理；缺省恒 true
 *         .maxErrors(() -> 3)                                      // 任务级连续失败阈值；每次实时读
 *         .triggerWrapper(r -> withLogScope(r))                    // 调度线程包装；缺省原样执行
 *         .build());                                               // onAttach 内注册 /loop 并加载任务
 * loop.addExecutor("web", id -> id.startsWith("web-"), (sid, prompt, locale) -> LoopOutcome.done(runRound(sid, prompt, locale)));
 *
 * // 宿主的增删改查（请求参数原样传入，null = 未设置/保持）：
 * LoopTask t = loop.schedule(sid, LoopSpec.of(prompt).everyMinutes(interval).cron(cron).runNow(runNow).locale(uiLocale)).getValue();
 * loop.update(sid, t.getId(), LoopPatch.create().prompt(newPrompt).cron(newCron));   // 写操作都返回 HarnessResult
 * loop.pause(sid, t.getId()); loop.resume(sid, t.getId()); loop.remove(sid, t.getId());
 * Map<String, Object> view = t.toMap();
 * }</pre>
 *
 * <p><b>钩子</b>：{@code onAttach} 绑定引擎、注册 /loop、加载并调度已存任务；{@code onRequestAssemble}
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
 * <p><b>语言</b>：任务记下创建时的语言（{@link LoopSpec#locale}；{@code /loop} 取命令上下文的语言，{@code loop_add} 取
 * 工具参数 {@code __locale}），每一轮把它（没有时用引擎实例的语言）交给执行器；缺省执行器写进本轮输入。</p>
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
    /** {@code store(dir)} 形式：挂接时在引擎状态来源的这个目录里存（可空） */
    private final String engineStateDir;

    private LoopExtension(Builder b) {
        IJobManager jm = b.jobManagerSet ? b.jobManager : resolveJobManager();
        if (jm == null && !b.jobManagerSet) {
            // 未显式关闭调度却取不到 JobManager：任务「看起来在、实际不跑」，必须让宿主看见
            LoggerFactory.getLogger(LoopExtension.class).warn(
                    "[Loop] No JobManager available (is solon-scheduling-simple started?); "
                            + "loop tasks will be stored but never fire. "
                            + "Use builder.jobManager(null) to disable scheduling explicitly.");
        }
        LoopStore store = new LoopStore(b.storeSource, b.storeDir, b.legacyFile);
        this.scheduler = new LoopScheduler(store, jm,
                b.maxTasks, b.quota, b.maxErrors, b.sessionAlive, b.triggerWrapper);
        this.loopTalent = new LoopTalent(scheduler);
        this.loopCommand = new LoopCommand(scheduler);
        this.defaultExecutorEnabled = b.defaultExecutor;
        this.engineStateDir = b.engineStateDir;
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

    private volatile HarnessEngine engine;

    // ==================== 钩子 ====================

    @Override
    public void onAttach(HarnessEngine engine) {
        this.engine = engine;
        scheduler.bindEngine(engine);
        if (engineStateDir != null && engine.getStateSource() != null) {
            scheduler.useStore(new LoopStore(engine.getStateSource(), engineStateDir, null));
        }
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

    @Override
    public Collection<String> getToolNames() {
        return loopTalent.getToolMap().keySet();
    }

    /** 事实口径，不判开关；只读内存计数（可能在会话锁内调用，无 IO、无其它锁）。 */
    @Override
    public void contributeSessionStatus(HarnessEngine engine, AgentSession session, SessionStatus.Builder builder) {
        builder.put(STATUS_TASKS, scheduler.count(session.getSessionId()));
    }

    /** 会话被永久删除：停止并删除该会话全部任务（清理类，不判开关；幂等）。非持有节点不动别人的任务。 */
    @Override
    public void onSessionRemove(HarnessEngine engine, String sessionId) {
        if (checkOwner(sessionId) == null) {
            scheduler.removeAll(sessionId);
        }
    }

    /**
     * 写门面的归属检查：多节点部署时非持有节点返回 NOT_OWNER（宿主按持有者转发）；本节点持有时返回 null。
     * 未挂载时（无引擎）不检查。
     */
    private <T> HarnessResult<T> checkOwner(String sessionId) {
        HarnessEngine e = engine;
        if (e == null || sessionId == null) {
            return null;
        }
        OwnerCheck check = e.checkOwner(sessionId);
        return check.isOwned() ? null : HarnessResult.<T>notOwner(check.getOwner());
    }

    private static HarnessResult<Void> found(boolean found) {
        return found ? HarnessResult.<Void>ok() : HarnessResult.<Void>notFound("loop task not found");
    }

    /** 引擎池回收判定：存在会自动触发的任务时不静默（只剩暂停任务时可以回收）。 */
    @Override
    public boolean isQuiescent(HarnessEngine engine) {
        return hasActiveTasks() == false;
    }

    /** 卸载全部 job（JobManager 是 JVM 单例，不卸则带着已废引擎空转），存储记录保留。 */
    @Override
    public void onDetach(HarnessEngine engine) {
        scheduler.unloadAll();
    }

    /**
     * 缺省执行器：经唯一输入入口同步取回最终文本（程序发起的 prompt 不解析命令与 @提及）。
     * 本轮的语言写进输入：协议指令、工具文案与工具参数 {@code __locale} 随之一致。
     */
    private static LoopExecutor defaultExecutor(HarnessEngine engine) {
        return (sessionId, prompt, locale) -> {
            // 只取已存在的会话：定时轮次不得把已删除的会话重新建出来
            AgentSession session = engine.getSessionOrNull(sessionId);
            if (session == null) {
                return LoopOutcome.skip("session not found");
            }
            InputOutcome outcome = engine.handle(HarnessInput.ofProgram(session, prompt, "loop").locale(locale));
            if (outcome.getKind() != InputOutcome.Kind.TASK) {
                // 忙 / 待审批（REJECTED）等未受理结局：本轮跳过，不计错误
                return LoopOutcome.skip(outcome.getRejectReason() != null
                        ? outcome.getRejectReason().code() : "input not admitted: " + outcome.getKind());
            }
            try {
                return LoopOutcome.done(outcome.call().getContent());
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable e) {
                // 失败必须抛给调度器（任务级错误计数 + 退避重试），不得吞掉或记成成功
                throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
            }
        };
    }

    // ==================== 运行期门面 ====================
    //
    // 写操作统一返回 HarnessResult（NOT_OWNER / NOT_FOUND / INVALID / FULL），不抛业务异常。

    /**
     * 新建任务并开始调度。
     *
     * <p>结果说明按规格的语言输出（{@link LoopSpec#locale}，未设置时用引擎实例的语言）。</p>
     *
     * @return OK 时值为新任务（{@code getId()} 即 taskId）；INVALID（规格不合法，含 cron 无法解析或已无未来触发时刻）/
     *         FULL（任务总数或本会话配额已达上限）/ NOT_OWNER
     */
    public HarnessResult<LoopTask> schedule(String sessionId, LoopSpec spec) {
        HarnessResult<LoopTask> denied = checkOwner(sessionId);
        if (denied != null) {
            return denied;
        }
        Locale locale = spec != null && spec.getLocale() != null ? spec.getLocale() : engineLocale();
        try {
            return HarnessResult.ok(scheduler.schedule(sessionId, spec));
        } catch (IllegalArgumentException e) {
            return HarnessResult.invalid(LoopArgumentException.messageOf(e, locale));
        } catch (IllegalStateException e) {
            return HarnessResult.full(LoopArgumentException.messageOf(e, locale));
        }
    }

    /** 删除任务。NOT_FOUND = 不存在 */
    public HarnessResult<Void> remove(String sessionId, String taskId) {
        HarnessResult<Void> denied = checkOwner(sessionId);
        return denied != null ? denied : found(scheduler.remove(sessionId, taskId));
    }

    /** 暂停（保留记录、停止调度；已暂停则不动）。NOT_FOUND = 不存在 */
    public HarnessResult<Void> pause(String sessionId, String taskId) {
        HarnessResult<Void> denied = checkOwner(sessionId);
        return denied != null ? denied : found(scheduler.setEnabled(sessionId, taskId, false));
    }

    /** 恢复调度（清零错误计数；已启用则不动）。NOT_FOUND = 不存在 */
    public HarnessResult<Void> resume(String sessionId, String taskId) {
        HarnessResult<Void> denied = checkOwner(sessionId);
        return denied != null ? denied : found(scheduler.setEnabled(sessionId, taskId, true));
    }

    /**
     * 按补丁修改（null 字段保持原值；启停、迭代与一次性属性随之保留）。
     * 任务正在执行时也可修改：新版本不会与在途那一轮并发，本轮结果记到新版本上。
     * 结果说明按任务的语言输出（没有时用引擎实例的语言）。
     *
     * <p>语言：补丁没带语言时保持任务现有的语言；带了则改为指定语言。</p>
     *
     * @return OK 时值为修改后的任务；NOT_FOUND（不存在）/ INVALID（补丁不合法，见 {@link LoopPatch}，此时原任务不变）
     */
    public HarnessResult<LoopTask> update(String sessionId, String taskId, LoopPatch patch) {
        HarnessResult<LoopTask> denied = checkOwner(sessionId);
        if (denied != null) {
            return denied;
        }
        try {
            LoopTask task = scheduler.update(sessionId, taskId, patch);
            return task == null ? HarnessResult.<LoopTask>notFound("loop task not found") : HarnessResult.ok(task);
        } catch (IllegalArgumentException e) {
            // 说明按「补丁指定的语言 > 现有任务的语言 > 引擎的语言」输出：用户刚改的语言就是他要看的
            Locale msgLocale;
            if (patch != null && patch.getLocale() != null) {
                msgLocale = patch.getLocale();
            } else {
                LoopTask current = scheduler.get(sessionId, taskId);
                msgLocale = current != null ? scheduler.localeOf(current) : engineLocale();
            }
            return HarnessResult.invalid(LoopArgumentException.messageOf(e, msgLocale));
        }
    }

    /** 门面 API 没有输入：规格与任务都没有语言时，说明文字用引擎实例的默认语言（未挂接或未配置为 null，即全局默认语言）。 */
    private @Nullable Locale engineLocale() {
        HarnessEngine e = engine;
        return e == null ? null : e.getLocale();
    }

    /** 立即异步执行一轮（不消耗 oneShot 配额）。NOT_FOUND = 不存在 */
    public HarnessResult<Void> trigger(String sessionId, String taskId) {
        HarnessResult<Void> denied = checkOwner(sessionId);
        return denied != null ? denied : found(scheduler.trigger(sessionId, taskId));
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

    /** 停止会话全部任务并删除记录（删除会话走 {@code engine.removeSession} → onSessionRemove）。 */
    public HarnessResult<Void> removeAll(String sessionId) {
        HarnessResult<Void> denied = checkOwner(sessionId);
        if (denied != null) {
            return denied;
        }
        scheduler.removeAll(sessionId);
        return HarnessResult.ok();
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
        private String storeDir;
        private String legacyFile;
        private String engineStateDir;
        private ToIntFunction<String> quota = id -> 0;
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
         * 任务存储到挂载来源（本地、db、http 均可），按会话分存：每个会话一个文件
         * {@code <dir>/<encode(sessionId)>.json}，变更只重写该会话的文件。不设置则只在内存、不持久化。
         *
         * @param source 存储来源（null = 只在内存）；通常传 {@code engine.getStateSource()}
         * @param dir    来源内目录（建议 {@code "loop"}：在引擎缺省受保护名单内，模型工具读写不到；
         *               用其它目录名时需加进 {@code protectedStatePaths}）
         */
        public Builder store(MountSource source, String dir) {
            return store(source, dir, null);
        }

        /**
         * 同 {@link #store(MountSource, String)}，并在首次加载时迁移旧单文件：把其中的任务拆成分存文件，
         * 旧文件改名为 {@code <legacyFile>.migrated-<时间戳>} 备份（外来条目随备份完整保留）。
         *
         * @param legacyFile 旧单文件（如 {@code "tasks.json"}；可空 = 不迁移）
         */
        public Builder store(MountSource source, String dir, String legacyFile) {
            this.storeSource = source;
            this.storeDir = dir;
            this.legacyFile = legacyFile;
            this.engineStateDir = null;
            return this;
        }

        /**
         * 任务存到引擎的状态来源（{@code engine.getStateSource()}）里的 {@code dir} 目录，挂接时才取来源——
         * 构建期装配（{@code Builder.extensionAdd}）时不必先拿到引擎。其余同 {@link #store(MountSource, String)}。
         *
         * @param dir 状态来源内目录（建议 {@code "loop"}）
         * @since 4.2.0
         */
        public Builder store(String dir) {
            if (dir == null || dir.trim().isEmpty()) {
                throw new IllegalArgumentException("Loop store dir must not be empty");
            }
            this.storeSource = null;
            this.storeDir = null;
            this.legacyFile = null;
            this.engineStateDir = dir;
            return this;
        }

        /**
         * 按会话（租户）配额：sessionId → 上限（≤0 = 不限；固定每会话上限写 {@code id -> 20}）；
         * 异常按不限处理（总量上限 {@link #maxTasks} 仍然生效）。缺省不限。
         *
         * @since 4.2.0
         */
        public Builder quota(ToIntFunction<String> sessionIdToLimit) {
            this.quota = sessionIdToLimit == null ? id -> 0 : sessionIdToLimit;
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
