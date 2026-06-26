package features.ai.talents.diff;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.diff.ApplyPatchTalent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ApplyPatchTalentTest {
    @TempDir
    Path tempDir;

    @Test
    public void testAddFileShouldNotIncludeEndPatchMarker() throws Throwable {
        ApplyPatchTalent talent = new ApplyPatchTalent();
        String patch = "*** Begin Patch\n" +
                "*** Add File: hello.txt\n" +
                "+Hello\n" +
                "*** End Patch\n";

        talent.apply_patch(patch, tempDir.toString());

        String content = new String(Files.readAllBytes(tempDir.resolve("hello.txt")), StandardCharsets.UTF_8);
        Assertions.assertEquals("Hello\n", content);
    }
}
