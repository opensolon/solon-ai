/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.rag;

import org.noear.solon.core.util.RunUtil;
import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * 可存储的知识库（可存储）
 *
 * @author noear
 * @since 3.1
 */
@Preview("3.1")
public interface RepositoryStorable extends Repository {
    /**
     * 异步保存文档
     *
     * @param documents        文档集
     * @param progressCallback 进度回调
     * @since 3.5
     */
    default CompletableFuture<Void> asyncSave(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        RunUtil.async(() -> {
            try {
                save(documents, progressCallback);
                future.complete(null);
            } catch (Exception ex) {
                future.completeExceptionally(ex);
            }
        });

        return future;
    }

    /**
     * 保存文档
     *
     * @param documents        文档集
     * @param progressCallback 进度回调
     * @since 3.5
     */
    void save(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException;


    /**
     * 保存文档
     *
     * @param documents 文档集
     * @since 3.5
     */
    default void save(List<Document> documents) throws IOException {
        save(documents, null);
    }

    /**
     * 保存文档
     *
     * @param documents 文档集
     * @since 3.5
     */
    default void save(Document... documents) throws IOException {
        save(Arrays.asList(documents));
    }

    /**
     * 删除文档
     *
     * @param ids 文档IDs
     * @since 3.5
     */
    void deleteById(String... ids) throws IOException;

    /**
     * 是否存在文档
     *
     * @param id 文档ID
     * @since 3.5
     */
    boolean existsById(String id) throws IOException;

    //========

    /**
     * 异步插件
     *
     * @param documents        文档集
     * @param progressCallback 进度回调
     * @deprecated 3.5 {@link #asyncSave(List, BiConsumer)}
     */
    @Deprecated
    default CompletableFuture<Void> asyncInsert(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) {
        return asyncSave(documents, progressCallback);
    }

    /**
     * 插入
     *
     * @param documents        文档集
     * @param progressCallback 进度回调
     * @deprecated 3.5 {@link #save(List, BiConsumer)}
     */
    @Deprecated
    default void insert(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        save(documents, progressCallback);
    }


    /**
     * 插入
     *
     * @param documents 文档集
     * @deprecated 3.5 {@link #save(List)}
     */
    @Deprecated
    default void insert(List<Document> documents) throws IOException {
        save(documents);
    }

    /**
     * 插入
     *
     * @param documents 文档集
     * @deprecated 3.5 {@link #save(Document...)}
     */
    @Deprecated
    default void insert(Document... documents) throws IOException {
        save(documents);
    }

    /**
     * 删除文档
     *
     * @param ids 文档IDs
     * @deprecated 3.5 {@link #deleteById(String...)}
     */
    @Deprecated
    default void delete(String... ids) throws IOException {
        deleteById(ids);
    }

    /**
     * 是否存在文档
     *
     * @param id 文档ID
     * @deprecated 3.5 {@link #existsById(String)}
     */
    @Deprecated
    default boolean exists(String id) throws IOException {
        return existsById(id);
    }
}