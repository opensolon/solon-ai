/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.harness.channel;

/**
 * IM 通道统一接口
 *
 * <p>所有 IM 通道（微信、飞书、钉钉等）均实现此接口</p>
 *
 * @author noear
 * @since 3.10.5
 */
public interface Channel {
    /**
     * 通道名称标识
     */
    String getChannelName();

    /**
     * 查询指定会话是否已绑定此通道
     */
    boolean isBound(String sessionId);

    /**
     * 向指定会话绑定的 IM 用户发送回复
     */
    void sendReply(String sessionId, String text, boolean isFinal);
}
