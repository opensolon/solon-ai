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
 * 记忆搜索结果模型
 *
 * @author noear
 * @since 3.9.4
 */
public class MemorySearchResult {
    private String key;
    private String content;
    private int importance;
    private String time; // 记录时间，用于时序冲突判断
    private String scope; // 记忆作用域："workspace" | "user"

    public MemorySearchResult() {
        //用于反序列化
    }

    public MemorySearchResult(String key, String content, int importance, String time, String scope) {
        this.key = key;
        this.content = content;
        this.importance = importance;
        this.time = time;

        if (scope == null) {
            this.scope = "";
        } else {
            this.scope = scope;
        }
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

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setImportance(int importance) {
        this.importance = importance;
    }

    public void setTime(String time) {
        this.time = time;
    }
}