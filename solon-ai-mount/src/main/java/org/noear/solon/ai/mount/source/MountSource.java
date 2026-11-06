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
package org.noear.solon.ai.mount.source;

import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 可挂载的虚拟文件来源（本地磁盘、db、http-api 等的统一 IO 抽象）。
 *
 * <p><b>实现契约</b>（{@code MountSourceContract} 测试套件锁定）：</p>
 * <ul>
 *   <li>路径：来源内路径用 {@code /} 分隔，空串表示根；{@link #normalize} 必须拒绝越过根的路径（抛 {@link SecurityException}）。</li>
 *   <li>不存在：{@link #stat} 返回 null；{@link #openRead} 抛 {@link IOException}。</li>
 *   <li>写入可见性：声明 {@link MountCapabilities#isAtomicWrite()} 的来源，覆盖写在流 close 成功前对读者不可见、
 *       写入失败不留残留；调用方自身逻辑可能半途失败时，应先在内存备好完整内容再调 {@link #writeBytes}。</li>
 *   <li>乐观锁：声明 {@link MountCapabilities#isVersioned()} 的来源，文件条目的 {@link MountEntry#getVersion()} 非空且随内容变化；
 *       {@link WriteOptions#getExpectedVersion()} 不匹配（含条目已不存在）时抛 {@link MountConflictException}。</li>
 * </ul>
 *
 * @author noear
 * @since 4.1.1
 */
@Preview("4.1.1")
public interface MountSource extends AutoCloseable {
    /**
     * 获取来源位置标识，默认返回协议名。
     *
     * @return 位置标识
     */
    default String getLocation(){
        return getScheme();
    }

    /**
     * 获取来源的协议名。
     *
     * @return 协议名
     */
    String getScheme();

    /**
     * 规范化来源内路径。
     *
     * @param path 来源内路径
     * @return 规范化后的路径
     */
    String normalize(String path);

    /**
     * 获取路径对应的条目；不存在时返回 null。
     *
     * @param path 来源内路径
     * @return 条目或 null
     * @throws IOException 读取条目失败时
     */
    MountEntry stat(String path) throws IOException;

    /**
     * 判断路径是否存在。
     *
     * @param path 来源内路径
     * @return 存在时为 true
     * @throws IOException 查询条目失败时
     */
    default boolean exists(String path) throws IOException {
        return stat(path) != null;
    }

    /**
     * 判断路径是否为目录。
     *
     * @param path 来源内路径
     * @return 路径存在且为目录时为 true
     * @throws IOException 查询条目失败时
     */
    default boolean isDirectory(String path) throws IOException {
        MountEntry entry = stat(path);
        return entry != null && entry.isDirectory();
    }

    /**
     * 列出路径下的条目。
     *
     * @param path 来源内路径
     * @return 条目列表
     * @throws IOException 列举失败时
     */
    List<MountEntry> list(String path) throws IOException;

    /**
     * 按选项查找路径下的条目。
     *
     * @param path 来源内路径
     * @param options 查找选项
     * @return 匹配的条目列表
     * @throws IOException 查找失败时
     */
    List<MountEntry> find(String path, FindOptions options) throws IOException;

    /**
     * 打开路径的读取流。
     *
     * @param path 来源内路径
     * @return 读取流
     * @throws IOException 打开失败时
     */
    InputStream openRead(String path) throws IOException;

    /**
     * 按选项打开路径的写入流。
     *
     * @param path 来源内路径
     * @param options 写入选项
     * @return 写入流
     * @throws IOException 打开失败时
     */
    OutputStream openWrite(String path, WriteOptions options) throws IOException;

    /**
     * 读取全部字节。
     *
     * @param path 来源内路径
     * @return 内容
     * @throws IOException 不存在或读取失败时
     * @since 4.2.0
     */
    default byte[] readBytes(String path) throws IOException {
        try (InputStream in = openRead(path)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }

    /**
     * 按 UTF-8 读取文本；条目不存在或是目录时返回 null。
     *
     * @param path 来源内路径
     * @return 文本或 null
     * @throws IOException 读取失败时
     * @since 4.2.0
     */
    default String readStringOrNull(String path) throws IOException {
        MountEntry entry = stat(path);
        if (entry == null || entry.isDirectory()) {
            return null;
        }
        return new String(readBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 整体写入字节（内容在内存中已完整，配合原子写来源可保证「要么全新、要么全旧」）。
     *
     * @param path    来源内路径
     * @param data    内容
     * @param options 写入选项；为 null 时覆盖写并创建父目录
     * @throws IOException 写入失败或版本冲突（{@link MountConflictException}）时
     * @since 4.2.0
     */
    default void writeBytes(String path, byte[] data, WriteOptions options) throws IOException {
        try (OutputStream out = openWrite(path, options == null ? WriteOptions.replace() : options)) {
            out.write(data);
        }
    }

    /**
     * 按 UTF-8 覆盖写入文本（创建父目录）。
     *
     * @param path    来源内路径
     * @param content 文本
     * @throws IOException 写入失败时
     * @since 4.2.0
     */
    default void writeString(String path, String content) throws IOException {
        writeBytes(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8), WriteOptions.replace());
    }

    /**
     * 删除条目（不存在时忽略）。
     *
     * @param path 来源内路径
     * @return 是否确有删除
     * @throws IOException 删除失败时
     * @since 4.2.0
     */
    default boolean deleteIfExists(String path) throws IOException {
        if (stat(path) == null) {
            return false;
        }
        delete(path);
        return true;
    }

    /**
     * 删除路径对应的条目：文件或空目录。
     *
     * <p>契约：不递归——目录非空时抛出 {@link IOException} 且内容保持不变；条目不存在时抛出 {@link IOException}；
     * 删除来源根（空路径）抛出 {@link SecurityException}。</p>
     *
     * @param path 来源内路径
     * @throws IOException 删除失败时
     */
    void delete(String path) throws IOException;

    /**
     * 按选项移动条目（文件或目录）。
     *
     * <p>契约：目标的上级目录自动创建（可跨目录移动）；移动目录时连同其全部内容；
     * 目标已存在且未指定 {@link MoveOptions#isReplaceExisting()} 时抛出 {@link IOException}，两边保持不变；
     * 来源不存在时抛出 {@link IOException}。</p>
     *
     * @param source 来源内原路径
     * @param target 来源内目标路径
     * @param options 移动选项
     * @throws IOException 移动失败时
     */
    void move(String source, String target, MoveOptions options) throws IOException;

    /**
     * 获取来源支持的操作能力。
     *
     * @return 操作能力
     */
    MountCapabilities capabilities();

    /**
     * 获取本地根目录；默认不提供。
     *
     * @return 本地根目录，默认返回空值
     */
    default Optional<Path> getLocalRoot() {
        return Optional.empty();
    }

    /**
     * 获取来源内路径对应的本地路径；默认不提供。
     *
     * @param path 来源内路径
     * @return 本地路径，默认返回空值
     */
    default Optional<Path> getLocalPath(String path) {
        return Optional.empty();
    }

    /**
     * 将来源内条目物化到目标目录；默认不提供。
     *
     * @param path 来源内路径
     * @param targetDirectory 目标目录
     * @return 物化后的本地路径，默认返回空值
     * @throws IOException 物化失败时
     */
    default Optional<Path> materialize(String path, Path targetDirectory) throws IOException {
        return Optional.empty();
    }

    /**
     * 获取来源内容指纹，用于物化缓存的失效判定；默认返回位置标识。
     *
     * <p>声明 materializable=true 的来源必须覆写此方法，提供能随内容或可靠版本变化
     * 而改变的指纹；无法确定指纹时应抛出异常，不能退回位置标识并误命中旧脚本。
     * 该方法可能在每次命令翻译时调用，来源应按实际体量选择内容摘要或可信版本号。</p>
     *
     * @return 内容指纹
     */
    default String getFingerprint() {
        return getLocation();
    }

    /**
     * 关闭来源并释放资源；默认不执行操作。
     *
     * @throws IOException 关闭失败时
     */
    @Override
    default void close() throws IOException {
    }
}
