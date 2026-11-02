/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.queue;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.flow.FlowContext;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.HarnessExtension;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.agent.react.ReActAgent;

import java.util.List;
import java.util.Map;

/**
 * Queue 一键装配扩展 —— queue 能力从头到尾只接触这一个类。
 *
 * <p>{@code engine.addExtension(new QueueExtension())} 一步完成：注册 /queue 命令
 * （幂等）+ 提供队列操作门面。宿主不再分别接触 {@link SessionQueue} 与
 * {@link SessionQueueDrainer} 两个静态工具类——本类内部中转，静态实现与
 * {@code session.queue.*} attrs 契约零改动。</p>
 *
 * <p>装配后宿主只需掌握两个时机（这是队列的宿主职责，扩展不代管）：</p>
 * <ul>
 *   <li>任务终态：{@code drainOne(...)} 续发队首（同步运行者回调返回即确认；
 *       异步运行者传 {@code acknowledgeOnReturn=false}，终态再 {@code ack(...)）}；</li>
 *   <li>启动恢复：先 {@code hasItems/hasPersistedItems} 探测，再 drain。</li>
 * </ul>
 *
 * @author noear
 * @since 3.9.4
 */
public class QueueExtension extends AbsHarnessExtension {

    private final QueueCommand queueCommand = new QueueCommand();

    @Override
    public void initialize(HarnessEngine engine) {
        engine.getCommandRegistry().register(queueCommand);
    }

    /** configure 无事可做（命令无代理级装配需求）；接口要求必须实现。 */
    @Override
    public void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
        // no-op
    }

    @Override
    public void setEnabled(Boolean enabled) {
        super.setEnabled(enabled);
        queueCommand.setEnabled(enabled);
    }

    /**
     * 开关语义（与命令层一致）：
     * <ul>
     *   <li>受控：{@link #enqueue} 与 {@link #drainOne} —— 停用后不再接单、不再续发；</li>
     *   <li>不受控：ack / cancelItem / promote / cancelPending 等清理类接口 —— 开关不得阻止清理，
     *       否则停用前已入队的项将永远无法取消。</li>
     * </ul>
     */

    // ==================== 操作门面（中转静态实现） ====================

    /** 入队（携带来源身份）。@return 队列位置（从 1 计）；-1 表示失败（满/超长/参数空/扩展已停用） */
    public int enqueue(AgentSession session, String text, InputOrigin origin) {
        if (isEnabled() == false) {
            return -1;
        }
        return SessionQueue.enqueue(session, text, origin);
    }

    /**
     * 入队（来源缺省为 {@code channel("queue")}）。
     *
     * <p>程序宿主调用时通常不关心来源元数据，不必为一个必填参数编造渠道标识
     * （与 {@code /queue} 命令的内部兜底同口径）。
     *
     * @return 队列位置（从 1 计）；-1 表示失败
     */
    public int enqueue(AgentSession session, String text) {
        return enqueue(session, text, InputOrigin.channel("queue"));
    }

    /** 完整参数入队（HTTP 重试幂等用 id 去重；model/effort 等随任务执行）。已停用时返回 -1。 */
    public int enqueue(AgentSession session, String text, InputOrigin origin,
                       String id, String model, String reasoningEffort,
                       String thinkingMode, String selectedAgent, boolean hasFiles) {
        if (isEnabled() == false) {
            return -1;
        }
        return SessionQueue.enqueue(session, text, origin, id, model, reasoningEffort,
                thinkingMode, selectedAgent, hasFiles);
    }

    /** 待执行数量（不含 in-flight，运行中的那条不占容量）。 */
    public int pendingSize(AgentSession session) {
        return SessionQueue.pendingSize(session);
    }

    /** 总数（含 in-flight）。 */
    public int size(AgentSession session) {
        return SessionQueue.size(session);
    }

    /** 是否有任务（含待执行与运行中）。 */
    public boolean hasItems(AgentSession session) {
        return SessionQueue.hasItems(session);
    }

    /** 快照中是否存在队列投影（不为探测而实例化会话）。 */
    public boolean hasPersistedItems(FlowContext context) {
        return SessionQueue.hasPersistedItems(context);
    }

    /** 统一视图：运行中任务（若有）在首位，其余待执行。 */
    public List<SessionQueueItem> view(AgentSession session) {
        return SessionQueue.view(session);
    }

    /**
     * 领取并执行队首一条（同步语义：回调返回即确认）。
     *
     * @return true = 已领取并执行；false = 无任务或领取失败
     * @see SessionQueueDrainer#drainOne(AgentSession, SessionQueueDrainer.TaskRunner)
     */
    public boolean drainOne(AgentSession session, SessionQueueDrainer.TaskRunner runner) {
        if (isEnabled() == false) {
            return false;
        }
        return SessionQueueDrainer.drainOne(session, runner);
    }

    /**
     * 领取并执行队首一条（异步语义：回调只负责启动，终态再 {@link #ack}）。
     *
     * @see SessionQueueDrainer#drainOne(AgentSession, SessionQueueDrainer.TaskRunner, boolean)
     */
    public boolean drainOne(AgentSession session, SessionQueueDrainer.TaskRunner runner,
                            boolean acknowledgeOnReturn) {
        if (isEnabled() == false) {
            return false;
        }
        return SessionQueueDrainer.drainOne(session, runner, acknowledgeOnReturn);
    }

    /** 异步终态确认：必须带正确的 item id（校验 in-flight 身份，不匹配返回 false）。 */
    public boolean ack(AgentSession session, String itemId) {
        return SessionQueue.acknowledge(session, itemId);
    }

    /** 取消单条待执行任务。 */
    public boolean cancelItem(AgentSession session, String itemId) {
        return SessionQueue.cancelItem(session, itemId);
    }

    /** 置顶单条待执行任务。 */
    public boolean promote(AgentSession session, String itemId) {
        return SessionQueue.promote(session, itemId);
    }

    /** 清空待执行并递增代际（in-flight 不受影响；旧代际 requeue 被拒）。 */
    public boolean cancelPending(AgentSession session) {
        return SessionQueue.cancelPending(session);
    }

    /** 旧版 queue-tasks.json 的一次性迁移入口。@return 实际导入条数 */
    public int importItems(AgentSession session, List<Map<String, Object>> rows) {
        return SessionQueue.importItems(session, rows);
    }
}
