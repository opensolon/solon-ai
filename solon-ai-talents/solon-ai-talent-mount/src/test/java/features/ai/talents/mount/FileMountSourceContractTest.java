package features.ai.talents.mount;

import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.source.FileMountSource;
import org.noear.solon.ai.talents.mount.source.MountSource;

import java.nio.file.Path;

/** 本地文件来源跑通 {@link MountSourceContract}。 */
public class FileMountSourceContractTest extends MountSourceContract {
    @TempDir
    Path tempDir;

    @Override
    protected MountSource create() {
        return FileMountSource.of(tempDir);
    }
}
