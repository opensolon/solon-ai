package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.cli.impl.DefaultSkillCatalog;
import org.noear.solon.ai.talents.mount.FileMountSource;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillCatalogTest {
    @TempDir Path root;

    @Test
    void duplicateShortNameRequiresUniqueIdAndRespectsDisallow() throws Exception {
        Path first = Files.createDirectories(root.resolve("first/demo"));
        Path second = Files.createDirectories(root.resolve("second/demo"));
        Files.write(first.resolve("SKILL.md"), "# First\n\nFirst body".getBytes(StandardCharsets.UTF_8));
        Files.write(second.resolve("SKILL.md"), "# Second\n\nSecond body".getBytes(StandardCharsets.UTF_8));
        MountManager manager = new MountManager(root.toString());
        manager.register(Mount.builder().alias("@first").type(MountType.SKILLS)
                .source(FileMountSource.of(root.resolve("first"))).build());
        manager.register(Mount.builder().alias("@second").type(MountType.SKILLS)
                .source(FileMountSource.of(root.resolve("second"))).build());
        DefaultSkillCatalog catalog = new DefaultSkillCatalog(manager);
        SkillTalent talent = new SkillTalent(catalog);

        assertEquals(2, catalog.getSkillCount());
        assertNull(catalog.getDescriptor("demo"));
        assertNull(catalog.readContent("demo"));
        assertEquals(2, catalog.searchDescriptors("demo").size());
        assertTrue(talent.skilllist().contains("@first/demo"));
        assertTrue(talent.skilllist().contains("@second/demo"));
        SkillContent content = catalog.readContent("@first/demo");
        assertNotNull(content);
        assertTrue(content.getText().contains("First body"));
        assertTrue(content.getRenderedText().contains("<skill_files>"));
        assertEquals("@first/demo", content.getDescriptor().getId());

        manager.disallowSkill("@first/demo");
        assertEquals(1, catalog.getSkillCount());
        assertNull(catalog.readContent("@first/demo"));
        assertFalse(talent.skilllist().contains("@first/demo"));
        assertTrue(talent.skilllist().contains("@second/demo"));
        assertFalse(talent.getInstruction(null).contains("@first/demo"));
        assertFalse(talent.skillsearch("demo").contains("@first/demo"));
        manager.allowSkill("@first/demo");
        assertNotNull(catalog.readContent("@first/demo"));
    }

    @Test
    void disabledMountNotDiscoveredAndRefreshUpdatesIndex() throws Exception {
        Path enabled = Files.createDirectories(root.resolve("enabled/alpha"));
        Path disabled = Files.createDirectories(root.resolve("disabled/beta"));
        Files.write(enabled.resolve("skill.md"), "# Alpha".getBytes(StandardCharsets.UTF_8));
        Files.write(disabled.resolve("SKILL.md"), "# Beta".getBytes(StandardCharsets.UTF_8));
        MountManager manager = new MountManager(root.toString());
        manager.register(Mount.builder().alias("@enabled").type(MountType.SKILLS)
                .source(FileMountSource.of(root.resolve("enabled"))).build());
        manager.register(Mount.builder().alias("@disabled").type(MountType.SKILLS).enabled(false)
                .source(FileMountSource.of(root.resolve("disabled"))).build());
        DefaultSkillCatalog catalog = new DefaultSkillCatalog(manager);
        assertEquals(1, catalog.getSkillCount());
        assertNull(catalog.getDescriptor("@disabled/beta"));
        assertEquals("@enabled/alpha", catalog.getDescriptor("alpha").getId());
        assertNotNull(catalog.readContent("alpha"));
        Files.createDirectories(root.resolve("enabled/gamma"));
        Files.write(root.resolve("enabled/gamma/SKILL.md"), "# Gamma".getBytes(StandardCharsets.UTF_8));
        catalog.refresh();
        assertEquals(2, catalog.getSkillCount());
        assertEquals("@enabled/gamma", catalog.getDescriptor("gamma").getId());
    }
}
