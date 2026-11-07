/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.spi.NotOwnerException;
import org.noear.solon.ai.harness.spi.OwnerCheck;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

import java.util.function.BiFunction;

/**
 * 管理视图（控制台 / 后台 API）：对任意已装配的扩展（queue / steer / goal / loop 或宿主自己的扩展）执行管理操作，
 * 统一返回 {@link FacadeResult}。
 *
 * <p>{@link #call} 按同一顺序检查：鉴权（{@link AdminAuthorizer}）→ 扩展是否装配 → 会话归属 → 会话是否存在 → 执行；
 * 成功之后自动续发积压（会话空闲且有排队时立即执行，入队、置顶后不需要再手动 drain）。
 * 扩展抛出的 {@code NotOwnerException} 译为 NOT_OWNER，{@code IllegalArgumentException} /
 * {@code IllegalStateException} 译为 INVALID。</p>
 *
 * <pre>{@code
 * AdminView admin = host.admin(tenant, principal);
 *
 * // 任何扩展都是同一种写法：取扩展 → 调它自己的门面 → 给出结果码
 * FacadeResult<List<LoopTask>> tasks = admin.call(sid, "loop.list", LoopExtension.class,
 *         (loop, s) -> FacadeResult.ok(loop.list(sid)));
 *
 * FacadeResult<Integer> pos = admin.call(sid, "queue.enqueue", QueueExtension.class, (queue, s) -> {
 *     int p = queue.enqueue(s, text, InputOrigin.channel("console"));
 *     return p > 0 ? FacadeResult.ok(p) : FacadeResult.fail(FacadeResult.Code.FULL, "queue full or disabled");
 * });
 *
 * admin.stop(sid, StopMode.ALL);
 * }</pre>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public final class AdminView {
    private final HarnessHost host;
    private final String tenant;
    private final String principal;

    AdminView(HarnessHost host, String tenant, @Nullable String principal) {
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
    public <E, T> FacadeResult<T> call(String sessionId, String operation, Class<E> extension,
                                       BiFunction<E, AgentSession, FacadeResult<T>> action) {
        if (sessionId == null || sessionId.isEmpty()) {
            return FacadeResult.fail(FacadeResult.Code.INVALID, "sessionId is required");
        }
        if (allowed(sessionId, operation) == false) {
            return forbidden();
        }

        HarnessEngine engine = host.engine(tenant);
        E ext = engine.getExtension(extension).orElse(null);
        if (ext == null) {
            return FacadeResult.fail(FacadeResult.Code.DISABLED, extension.getSimpleName() + " is not installed");
        }

        // 先查归属再加载会话：非持有节点不把会话装进内存
        OwnerCheck owner = engine.checkOwner(sessionId);
        if (owner.isOwned() == false) {
            return FacadeResult.notOwner(owner.getOwner());
        }
        AgentSession session = engine.getSessionOrNull(sessionId);
        if (session == null) {
            return FacadeResult.fail(FacadeResult.Code.NOT_FOUND, "session not found");
        }

        FacadeResult<T> r;
        try {
            r = action.apply(ext, session);
        } catch (NotOwnerException e) {
            return FacadeResult.notOwner(e.getOwner());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return FacadeResult.fail(FacadeResult.Code.INVALID, e.getMessage());
        }
        if (r == null) {
            r = FacadeResult.ok(null);
        }
        if (r.isOk()) {
            host.drain(tenant, sessionId);
        }
        return r;
    }

    /** 停止（见 {@link StopMode}；鉴权操作名为 {@code "stop"}）。值为「确有运行中的流被停掉」 */
    public FacadeResult<Boolean> stop(String sessionId, StopMode mode) {
        if (allowed(sessionId, "stop") == false) {
            return forbidden();
        }
        return host.stop(tenant, sessionId, mode);
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

    private static <T> FacadeResult<T> forbidden() {
        return FacadeResult.fail(FacadeResult.Code.FORBIDDEN, "forbidden");
    }
}
