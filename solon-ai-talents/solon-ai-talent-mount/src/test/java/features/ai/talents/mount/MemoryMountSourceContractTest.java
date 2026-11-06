package features.ai.talents.mount;

import org.noear.solon.ai.talents.mount.source.MemoryMountSource;
import org.noear.solon.ai.talents.mount.source.MountSource;

/** 内存来源（非本地来源参考实现）跑通 {@link MountSourceContract}。 */
public class MemoryMountSourceContractTest extends MountSourceContract {
    @Override
    protected MountSource create() {
        return new MemoryMountSource();
    }
}
