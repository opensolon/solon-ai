/*
 * Copyright 2017-2026 noear.org and authors
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
package org.noear.solon.ai.harness.command;

import org.noear.solon.ai.harness.input.InputOutcome;

/**
 * 默认命令输出工厂（缓冲式）
 * <p>
 * 用于宿主未提供工厂的场景：回执写入内存缓冲，由 {@link InputOutcome#getCommandOutput()} 一次取回。
 * 命令内部声明的 Agent 意图由分发器交给引擎装配，因此未提供工厂的宿主（IM 等）同样能触发任务。
 *
 * <p>工厂无状态：渠道、语言、会话等信息一律由命令从 {@link CommandInput} 读取，输出侧不持有任何输入元数据。</p>
 *
 * @author noear
 * @since 4.2
 */
public class BufferedCommandOutputFactory implements CommandOutputFactory {
    /**
     * 共享实例（无状态）
     */
    public static final BufferedCommandOutputFactory INSTANCE = new BufferedCommandOutputFactory();

    @Override
    public CommandOutput create(CommandInput input) {
        return new BufferedCommandOutput();
    }
}
