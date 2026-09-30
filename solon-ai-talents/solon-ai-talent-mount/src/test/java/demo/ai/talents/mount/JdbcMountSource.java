package demo.ai.talents.mount;

import org.noear.solon.ai.talents.mount.source.MountEntry;
import org.noear.solon.ai.talents.mount.source.MountCapabilities;
import org.noear.solon.ai.talents.mount.source.FindOptions;
import org.noear.solon.ai.talents.mount.source.MountSource;
import org.noear.solon.ai.talents.mount.source.MoveOptions;
import org.noear.solon.ai.talents.mount.source.WriteOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;

public class JdbcMountSource implements MountSource {

    @Override
    public String getScheme() {
        return "";
    }

    @Override
    public String normalize(String path) {
        return "";
    }

    @Override
    public MountEntry stat(String path) throws IOException {
        return null;
    }

    @Override
    public List<MountEntry> list(String path) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public List<MountEntry> find(String path, FindOptions options) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        return null;
    }

    @Override
    public OutputStream openWrite(String path, WriteOptions options) throws IOException {
        return null;
    }

    @Override
    public void delete(String path) throws IOException {

    }

    @Override
    public void move(String source, String target, MoveOptions options) throws IOException {

    }

    @Override
    public MountCapabilities capabilities() {
        return null;
    }
}
