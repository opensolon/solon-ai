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
package org.noear.solon.ai.harness.command;

/**
 * 命令类型枚举
 *
 * @author noear
 * @since 2026.4.28
 */
public enum CommandType {
    /**
     * 系统级：直接操作 CLI 状态，如 /exit, /clear
     */
    SYSTEM,
    /**
     * 配置级：查询或修改运行配置，如 /model
     */
    CONFIG,
    /**
     * Agent 级：触发 Agent 执行，如 /resume, /compact
     */
    AGENT
}
