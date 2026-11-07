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
package org.noear.solon.ai.harness.session;

import org.noear.solon.lang.Nullable;
import org.noear.solon.lang.Preview;

/**
 * 本节点不持有会话（多节点部署时写门面在非持有节点上被调用）
 *
 * <p>返回值本身能表达失败的门面（如 {@code SteerResult.Code.NOT_OWNER}、队列的 {@code NOT_OWNER}）不抛本异常；
 * 返回快照或布尔的写门面（goal / loop 的 create / update / pause ...）抛本异常，宿主按 {@link #getOwner()} 转发。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@Preview("4.2.0")
public class NotOwnerException extends IllegalStateException {
    private final String sessionId;
    private final String owner;

    public NotOwnerException(String sessionId, @Nullable String owner) {
        super("Session is owned by another node: " + sessionId + (owner == null ? "" : " (owner=" + owner + ")"));
        this.sessionId = sessionId;
        this.owner = owner;
    }

    public String getSessionId() {
        return sessionId;
    }

    /** 持有者节点标识（未知为 null） */
    @Nullable
    public String getOwner() {
        return owner;
    }
}
