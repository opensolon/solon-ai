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
package org.noear.solon.ai.talents.memory;

import java.util.Arrays;
import java.util.List;

/**
 * 记忆方案提供者
 *
 * 负责根据运行上下文（如工作目录、租户标识等）构建或检索对应的记忆方案。
 *
 * @author noear
 * @since 4.0.0
 */
public interface MemorySolutionProvider {
    String SCOPE_USER = "user";
    String SHARED_USER_ID = "shared";

    /**
     * 根据当前上下文标识获取记忆方案
     *
     * <p>返回的方案可以是单域方案，也可以是内部聚合了多个作用域的复合方案
     * （由实现自行封装读合并、写路由）。调用方只面向这一个方案，无需感知作用域细节。
     *
     * @param __cwd 当前工作区
     * @return 匹配的记忆方案实例
     */
    MemorySolution get(String __cwd);

    default String getScopesDefault(){
        return "workspace";
    }

    default String getScopesDescription(){
        return "存储作用域: workspace(工作区,默认) 或 user(用户全局)。跨项目的通用认知用 user 域。";
    }

    /**
     * 根据重要度计算 TTL（秒），统一 TTL 策略避免消费端与框架端重复硬编码。
     *
     * <ul>
     *   <li>importance >= 10: 永久 (-1)</li>
     *   <li>importance >= 5: 30 天 (2592000)</li>
     *   <li>else: 7 天 (604800)</li>
     * </ul>
     */
     default int computeTtl(int importance) {
        if (importance >= 10) return -1;
        if (importance >= 5) return 2592000;
        return 604800;
    }
}