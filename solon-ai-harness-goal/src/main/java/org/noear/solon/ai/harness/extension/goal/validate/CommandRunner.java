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
package org.noear.solon.ai.harness.extension.goal.validate;

import org.noear.solon.ai.talents.cli.CommandResult;

/**
 * 验证器的命令执行口
 *
 * <p>内置实现走引擎 TerminalTalent 的执行链（OS 沙盒、超时整树终止、输出上限），
 * 不允许验证器自己 {@code ProcessBuilder} 绕过沙盒。超时由执行口持有。</p>
 *
 * @author noear
 * @since 4.2.0
 */
@FunctionalInterface
public interface CommandRunner {
    /**
     * @param command 命令
     * @param workDir 执行目录（本轮会话工作目录）
     */
    CommandResult run(String command, String workDir);
}
