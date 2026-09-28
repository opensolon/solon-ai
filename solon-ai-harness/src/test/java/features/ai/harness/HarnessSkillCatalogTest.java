package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.talents.mount.FileMountSource;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HarnessSkillCatalogTest {
    @TempDir Path workspace;

    @Test
    void skillQueriesFollowCatalogAndMountChanges() throws Exception {
        Path skill = Files.createDirectories(workspace.resolve("skills/demo"));
        Files.write(skill.resolve("SKILL.md"), "# Demo\n\nHello".getBytes(StandardCharsets.UTF_8));
        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .mountAdd(Mount.builder().alias("@skills").type(MountType.SKILLS)
                        .source(FileMountSource.of(workspace.resolve("skills"))).build())
                .build();

        assertNotNull(engine.getSkillCatalog());
        assertEquals("@skills/demo", engine.getSkill("demo").getId());
        assertEquals(1, engine.getSkillsByMount("skills").size());
        assertEquals(1, engine.getSkills().size());
        engine.removeMount("skills");
        assertNull(engine.getSkill("demo"));
        assertTrue(engine.getSkillsByMount("@skills").isEmpty());
    }
}
