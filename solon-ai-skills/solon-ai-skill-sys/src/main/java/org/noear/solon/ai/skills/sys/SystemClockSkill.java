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
package org.noear.solon.ai.skills.sys;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.AbsSkill;
import org.noear.solon.lang.Preview;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 系统时钟技能：为 AI 代理赋予精准的“时间维度感知”能力。
 *
 * @author noear
 * @since 3.9.1
 */
@Preview("3.9.1")
public class SystemClockSkill extends AbsSkill {

    @Override
    public String name() {
        return "system_clock";
    }

    @ToolMapping(name = "get_current_time", description = "获取系统当前的日期、精确时间、星期及本地时区（例如：2026-01-30 21:00:00 Friday CST）")
    public String getCurrentTime() {
        ZonedDateTime now = ZonedDateTime.now();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE z", Locale.CHINESE);
        return now.format(formatter);
    }
}