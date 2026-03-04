/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.skills.text2sql;

/**
 * 数据库结构同步模式
 *
 * @author noear
 * @since 3.9.1
 */
public enum SchemaMode {
    /**
     * 全量模式：将所有表结构（DDL）预加载并输出到系统提示词 (Instruction) 中。
     * 适用于表数量较少（如 < 20张）的情况，AI 响应更快，无需额外工具调用。
     */
    FULL,

    /**
     * 动态模式：初始仅输出表名列表，具体的列信息和结构在需要时通过 AI 工具（Tool）动态获取。
     * 适用于大型数据库（表数量多、字段多），能够有效节省初始 Token 消耗，防止上下文溢出。
     */
    DYNAMIC
}