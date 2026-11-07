/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.ai.harness.HarnessResult;
import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 管理门面的统一鉴权钩子（{@link HarnessHost.Builder#adminAuthorizer}）。
 *
 * <p>插件门面本身不检查作用域；配置本钩子后，{@link AdminGateway} 的每次调用在执行前都先问它，
 * 返回 false 即得到 {@link HarnessResult.Code#FORBIDDEN}。未配置时不做鉴权（由控制器自行负责）。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
@FunctionalInterface
public interface AdminAuthorizer {
    /**
     * @param tenant    引擎键
     * @param principal 调用者（{@code host.admin(tenant, principal)} 传入；可空）
     * @param sessionId 目标会话
     * @param operation 操作名（{@link AdminGateway#call} 传入的名字，由宿主自定；{@link AdminGateway#stop} 固定为 {@code stop}）
     */
    boolean allow(String tenant, @Nullable String principal, String sessionId, String operation);
}
