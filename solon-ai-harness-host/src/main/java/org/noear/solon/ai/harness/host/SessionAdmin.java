/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.InterruptMode;
import org.noear.solon.ai.harness.session.NotOwnerException;
import org.noear.solon.ai.harness.session.OwnerCheck;
import org.noear.solon.lang.Preview;

import java.util.function.BiFunction;

/**
 * 会话管理态（控制台 / 后台 API）：对任意已装配的扩展（queue / steer / goal / loop 或宿主自己的扩展）
 * 执行管理操作，统一返回 {@link HarnessResult}。
 *
 * <p><b>本类不做鉴权</b>：鉴权需要调用者身份、角色、租户成员关系、限流等上下文，框架一样拿不到。
 * 请在控制器 / 拦截器层完成鉴权，再调用本类。本类只保证框架级不变量，按同一顺序检查：
 * 扩展是否装配（DISABLED）→ 会话归属（NOT_OWNER）→ 会话是否存在（NOT_FOUND）→ 执行 →
 * 成功之后自动续发积压（会话空闲且有排队时立即执行，入队、置顶后不需要再手动 drain）。</p>
 *
 * <p>扩展抛出的 {@code NotOwnerException} 译为 NOT_OWNER，{@code IllegalArgumentException} 译为 INVALID，
 * {@code IllegalStateException} 译为 CONFLICT。</p>
 *
 * <pre>{@code
 * SessionAdmin admin = host.admin(tenant);
 *
 * // 任何扩展都是同一种写法：取扩展 → 调它自己的门面 → 给出结果码
 * HarnessResult<List<LoopTask>> tasks = admin.call(sid, LoopExtension.class,
 *         (loop, s) -> HarnessResult.ok(loop.list(sid)));
 *
 * HarnessResult<Integer> pos = admin.call(sid, QueueExtension.class, (queue, s) -> {
 *     return queue.enqueue(s, text, InputOrigin.channel("console"));   // 插件门面直接返回 HarnessResult
 * });
 *
 * admin.interrupt(sid, InterruptMode.INTERRUPT_ALL);
 * }</pre>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class SessionAdmin {
    private final HarnessHost host;
    private final String tenant;

    SessionAdmin(HarnessHost host, String tenant) {
        this.host = host;
        this.tenant = tenant;
    }

    /**
     * 对一个会话执行一次扩展的管理操作。
     *
     * @param sessionId 目标会话（只查不建）
     * @param extension 扩展类型（未装配时为 DISABLED）
     * @param action    操作本身：拿到扩展与会话，调用扩展门面并给出结果
     */
    public <E, T> HarnessResult<T> call(String sessionId, Class<E> extension,
                                       BiFunction<E, AgentSession, HarnessResult<T>> action) {
        if (sessionId == null || sessionId.isEmpty()) {
            return HarnessResult.fail(HarnessResult.Code.INVALID, "sessionId is required");
        }

        HarnessEngine engine = host.engine(tenant);
        E ext = engine.getExtension(extension).orElse(null);
        if (ext == null) {
            return HarnessResult.fail(HarnessResult.Code.DISABLED, extension.getSimpleName() + " is not installed");
        }

        // 先查归属再加载会话：非持有节点不把会话装进内存
        OwnerCheck owner = engine.checkOwner(sessionId);
        if (owner.isOwned() == false) {
            return HarnessResult.notOwner(owner.getOwner());
        }
        AgentSession session = engine.getSessionOrNull(sessionId);
        if (session == null) {
            return HarnessResult.fail(HarnessResult.Code.NOT_FOUND, "session not found");
        }

        HarnessResult<T> r;
        try {
            r = action.apply(ext, session);
        } catch (NotOwnerException e) {
            return HarnessResult.notOwner(e.getOwner());
        } catch (IllegalArgumentException e) {
            return HarnessResult.invalid(e.getMessage());
        } catch (IllegalStateException e) {
            return HarnessResult.conflict(e.getMessage());
        }
        if (r == null) {
            r = HarnessResult.ok(null);
        }
        if (r.isOk()) {
            host.drain(tenant, sessionId);
        }
        return r;
    }

    /**
     * 中断会话的运行（见 {@link InterruptMode}：用户点「停止」用 {@code INTERRUPT_ALL}，
     * 只停当前运行、保留排队用 {@code CANCEL_STREAM}）。
     *
     * @return OK（值为「确有运行中的流被停掉」）、NOT_FOUND、NOT_OWNER
     */
    public HarnessResult<Boolean> interrupt(String sessionId, InterruptMode mode) {
        return host.interrupt(tenant, sessionId, mode);
    }
}
