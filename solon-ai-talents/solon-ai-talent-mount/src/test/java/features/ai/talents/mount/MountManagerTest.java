package features.ai.talents.mount;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.MountDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.MountType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class MountManagerTest {
    @TempDir
    Path tempDir;

    @Test
    public void registerAliasWithoutAtShouldBeNormalized() throws Exception {
        Path pool = Files.createDirectory(tempDir.resolve("pool"));
        MountManager manager = new MountManager(tempDir.toString());

        MountDir mount = manager.register(MountDir.builder()
                .alias("pool")
                .path(pool.toString())
                .type(MountType.SKILLS)
                .build());

        assertEquals("@pool", mount.getAlias());
        assertTrue(manager.hasMount("pool"));
        assertTrue(manager.hasMount("@pool"));
        assertEquals(pool.resolve("note.txt"), manager.resolve(tempDir, "@pool/note.txt"));
        assertNotNull(manager.remove("pool"));
        assertFalse(manager.hasMount("@pool"));
    }

    @Test
    public void registerInvalidAliasShouldReject() {
        MountManager manager = new MountManager(tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> register(manager, ""));
        assertThrows(IllegalArgumentException.class, () -> register(manager, "@"));
        assertThrows(IllegalArgumentException.class, () -> register(manager, "@pool/sub"));
        assertThrows(IllegalArgumentException.class, () -> register(manager, "@pool\\sub"));
        assertThrows(IllegalArgumentException.class, () -> register(manager, "@pool name"));
    }

    @Test
    public void resolveMountPathShouldRejectDotDotEscape() throws Exception {
        Path pool = Files.createDirectory(tempDir.resolve("pool"));
        MountManager manager = new MountManager(tempDir.toString());
        register(manager, "@pool", pool);

        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "@pool/../outside.txt"));
    }

    @Test
    public void resolveDisabledOrUnknownAtPathShouldFailClosed() throws Exception {
        Path pool = Files.createDirectory(tempDir.resolve("pool"));
        MountManager manager = new MountManager(tempDir.toString());
        manager.register(MountDir.builder()
                .alias("@pool")
                .path(pool.toString())
                .type(MountType.SKILLS)
                .enabled(false)
                .build());

        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "@pool/a.txt"));
        assertThrows(SecurityException.class, () -> manager.resolve(tempDir, "@missing/a.txt"));
    }

    @Test
    public void mountListOrderShouldFollowRegistrationOrderAndSnapshot() throws Exception {
        MountManager manager = new MountManager(tempDir.toString());
        register(manager, "@b", Files.createDirectory(tempDir.resolve("b")));
        register(manager, "@a", Files.createDirectory(tempDir.resolve("a")));
        register(manager, "@c", Files.createDirectory(tempDir.resolve("c")));

        ArrayList<String> aliases = new ArrayList<>();
        manager.getMounts().forEach(m -> aliases.add(m.getAlias()));
        assertEquals("@b", aliases.get(0));
        assertEquals("@a", aliases.get(1));
        assertEquals("@c", aliases.get(2));
        assertThrows(UnsupportedOperationException.class, () -> manager.getMountKeySet().clear());
        assertEquals(3, manager.getMountKeySet().size());
    }

    private void register(MountManager manager, String alias) {
        manager.register(MountDir.builder()
                .alias(alias)
                .path(tempDir.toString())
                .type(MountType.SKILLS)
                .build());
    }

    private void register(MountManager manager, String alias, Path path) {
        manager.register(MountDir.builder()
                .alias(alias)
                .path(path.toString())
                .type(MountType.SKILLS)
                .build());
    }
}
