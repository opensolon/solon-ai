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

/**
 * 命令输出工厂（由宿主实现）
 * <p>
 * 命令的分发与执行由 harness 统一处理，但「回执怎么落地」属于端特性，
 * 因此 {@link CommandOutput} 的构造交由宿主决定。未提供时使用缓冲式默认实现
 * {@link BufferedCommandOutputFactory}。
 *
 * @author noear
 * @since 4.2
 */
@FunctionalInterface
public interface CommandOutputFactory {
    /**
     * 创建命令输出
     *
     * @param input 本次命令的只读输入（需要按渠道区分输出时读取，多数实现可忽略）
     */
    CommandOutput create(CommandInput input);
}
