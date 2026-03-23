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
package org.noear.solon.ai.skills.memory;

/**
 * 记忆方案
 * 组合了存储与搜索能力，构成完整的长期记忆解决闭环。
 *
 * @author noear
 * @since 3.9.7
 */
public interface MemorySolution {
    /**
     * 获取搜索供应商（负责语义检索与热记忆提取）
     */
    MemorySearchProvider getSearchProvider();

    /**
     * 获取存储供应商（负责物理持久化与 TTL 管理）
     */
    MemoryStoreProvider getStoreProvider();

    /**
     * 记忆方案工厂
     * 负责根据运行上下文（如工作目录、租户标识等）构建或检索对应的记忆方案。
     *
     * @author noear
     * @since 3.9.7
     */
    static interface Factory {
        /**
         * 根据当前上下文标识获取记忆方案
         *
         * @param __cwd 当前工作区
         * @return 匹配的记忆方案实例
         */
        MemorySolution get(String __cwd);
    }
}