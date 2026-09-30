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
     * 删除路径对应的条目。
     *
     * @param path 来源内路径
     * @throws IOException 删除失败时
     */
    void delete(String path) throws IOException;

    /**
     * 按选项移动条目。
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
