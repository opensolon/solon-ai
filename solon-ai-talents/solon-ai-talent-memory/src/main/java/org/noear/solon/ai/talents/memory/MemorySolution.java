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

/**
 * 记忆方案
 * 组合了存储与搜索能力，构成完整的长期记忆解决闭环。
 *
 * @author noear
 * @since 3.9.7
 */
public interface MemorySolution {
    /**
     * 获取搜索器（负责语义检索与热记忆提取）
     */
    MemorySearcher getSearcher();

    /**
     * 获取存储器（负责物理持久化与 TTL 管理）
     */
    MemoryStorer getStorer();

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