package features.ai.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.solon.ai.talents.mount.FileMountSource;
import org.noear.solon.ai.talents.mount.Mount;
import org.noear.solon.ai.talents.mount.MountType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HarnessAgentMountTest {
    @TempDir Path workspace;

    @Test
    void mountReplacementRefreshAndRemovalUseCurrentDefinition() throws Exception {
        Path first = Files.createDirectories(workspace.resolve("first"));
        Path second = Files.createDirectories(workspace.resolve("second"));
        Path other = Files.createDirectories(workspace.resolve("other"));
        writeAgent(first, "first");
        writeAgent(second, "second");
        writeAgent(other, "other", "other");

        HarnessEngine engine = HarnessEngine.of(workspace.toString(), workspace.resolve("home").toString())
                .mountAdd(agentMount(first)).build();
        assertEquals(1, engine.getAgentsByMount("agents").size());
        engine.addMount(Mount.builder().alias("other").type(MountType.AGENTS)
                .source(FileMountSource.of(other)).build());
        assertEquals(1, engine.getAgentsByMount("other").size());
        assertTrue(engine.getAgentsByMount("agents").stream()
                .allMatch(agent -> "@agents".equals(agent.getMountAlias())));
        assertEquals("first", engine.getAgentManager().getAgent("custom").getSystemPrompt().trim());
        assertEquals("first", engine.getAgentManager().getAgents().stream()
                .filter(a -> "custom".equals(a.getName())).findFirst().get().getSystemPrompt().trim());

        engine.addMount(agentMount(second));
        assertEquals("second", engine.getAgentManager().getAgent("custom").getSystemPrompt().trim());
        writeAgent(second, "updated");
        engine.refreshMount("agents");
        assertEquals("updated", engine.getAgentManager().getAgent("custom").getSystemPrompt().trim());

        Files.delete(second.resolve("custom.md"));
        engine.refreshMount("agents");
        assertFalse(engine.getAgentManager().hasAgent("custom"));
        writeAgent(second, "restored");
        engine.refreshMount("agents");
        assertEquals("restored", engine.getAgentManager().getAgent("custom").getSystemPrompt().trim());
        engine.removeMount("agents");
        assertFalse(engine.getAgentManager().hasAgent("custom"));
        assertTrue(engine.getAgentsByMount("agents").isEmpty());
        assertNotNull(engine.getAgentManager().getAgent("plan"));
    }

    private Mount agentMount(Path root) {
        return Mount.builder().alias("@agents").type(MountType.AGENTS)
                .source(FileMountSource.of(root)).build();
    }

    private void writeAgent(Path root, String prompt) throws Exception {
        writeAgent(root, "custom", prompt);
    }

    private void writeAgent(Path root, String name, String prompt) throws Exception {
        Files.write(root.resolve(name + ".md"), ("---\nname: " + name + "\ndescription: test\n---\n" + prompt)
                .getBytes(StandardCharsets.UTF_8));
    }
}
