/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.harness.permission;

/**
 * 权限规则的来源
 *
 * @author noear
 * @since 4.0
 */
public enum RuleSource {
    /** 用户级配置 */
    USER_SETTINGS,
    /** 项目级配置 */
    PROJECT_SETTINGS,
    /** 本地项目配置（不提交到版本控制） */
    LOCAL_SETTINGS,
    /** 命令行标志参数 */
    FLAG_SETTINGS,
    /** 组织策略配置 */
    POLICY_SETTINGS,
    /** CLI 参数 */
    CLI_ARG,
    /** 命令行（运行时） */
    COMMAND,
    /** 当前会话（运行时） */
    SESSION
}
