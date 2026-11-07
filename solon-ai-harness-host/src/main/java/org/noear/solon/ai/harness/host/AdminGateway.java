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
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.function.BiFunction;

/**
 * 管理视图（控制台 / 后台 API）：对任意已装配的扩展（queue / steer / goal / loop 或宿主自己的扩展）执行管理操作，
 * 统一返回 {@link HarnessResult}。
 *
 * <p>{@link #call} 按同一顺序检查：鉴权（{@link AdminAuthorizer}）→ 扩展是否装配 → 会话归属 → 会话是否存在 → 执行；
 * 成功之后自动续发积压（会话空闲且有排队时立即执行，入队、置顶后不需要再手动 drain）。
 * 扩展抛出的 {@code NotOwnerException} 译为 NOT_OWNER，{@code IllegalArgumentException} /
 * {@code IllegalStateException} 译为 INVALID。</p>
 *
 * <pre>{@code
 * AdminGateway admin = host.admin(tenant, principal);
 *
 * // 任何扩展都是同一种写法：取扩展 → 调它自己的门面 → 给出结果码
 * HarnessResult<List<LoopTask>> tasks = admin.call(sid, "loop.list", LoopExtension.class,
 *         (loop, s) -> HarnessResult.ok(loop.list(sid)));
 *
 * HarnessResult<Integer> pos = admin.call(sid, "queue.enqueue", QueueExtension.class, (queue, s) -> {
 *     return queue.enqueue(s, text, InputOrigin.channel("console"));   // 插件门面直接返回 HarnessResult
 * });
 *
 * admin.interrupt(sid, InterruptMode.INTERRUPT);
 * }</pre>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class AdminGateway {
    private final HarnessHost host;
    private final String tenant;
    private final String principal;

    AdminGateway(HarnessHost host, String tenant, @Nullable String principal) {
        this.host = host;
        this.tenant = tenant;
        this.principal = principal;
    }

    /**
     * 对一个会话执行一次扩展的管理操作。
     *
     * @param sessionId 目标会话（只查不建）
     * @param operation 操作名（交给 {@link AdminAuthorizer} 鉴权，命名由宿主自定，如 {@code "queue.enqueue"}）
     * @param extension 扩展类型（未装配时为 DISABLED）
     * @param action    操作本身：拿到扩展与会话，调用扩展门面并给出结果
     */
    public <E, T> HarnessResult<T> call(String sessionId, String operation, Class<E> extension,
                                       BiFunction<E, AgentSession, HarnessResult<T>> action) {
        if (sessionId == null || sessionId.isEmpty()) {
            return HarnessResult.fail(HarnessResult.Code.INVALID, "sessionId is required");
        }
        if (allowed(sessionId, operation) == false) {
            return forbidden();
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

    /** 中断（见 {@link InterruptMode}；鉴权操作名为 {@code "interrupt"}）。值为「确有运行中的流被停掉」 */
    public HarnessResult<Boolean> interrupt(String sessionId, InterruptMode mode) {
        if (allowed(sessionId, "interrupt") == false) {
            return forbidden();
        }
        return host.interrupt(tenant, sessionId, mode);
    }

    private boolean allowed(String sessionId, String operation) {
        AdminAuthorizer authorizer = host.adminAuthorizer();
        if (authorizer == null) {
            return true;
        }
        try {
            return authorizer.allow(tenant, principal, sessionId, operation);
        } catch (Throwable e) {
            return false; // 授权服务异常时宁可拒绝
        }
    }

    private static <T> HarnessResult<T> forbidden() {
        return HarnessResult.fail(HarnessResult.Code.FORBIDDEN, "forbidden");
    }
}
