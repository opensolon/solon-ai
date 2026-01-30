package org.noear.solon.ai.skills.system;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.skill.AbsSkill;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 系统时钟技能：为 AI 提供当前的真实日期、时间和星期
 *
 * @author noear
 */
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