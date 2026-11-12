package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.i18n.testkit.GoldenFiles;

/**
 * cli 才能（Terminal / Skill / Clock）对模型可见文本的渲染快照（场景见 {@link CliI18nRender}）
 *
 * <p>中文快照由改造前的代码生成，国际化后必须逐字不变；英文快照经人工审校后生成。
 * 按场景分组拆成多个用例：覆盖率变异时只重跑读取过该 key 的分组。ZhExplicit 校验请求语言为中文时与默认一致。</p>
 */
public class CliRenderGoldenTest {
    @Test
    public void testSkillZh() throws Exception {
        GoldenFiles.verify(CliI18nRender.render(null, "_zh", "skill"));
    }

    @Test
    public void testSkillZhExplicit() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("zh-CN", "_zh", "skill"));
    }

    @Test
    public void testSkillEn() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("en", "_en", "skill"));
    }

    @Test
    public void testClockZh() throws Exception {
        GoldenFiles.verify(CliI18nRender.render(null, "_zh", "clock"));
    }

    @Test
    public void testClockZhExplicit() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("zh-CN", "_zh", "clock"));
    }

    @Test
    public void testClockEn() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("en", "_en", "clock"));
    }

    @Test
    public void testInstructionZh() throws Exception {
        GoldenFiles.verify(CliI18nRender.render(null, "_zh", "instruction"));
    }

    @Test
    public void testInstructionZhExplicit() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("zh-CN", "_zh", "instruction"));
    }

    @Test
    public void testInstructionEn() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("en", "_en", "instruction"));
    }

    @Test
    public void testCommandZh() throws Exception {
        GoldenFiles.verify(CliI18nRender.render(null, "_zh", "command"));
    }

    @Test
    public void testCommandZhExplicit() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("zh-CN", "_zh", "command"));
    }

    @Test
    public void testCommandEn() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("en", "_en", "command"));
    }

    @Test
    public void testExecutorZh() throws Exception {
        GoldenFiles.verify(CliI18nRender.render(null, "_zh", "executor"));
    }

    @Test
    public void testExecutorZhExplicit() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("zh-CN", "_zh", "executor"));
    }

    @Test
    public void testExecutorEn() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("en", "_en", "executor"));
    }

    @Test
    public void testFileZh() throws Exception {
        GoldenFiles.verify(CliI18nRender.render(null, "_zh", "file"));
    }

    @Test
    public void testFileZhExplicit() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("zh-CN", "_zh", "file"));
    }

    @Test
    public void testFileEn() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("en", "_en", "file"));
    }

    @Test
    public void testVirtualZh() throws Exception {
        GoldenFiles.verify(CliI18nRender.render(null, "_zh", "virtual"));
    }

    @Test
    public void testVirtualZhExplicit() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("zh-CN", "_zh", "virtual"));
    }

    @Test
    public void testVirtualEn() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("en", "_en", "virtual"));
    }

    @Test
    public void testSupportZh() throws Exception {
        GoldenFiles.verify(CliI18nRender.render(null, "_zh", "support"));
    }

    @Test
    public void testSupportZhExplicit() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("zh-CN", "_zh", "support"));
    }

    @Test
    public void testSupportEn() throws Exception {
        GoldenFiles.verify(CliI18nRender.render("en", "_en", "support"));
    }
}
