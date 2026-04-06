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
package org.noear.solon.ai.rag.repository.aliyun.dashvector.util;

import com.aliyun.dashvector.models.Doc;
import com.aliyun.dashvector.models.Vector;

import org.noear.solon.Utils;
import org.noear.solon.ai.rag.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * solon {@link Document} 与 DashVector SDK {@link Doc} 之间的互转工具
 *
 * @author 小奶奶花生米
 * @author 烧饵块
 */
public final class DocumentConverter {
    /**
     * Document content 字段在 Doc.fields 中的存储 key
     */
    public static final String CONTENT_FIELD_KEY = "__content";

    /**
     * Document url 字段在 Doc.fields 中的存储 key
     */
    public static final String URL_FIELD_KEY = "__url";

    private DocumentConverter() {
    }

    /**
     * 把 solon {@link Document} 转换为 DashVector SDK {@link Doc}
     * <p>
     * 自动把 {@code content} 写入 {@link #CONTENT_FIELD_KEY}，
     * {@code url} 写入 {@link #URL_FIELD_KEY}，
     * 其余 metadata 作为字段平铺到 {@code fields}。
     */
    public static Doc toDoc(Document document) {
        if (document == null) {
            return null;
        }

        Map<String, Object> fields = new HashMap<>();
        if (document.getMetadata() != null) {
            fields.putAll(document.getMetadata());
        }
        if (document.getContent() != null) {
            fields.put(CONTENT_FIELD_KEY, document.getContent());
        }
        if (!Utils.isEmpty(document.getUrl())) {
            fields.put(URL_FIELD_KEY, document.getUrl());
        }

        Doc.DocBuilder docBuilder = Doc.builder().id(document.getId());

        float[] embedding = document.getEmbedding();
        if (embedding != null) {
            Vector vector = Vector.builder()
                    .value(floatArrayToList(embedding))
                    .build();
            docBuilder.vector(vector);
        }

        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            docBuilder.field(entry.getKey(), entry.getValue());
        }

        return docBuilder.build();
    }

    /**
     * 批量转换 {@link Document} → {@link Doc}
     */
    public static List<Doc> toDocs(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new ArrayList<>();
        }
        List<Doc> docs = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            docs.add(toDoc(doc));
        }
        return docs;
    }

    /**
     * 把 DashVector SDK {@link Doc} 转换为 solon {@link Document}
     * <p>
     * 自动从 {@code fields} 提取 {@link #CONTENT_FIELD_KEY} 作为 content，
     * {@link #URL_FIELD_KEY} 作为 url；其余字段保留为 metadata；
     * score 经 {@code 1 - distance} 归一化到 0-1。
     */
    public static Document toDocument(Doc doc) {
        if (doc == null) {
            return null;
        }

        Map<String, Object> fields;
        if (doc.getFields() == null) {
            fields = new HashMap<>();
        } else {
            // 复制一份，避免污染 SDK 内部结构
            fields = new HashMap<>(doc.getFields());
        }

        String content = (String) fields.remove(CONTENT_FIELD_KEY);
        String url = (String) fields.remove(URL_FIELD_KEY);

        // 计算相似度分数 (1 - 距离)，限制在 0-1 之间
        double score = 1.0 - Math.min(1.0, Math.max(0.0, doc.getScore()));
        Document document = new Document(doc.getId(), content, fields, score);

        if (!Utils.isEmpty(url)) {
            document.url(url);
        }

        // 把向量回写到 Document，便于上层在 includeVector=true 时拿到
        if (doc.getVector() != null && doc.getVector().getValue() != null) {
            document.embedding(toFloatArray(doc.getVector().getValue()));
        }

        return document;
    }

    /**
     * 批量转换 {@link Doc} → {@link Document}
     */
    public static List<Document> toDocuments(List<Doc> docs) {
        if (docs == null || docs.isEmpty()) {
            return new ArrayList<>();
        }
        List<Document> result = new ArrayList<>(docs.size());
        for (Doc doc : docs) {
            result.add(toDocument(doc));
        }
        return result;
    }

    /**
     * float[] → List&lt;Float&gt;
     */
    public static List<Float> floatArrayToList(float[] array) {
        if (array == null) {
            return new ArrayList<>();
        }
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }

    /**
     * List&lt;? extends Number&gt; → float[]
     */
    public static float[] toFloatArray(List<? extends Number> values) {
        if (values == null) {
            return new float[0];
        }
        float[] array = new float[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i).floatValue();
        }
        return array;
    }
}