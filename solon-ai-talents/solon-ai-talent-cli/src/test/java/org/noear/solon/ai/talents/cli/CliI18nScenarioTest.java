package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.AiI18n;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;
import org.noear.solon.ai.mount.MountManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 没有请求上下文的入口（旧签名、描述、异常消息）跟随默认语言；请求语言优先于默认语言
 */
public class CliI18nScenarioTest {
    @AfterEach
    public void restore() {
        AiI18n.setDefaultLocale(Locale.CHINESE);
    }

    @Test
    public void testDefaultLocaleEn() throws Exception {
        AiI18n.setDefaultLocale(Locale.ENGLISH);
        Path work = Files.createTempDirectory("cli-i18n-scenario");
        try {
            TerminalTalent terminal = new TerminalTalent(new MountManager(work.toString()));
            terminal.setBashAsyncEnabled(true);
            SkillTalent skill = new SkillTalent(new CliI18nFixtures.FakeSkills(2));

            GoldenFiles.verify("terminal_description_en", terminal.description(null));
            GoldenFiles.verify("terminal_tools", GoldenFiles.dumpTools(terminal.getToolAry()));
            GoldenFiles.verify("skill_description_en", skill.description(null));
            GoldenFiles.verify("clock_tools", GoldenFiles.dumpTools(new ClockTalent().getToolAry()));

            //未注入 __locale 时按默认语言（旧签名）
            GoldenFiles.verify("terminal_validate_empty_en", terminal.validateCommand(""));
            GoldenFiles.verify("terminal_read_missing_en", terminal.read("nope.txt", null, null, null));
            GoldenFiles.verify("skill_search_none_en", skill.skillsearch("none"));

            //向上抛出的权限异常：未绑定语言时用默认语言
            SecurityException e = Assertions.assertThrows(SecurityException.class,
                    () -> terminal.read("/etc/hosts", null, null, null));
            GoldenFiles.verify("terminal_read_absolute_en", "! " + e.getMessage());
        } finally {
            Files.deleteIfExists(work);
        }
    }

    /**
     * 请求语言优先：默认中文时，按英文请求抛出的权限异常消息为英文
     */
    @Test
    public void testExceptionFollowsRequestLocale() throws Exception {
        Path work = Files.createTempDirectory("cli-i18n-scenario");
        try {
            TerminalTalent terminal = new TerminalTalent(new MountManager(work.toString()));
            SecurityException en = Assertions.assertThrows(SecurityException.class,
                    () -> terminal.read("/etc/hosts", null, null, null, "en"));
            GoldenFiles.verify("terminal_read_absolute_en", "! " + en.getMessage());

            SecurityException zh = Assertions.assertThrows(SecurityException.class,
                    () -> terminal.read("/etc/hosts", null, null, null, null));
            GoldenFiles.verify("terminal_read_absolute_zh", "! " + zh.getMessage());
        } finally {
            Files.deleteIfExists(work);
        }
    }

    /**
     * 时钟：星期按请求语言输出；未指定时与改造前一致（中文）
     */
    @Test
    public void testClockWeekday() {
        ClockTalent clock = new ClockTalent();
        Assertions.assertTrue(clock.now().contains("星期"), clock.now());
        Assertions.assertTrue(clock.now("zh-CN").contains("星期"));
        String en = clock.now("en");
        Assertions.assertTrue(en.matches(".*(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday).*"), en);
    }
}
