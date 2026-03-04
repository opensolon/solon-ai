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
 * 记忆搜索结果模型
 *
 * @author noear
 * @since 3.9.4
 */
public class MemSearchResult {
    private String key;
    private String content;
    private int importance;
    private String time; // 新增：记录时间，用于时序冲突判断

    public MemSearchResult() {
        //用于反序列化
    }

    public MemSearchResult(String key, String content, int importance, String time) {
        this.key = key;
        this.content = content;
        this.importance = importance;
        this.time = time;
    }

    public String getKey() {
        return key;
    }

    public String getContent() {
        return content;
    }

    public int getImportance() {
        return importance;
    }

    public String getTime() {
        return time;
    }
}