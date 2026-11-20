/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.rag.repository.zvec;

import org.zvec.binding.Collection;
import org.zvec.binding.CollectionOptions;
import org.zvec.binding.CollectionSchema;
import org.zvec.binding.Zvec;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Zvec 集合注册表
 *
 * <p>Zvec 的一个集合目录在同一进程内只能被打开一次（目录里有读写锁），所以指向同一路径的
 * 多个 {@code ZvecRepository} 必须共用同一个 {@link Collection} 句柄。注册表以绝对路径为键缓存句柄。</p>
 *
 * @author noear
 * @since 4.2
 */
public class CollectionRegistry {
    private static final Map<String, Collection> collections = new HashMap<>();

    /**
     * 初始化 Zvec 原生库（幂等）
     */
    public static void initNative() {
        if (Zvec.isInitialized() == false) {
            synchronized (Zvec.class) {
                if (Zvec.isInitialized() == false) {
                    Zvec.initialize(null);
                }
            }
        }
    }

    /**
     * 打开（或复用）集合
     *
     * @param path    集合目录
     * @param creator 集合不存在时的 schema 构造器
     * @param options 集合选项
     */
    public static synchronized Collection open(String path, Creator creator, CollectionOptions options) {
        String key = new File(path).getAbsolutePath();

        Collection collection = collections.get(key);
        if (collection != null && collection.isOpen()) {
            return collection;
        }

        File dir = new File(key);
        if (dir.exists()) {
            collection = Zvec.open(key, options);
        } else {
            File parentDir = dir.getParentFile();
            if (parentDir != null && parentDir.exists() == false) {
                parentDir.mkdirs();
            }

            CollectionSchema schema = creator.create();
            try {
                collection = Zvec.createAndOpen(key, schema, options);
            } finally {
                schema.close();
            }
        }

        collections.put(key, collection);
        return collection;
    }

    /**
     * 销毁集合（删除磁盘数据并关闭句柄）
     */
    public static synchronized void destroy(String path) {
        String key = new File(path).getAbsolutePath();

        Collection collection = collections.remove(key);
        if (collection != null) {
            collection.destroy();
        } else {
            deleteDir(new File(key));
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDir(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * 集合 schema 构造器
     */
    public interface Creator {
        CollectionSchema create();
    }
}
