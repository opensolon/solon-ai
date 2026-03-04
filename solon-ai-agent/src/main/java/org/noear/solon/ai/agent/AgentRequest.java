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
package org.noear.solon.ai.agent;

import org.noear.solon.lang.NonSerializable;
import org.noear.solon.lang.Preview;
import reactor.core.publisher.Flux;

/**
 * 智能体请求构建器
 *
 * <p>
 * 该接口定义了向 {@link Agent} 发起调用的请求契约。它通常作为 Fluent API 的入口，
 * 允许调用者链式配置 Prompt、Session、拦截器以及模型参数。
 * </p>
 *
 * <p>
 * 作为一个“活跃”的构建器，它不仅承载请求参数，还负责触发最终的推理执行过程。
 * </p>
 *
 * @author noear
 * @since 3.9.0
 */
@Preview("3.9.0")
public interface AgentRequest<Req extends AgentRequest<Req,Resp>,Resp extends AgentResponse> extends NonSerializable {
    /**
     * 绑定或切换当前请求的会话上下文
     */
    Req session(AgentSession session);

    /**
     * 同步调用：阻塞等待推理结束并返回完整响应
     */
    Resp call() throws Throwable;

    /**
     * 响应式流输出：实时推送推理过程中的中间结果（如思考、动作、内容片段）
     */
    Flux<AgentChunk> stream();
}