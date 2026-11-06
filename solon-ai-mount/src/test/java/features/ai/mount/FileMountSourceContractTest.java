package features.ai.mount;

import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.mount.source.FileMountSource;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.testkit.MountSourceContract;

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
