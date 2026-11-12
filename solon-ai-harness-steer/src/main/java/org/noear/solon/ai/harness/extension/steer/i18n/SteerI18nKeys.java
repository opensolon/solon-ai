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
package org.noear.solon.ai.harness.extension.steer.i18n;

import org.noear.solon.lang.Preview;

/**
 * steer 插件的文案 key（常量名 = key 转大写，"." 换成 "_"）
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public final class SteerI18nKeys {
    private SteerI18nKeys() {
    }

    /** 注入正文前缀（不含结尾空格，由代码补；识别时匹配所有已知语言） */
    public static final String STEER_PREFIX = "steer.prefix";

    public static final String COMMAND_STEER_DESCRIPTION = "command.steer.description";
    public static final String COMMAND_STEER_EXAMPLE_1 = "command.steer.example_1";
    public static final String COMMAND_STEER_EXAMPLE_2 = "command.steer.example_2";
    public static final String COMMAND_STEER_USAGE = "command.steer.usage";
    public static final String COMMAND_STEER_APPLIED = "command.steer.applied";
    public static final String COMMAND_STEER_DISABLED = "command.steer.disabled";
    public static final String COMMAND_STEER_BOX_FULL = "command.steer.box_full";
    public static final String COMMAND_STEER_NOT_RUNNING = "command.steer.not_running";
    public static final String COMMAND_STEER_TOO_LONG = "command.steer.too_long";
    public static final String COMMAND_STEER_FAILED = "command.steer.failed";
}
