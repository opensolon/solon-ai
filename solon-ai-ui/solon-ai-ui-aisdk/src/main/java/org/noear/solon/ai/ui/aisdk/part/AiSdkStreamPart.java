/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.ui.aisdk.part;

import org.noear.snack4.ONode;

/**
 * Vercel AI SDK UI Message Stream Protocol v1 Part 抽象基类
 * <p>
 * 所有协议 Part 继承此类，通过模板方法 {@link #toJson()} 统一序列化为 JSON。
 * 子类只需实现 {@link #getType()} 和 {@link #writeFields(ONode)}。
 *
 * @author shaoerkuai
 * @see <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol">UI Message Stream Protocol</a>
 * @since 3.9.5
 */
public abstract class AiSdkStreamPart {

    /**
     * 获取 Part 类型标识（如 "text-delta"、"reasoning-start" 等）
     */
    public abstract String getType();

    /**
     * 子类实现：将 Part 字段写入 JSON 节点
     *
     * @param node JSON 节点，已包含 "type" 字段
     */
    protected abstract void writeFields(ONode node);

    /**
     * 序列化为 JSON 字符串（模板方法）
     * <p>
     * 输出格式：{@code {"type":"xxx", ...fields}}
     */
    public String toJson() {
        ONode node = new ONode();
        node.set("type", getType());
        writeFields(node);
        return node.toJson();
    }
}
