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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 系统时钟技能：为 AI 代理赋予精准的“时间维度感知”能力。
 *
 * <p>核心职责：
 * <ul>
 * <li><b>消除时间幻觉</b>：LLM 无法获知当前真实时间，该工具作为权威的时间源，是处理一切时效性任务的基础。</li>
 * <li><b>业务逻辑对齐</b>：提供包含星期（Day of Week）的完整时间戳，便于 AI 处理排班、节假日判断及日程规划。</li>
 * <li><b>本地化支持</b>：默认输出符合中文语境的时间格式，确保 Agent 输出的自然语言描述符合用户习惯。</li>
 * </ul>
 * </p>
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

    @Override
    public String description() {
        return "时钟助手：提供当前的日期、精确时间和星期，解决时间感知问题。";
    }

    @ToolMapping(name = "get_current_time", description = "获取系统当前的日期、精确时间和星期（例如：2026-01-30 21:00:00 Friday）")
    public String getCurrentTime() {
        LocalDateTime now = LocalDateTime.now();
        // 格式化：年-月-日 时:分:秒 星期
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINESE);
        return now.format(formatter);
    }
}