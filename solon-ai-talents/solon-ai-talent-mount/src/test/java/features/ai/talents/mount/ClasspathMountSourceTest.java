package features.ai.talents.mount;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.talents.mount.source.ClasspathMountSource;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.MountEntry;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ClasspathMountSourceTest {
    @Test
    public void readsAndFindsClasspathResources() throws Exception {
        ClasspathMountSource source = ClasspathMountSource.of(
                Thread.currentThread().getContextClassLoader(), "test-skills");

        assertTrue(source.isDirectory("demo"));
        try (InputStream input = source.openRead("demo/SKILL.md")) {
            String content = new String(readAll(input), StandardCharsets.UTF_8);
            assertTrue(content.contains("Demo classpath skill"));
        }

        List<MountEntry> entries = source.find("demo", FindOptions.builder()
                .glob("**/*.md").filesOnly(true).maxDepth(3).build());
        assertEquals(2, entries.size());
        assertTrue(source.find("demo", FindOptions.builder().maxEntries(0).build()).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> source.openWrite("demo/new.md", null));
        assertFalse(source.capabilities().isShellAccessible());
    }

    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
