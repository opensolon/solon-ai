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
package org.noear.solon.ai.router;

import java.util.List;

/**
 * 聊天模型路由策略
 *
 * @author bai
 * @since 4.1
 */
public interface RoutingStrategy {
    /**
     * 选择路由
     *
     * @param context 路由上下文
     * @param routes  候选路由
     * @return 路由决策
     */
    RoutingDecision select(RoutingContext context, List<ChatModelRoute> routes);
}
