/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.session.steer;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.harness.AbsHarnessExtension;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.harness.command.builtin.SteerCommand;
import org.noear.solon.ai.harness.input.InputOrigin;
import org.noear.solon.ai.harness.session.steer.SteerStatus;

import java.util.Queue;
import java.util.function.Supplier;

/**
 * Steer 一键装配扩展 —— steer 能力从头到尾只接触这一个类。
 *
 * <p>{@code engine.addExtension(new SteerExtension<>(() -> outputPort))} 一步完成：
 * 注册 /steer 命令（幂等）+ 把 {@link SteerInterceptor} 挂为主代理<b>默认拦截器</b>。
 * 宿主不再需要每请求 {@code new SteerInterceptor(...) + interceptorAdd(...)}。</p>
 *
 * <p><b>挂载顺序安全</b>：拦截器按 {@code LinkedHashMap} 插入序 + RankEntity 稳定排序，
 * AgentFactory 先挂默认拦截器（压缩/StopLoop）、后轮到 extension 的 configure，
 * 因此本扩展挂载的 steer 拦截器天然排在上下文压缩之后——与请求级挂载语义一致，
 * 注入是向压缩产物尾部追加，不破坏消息序列。同类型按 Class 去重，重复挂载无双份。</p>
 *
 * <p>{@code outputPort} 用 {@code Supplier} 惰性解析：默认工作区可能在入口绑定之前创建，
 * 构造期拿不到端口（与 soloncode 宿主既有做法一致）。传 {@code null} 供应商时
 * drop 只记 warn 日志、不通知客户端（纯 headless 场景）。</p>
 *
 * <p>插话投递（Web API / IM 命令共用）与取消直接在本类上中转。</p>
 *
 * @author noear
 * @since 3.9.4
 */
public class SteerExtension<C> extends AbsHarnessExtension {

    private final Supplier<SteerOutputPort<C>> outputPortSupplier;
    private final SteerInterceptor<C> interceptor;

    private final SteerCommand steerCommand = new SteerCommand();

    /** @param outputPortSupplier 输出端口惰性供应商（每次事件回调时取值；可为 null） */
    public SteerExtension(Supplier<SteerOutputPort<C>> outputPortSupplier) {
        this(outputPortSupplier, null);
    }

    /**
     * @param outputPortSupplier 输出端口惰性供应商（可为 null = headless）
     * @param wsContext          不透明路由令牌（可为 null）
     */
    public SteerExtension(Supplier<SteerOutputPort<C>> outputPortSupplier, C wsContext) {
        this.outputPortSupplier = outputPortSupplier;
        // 需要动态解析端口：包装为转发端口，拦截器只持有一份（wsContext 一次定值）
        this.interceptor = new SteerInterceptor<>(
                outputPortSupplier == null ? null : new ForwardingPort<>(outputPortSupplier),
                wsContext);
    }

    @Override
    public void initialize(HarnessEngine engine) {
        engine.getCommandRegistry().register(steerCommand);
    }

    /** 挂载到主代理（子代理不挂：插话面向用户任务，子代理上下文无前端邮箱语义）。 */
    @Override
    public void configure(HarnessEngine engine, String agentName, ReActAgent.Builder agentBuilder) {
        if (AgentDefinition.AGENT_MAIN.equals(agentName)) {
            agentBuilder.defaultInterceptorAdd(interceptor);
        }
    }

    @Override
    public void setEnabled(Boolean enabled) {
        super.setEnabled(enabled);
        steerCommand.setEnabled(enabled);
        interceptor.setEnabled(enabled);
    }

    /**
     * 开关语义（与命令层一致）：{@link #steer} 在停用时返回
     * {@link SteerStatus#DISABLED}；{@link #cancel} 等清理类接口不受控（不得阻止取消）。
     */

    // ==================== 操作门面（中转静态实现） ====================

    /** 组装好的拦截器（挂载已由扩展完成；仅供确需请求级再挂/细粒度访问的宿主）。 */
    public SteerInterceptor<C> getInterceptor() {
        return interceptor;
    }

    /**
     * 插话入队（唯一实现的中转，与 Web /web/chat/steer、/steer 命令同源）。
     *
     * @see SteerInterceptor#steer(AgentSession, String, String, String, InputOrigin, java.util.function.BooleanSupplier)
     */
    public SteerResult steer(AgentSession session, String runId, String steerId,
                                              String text, InputOrigin origin,
                                              java.util.function.BooleanSupplier busyRecheck) {
        if (isEnabled() == false) {
            // 停用后仍投递会一直积压在邮箱（消费方拦截器已停用），必须当场拒绝。
            return new SteerResult(SteerStatus.DISABLED, steerId, 0);
        }
        return SteerInterceptor.steer(session, runId, steerId, text, origin, busyRecheck);
    }

    /**
     * 向当前活跃任务插话（省略 runId / steerId / busyRecheck 三个高级参数）。
     *
     * <p>「投给现在这条任务」是最常见语义：runId、steerId 均传 null 表示不比对轮次、
     * 由框架生成 id；busyRecheck 供宿主做二次忙判定，绝大多数调用方不需要。</p>
     *
     * <p>来源缺省为 {@link InputOrigin#NONE}；需保留渠道身份时用三参重载。
     * 结果仍用 {@code isSteered/isBoxFull/isNotRunning()} 判断，本方法不做降级。</p>
     */
    public SteerResult steer(AgentSession session, String text) {
        return steer(session, text, InputOrigin.NONE);
    }

    /** 向当前活跃任务插话，携带来源身份（runId / steerId / busyRecheck 省略）。 */
    public SteerResult steer(AgentSession session, String text, InputOrigin origin) {
        return steer(session, null, null, text, origin == null ? InputOrigin.NONE : origin, null);
    }

    /** 取消仍留在邮箱中的插话（消费线程已 poll 出则返回 false，不得宣称取消成功）。 */
    public boolean cancel(AgentSession session, String steerId) {
        Queue<SteerMessage> box = SteerInterceptor.steerBox(session);
        return SteerInterceptor.cancel(box, steerId);
    }

    /** 每次事件回调时从供应商取最新端口并转发（端口实例可随工作区重建而更换）。 */
    private static final class ForwardingPort<C> implements SteerOutputPort<C> {
        private final Supplier<SteerOutputPort<C>> supplier;

        ForwardingPort(Supplier<SteerOutputPort<C>> supplier) {
            this.supplier = supplier;
        }

        private SteerOutputPort<C> delegate() {
            return supplier.get();
        }

        @Override
        public void emitSteerApplied(C wsContext, AgentSession session, String runId,
                                     java.util.List<SteerMessage> items) {
            SteerOutputPort<C> port = delegate();
            if (port != null) {
                port.emitSteerApplied(wsContext, session, runId, items);
            }
        }

        @Override
        public void handleDroppedSteers(C wsContext, AgentSession session,
                                        Queue<SteerMessage> box, String runId) {
            SteerOutputPort<C> port = delegate();
            if (port != null) {
                port.handleDroppedSteers(wsContext, session, box, runId);
            }
        }
    }
}
