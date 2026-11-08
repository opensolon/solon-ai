/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HostSink} / {@link RunSink} 缺省实现的日志出口与安全调用（包内）。
 *
 * <p>放在这里而不是写成接口字段：接口字段总是 {@code public static final}，会把日志对象暴露成本模块 API。</p>
 *
 * @author noear
 * @since 4.2.0
 */
final class HostSinkSupport {
    static final Logger LOG = LoggerFactory.getLogger(HostSink.class);

    private HostSinkSupport() {
    }

    /** 调用宿主回调：异常只记日志，不影响运行 */
    static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable e) {
            LOG.warn("[HarnessHost] sink callback failed", e);
        }
    }
}
