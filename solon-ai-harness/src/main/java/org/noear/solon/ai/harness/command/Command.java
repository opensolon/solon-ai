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

import org.noear.solon.lang.Nullable;

/**
 * 通用命令接口（适用于 CLI、Web 等所有端）
 *
 * @author noear
 */
public interface Command {
    /**
     * 命令名（不含 / 前缀）
     */
    String name();

    /**
     * 命令描述
     */
    String description();

    /**
     * 示例
     * */
    String[] examples();

    /**
     * 使用模型
     */
    default String model(){
        return null;
    }

    /**
     * 是否仅 CLI 环境可用
     * <p>
     * 返回 true 时，Web 端将不展示也不执行该命令
     */
    default boolean cliOnly() {
        return false;
    }

    /**
     * 是否允许在会话正在执行任务时运行。
     * <p>
     * 默认不允许。中断、插话、排队等只修改当前运行态的命令可以覆写为 true；
     * 会启动新任务或修改会话结构的命令应保持默认值，避免忙碌会话发生重入。
     */
    default boolean runnableWhenBusy() {
        return false;
    }

    /**
     * 执行命令
     *
     * @return true 表示已处理该输入（不再触发 Agent 任务）
     */
    void execute(CommandContext ctx) throws Exception;
}