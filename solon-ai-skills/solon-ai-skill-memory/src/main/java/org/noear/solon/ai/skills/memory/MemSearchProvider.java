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

import java.util.List;

/**
 * 记忆搜索供应商接口
 *
 * @author noear
 * @since 3.9.4
 */
public interface MemSearchProvider {
    /** 语义/模糊搜索 */
    List<MemSearchResult> search(String userId, String query, int limit);
    /** 获取高价值热记忆（用于画像注入） */
    List<MemSearchResult> getHotMemories(String userId, int limit);
    /** 同步索引 */
    void updateIndex(String userId, String key, String fact, int importance, String time);
    /** 移除索引 */
    void removeIndex(String userId, String key);
}