package features.ai.mount;

import org.noear.solon.ai.mount.source.InMemoryMountSource;
import org.noear.solon.ai.mount.source.MountSource;
import org.noear.solon.ai.mount.testkit.MountSourceContract;

/** 内存来源（非本地来源参考实现）跑通 {@link MountSourceContract}。 */
public class InMemoryMountSourceContractTest extends MountSourceContract {
    @Override
    protected MountSource create() {
        return new InMemoryMountSource();
    }
}
