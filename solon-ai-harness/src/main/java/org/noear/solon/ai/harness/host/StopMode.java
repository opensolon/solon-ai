/*
 * Copyright 2017-2026 noear.org and authors
 * Licensed under the Apache License, Version 2.0.
 */
package org.noear.solon.ai.harness.host;

import org.noear.solon.lang.Preview;

/**
 * 停止方式（{@link HarnessHost#stop} / {@link AdminView#stop}）。
 *
 * <p>引擎有三个停流入口，语义差别容易混淆（{@code interrupt} 会连带清空队列）。宿主层只暴露两种按意图命名的方式：</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public enum StopMode {
    /**
     * 用户点「停止」：先清空排队任务，再停当前运行流，并在历史里补写取消消息（= {@code engine.interrupt}）。
     */
    ALL,
    /**
     * 只停当前运行流，保留排队任务（跑完后续发下一条），不写历史（= {@code engine.cancelStream}）。
     */
    CURRENT_RUN
}
