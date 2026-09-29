/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.mount.source;

import org.noear.solon.ai.talents.mount.*;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** 
 * 可挂载的虚拟文件来源。 
 * 
 * @author noear 
 * @since 4.1.1
 */
@Preview("4.1.1")
public interface MountSource extends AutoCloseable {
    default String getLocation(){
        return getScheme();
    }

    String getScheme();

    String normalize(String path);

    MountEntry stat(String path) throws IOException;

    default boolean exists(String path) throws IOException {
        return stat(path) != null;
    }

    default boolean isDirectory(String path) throws IOException {
        MountEntry entry = stat(path);
        return entry != null && entry.isDirectory();
    }

    List<MountEntry> list(String path) throws IOException;

    List<MountEntry> find(String path, FindOptions options) throws IOException;

    InputStream openRead(String path) throws IOException;

    OutputStream openWrite(String path, WriteOptions options) throws IOException;

    void delete(String path) throws IOException;

    void move(String source, String target, MoveOptions options) throws IOException;

    MountCapabilities capabilities();

    default Optional<Path> getLocalRoot() {
        return Optional.empty();
    }

    default Optional<Path> getLocalPath(String path) {
        return Optional.empty();
    }

    default Optional<Path> materialize(String path, Path targetDirectory) throws IOException {
        return Optional.empty();
    }

    @Override
    default void close() throws IOException {
    }
}
