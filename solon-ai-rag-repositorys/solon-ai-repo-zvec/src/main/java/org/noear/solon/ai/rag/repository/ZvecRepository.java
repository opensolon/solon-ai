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
package org.noear.solon.ai.rag.repository;

import org.noear.snack4.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.zvec.CollectionRegistry;
import org.noear.solon.ai.rag.repository.zvec.FilterTransformer;
import org.noear.solon.ai.rag.repository.zvec.MetadataField;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.lang.Preview;
import org.zvec.binding.Collection;
import org.zvec.binding.CollectionOptions;
import org.zvec.binding.CollectionSchema;
import org.zvec.binding.DataType;
import org.zvec.binding.Doc;
import org.zvec.binding.FieldSchema;
import org.zvec.binding.IndexParams;
import org.zvec.binding.MetricType;
import org.zvec.binding.VectorQuery;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Zvec 矢量存储知识库（阿里开源的进程内向量库）
 *
 * <p>Zvec 是嵌入式（in-process）向量库：一个 collection 就是一个本地目录，
 * 无需部署服务端。数据落在 {@code basePath/collectionName} 下。</p>
 *
 * @author noear
 * @since 4.2
 */
@Preview("4.2")
public class ZvecRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;

    private ZvecRepository(Builder config) {
        this.config = config;

        initRepository();
    }

    /**
     * 初始化仓库
     */
    @Override
    public void initRepository() {
        synchronized (config) {
            //句柄可能被同一路径的其它 Repository（或 dropRepository）关掉/删掉，失效时重新打开
            if (config.collection == null || config.collection.isOpen() == false) {
                CollectionRegistry.initNative();

                CollectionOptions options = config.collectionOptions();

                try {
                    //同一目录只能打开一次，交给注册表复用
                    config.collection = CollectionRegistry.open(config.collectionPath(), this::readSchema, options);
                } finally {
                    //集合内部会留有自己的一份，传入的选项可以回收
                    options.close();
                }
            }
        }
    }

    /**
     * 注销仓库（删除磁盘数据）
     */
    @Override
    public void dropRepository() {
        synchronized (config) {
            //destroy 会删除磁盘数据并关闭句柄（同时清掉注册表里的记录）
            CollectionRegistry.destroy(config.collectionPath());
            config.collection = null;
        }
    }

    /**
     * 获取集合句柄（句柄失效时重新打开）
     */
    private Collection collection() {
        initRepository();

        Collection collection = config.collection;
        if (collection == null) {
            throw new IllegalStateException("The zvec collection is not initialized");
        }

        return collection;
    }

    /**
     * 构建 schema（新建 collection 时使用）
     */
    private CollectionSchema readSchema() {
        CollectionSchema schema = new CollectionSchema(config.collectionName);

        //addField 是深拷贝，字段定义用完即可释放（否则原生内存泄漏）
        try (FieldSchema contentField = new FieldSchema(config.contentFieldName, DataType.STRING, true, 0)) {
            schema.addField(contentField);
        }

        try (FieldSchema metadataField = new FieldSchema(config.metadataFieldName, DataType.STRING, true, 0)) {
            schema.addField(metadataField);
        }

        for (MetadataField metadataField : config.metadataFields) {
            try (FieldSchema fieldSchema = new FieldSchema(metadataField.getName(), metadataField.getDataType(), true, 0)) {
                if (metadataField.isIndexed()) {
                    try (IndexParams indexParams = IndexParams.createInvert(true, true)) {
                        fieldSchema.setIndexParams(indexParams);
                    }
                }
                schema.addField(fieldSchema);
            }
        }

        try (FieldSchema vectorField = new FieldSchema(config.embeddingFieldName, DataType.VECTOR_FP32, false, config.dimensions)) {
            try (IndexParams hnsw = IndexParams.createHNSW(config.metricType, config.hnswM, config.hnswEfConstruction)) {
                vectorField.setIndexParams(hnsw);
            }
            schema.addField(vectorField);
        }

        return schema;
    }

    /**
     * 批量存储文档（支持更新）
     */
    @Override
    public void save(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        // 确保所有文档都有ID
        for (Document doc : documents) {
            if (Utils.isEmpty(doc.getId())) {
                doc.id(Utils.uuid());
            }
        }

        // 分块处理
        List<List<Document>> batchList = ListUtil.partition(documents, config.embeddingModel.batchSize());
        int batchIndex = 0;
        for (List<Document> batch : batchList) {
            // 批量embedding
            config.embeddingModel.embed(batch);
            batchSaveDo(batch);

            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(++batchIndex, batchList.size());
            }
        }

        if (config.optimizeOnSave) {
            optimize();
        }
    }

    private void batchSaveDo(List<Document> batch) throws IOException {
        List<Doc> docs = new ArrayList<>(batch.size());

        try {
            for (Document doc : batch) {
                docs.add(toDoc(doc));
            }

            collection().upsert(docs);
        } finally {
            //toDoc 中途抛异常也要释放已创建的原生对象
            Doc.freeDocs(docs);
        }
    }

    /**
     * 优化索引（把暂存区的向量合并进索引，提升检索速度）
     */
    public void optimize() {
        collection().optimize();
    }

    @Override
    public void deleteById(String... ids) throws IOException {
        if (Utils.isEmpty(ids)) {
            return;
        }

        collection().delete(Arrays.asList(ids));
    }

    @Override
    public boolean existsById(String id) throws IOException {
        if (Utils.isEmpty(id)) {
            return false;
        }

        List<Doc> docs = collection().fetch(Collections.singletonList(id));
        try {
            return Utils.isNotEmpty(docs);
        } finally {
            Doc.freeDocs(docs);
        }
    }

    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        float[] queryVector = config.embeddingModel.embed(condition.getQuery());

        String filter = null;
        int topK = condition.getLimit();

        if (condition.getFilterExpression() != null) {
            filter = FilterTransformer.getInstance().transform(condition.getFilterExpression(), config.filterColumns());

            if (filter == null) {
                //无法下推（含未知列或不支持的运算符），放宽召回后由内存二次过滤
                topK = Math.max(topK * 10, 100);
            }
        }

        VectorQuery query = new VectorQuery();
        List<Doc> docs = null;

        try {
            query.setFieldName(config.embeddingFieldName);
            query.setQueryVector(queryVector);
            query.setTopK(topK);
            query.setIncludeVector(config.includeVector);
            query.setIncludeDocID(false);

            if (Utils.isNotEmpty(filter)) {
                query.setFilter(filter);
            }

            docs = collection().query(query);

            Stream<Document> stream = docs.stream().map(this::toDocument);

            // 再次过滤下（评分与阈值、以及无法下推的条件）
            return SimilarityUtil.refilter(stream, condition);
        } finally {
            if (docs != null) {
                Doc.freeDocs(docs);
            }
            query.close();
        }
    }

    /**
     * 文档转为 Zvec 文档
     */
    private Doc toDoc(Document doc) {
        Doc zDoc = new Doc();
        zDoc.setPK(doc.getId());

        if (doc.getContent() != null) {
            zDoc.addStringField(config.contentFieldName, doc.getContent());
        }

        if (Utils.isNotEmpty(doc.getMetadata())) {
            zDoc.addStringField(config.metadataFieldName, ONode.serialize(doc.getMetadata()));

            for (MetadataField metadataField : config.metadataFields) {
                Object value = doc.getMetadata(metadataField.getName());
                if (value == null) {
                    continue; //可空字段，跳过
                }

                switch (metadataField.getDataType()) {
                    case STRING:
                        zDoc.addStringField(metadataField.getName(), String.valueOf(value));
                        break;
                    case BOOL:
                        zDoc.addBoolField(metadataField.getName(), toBoolean(metadataField, value));
                        break;
                    case INT32:
                        zDoc.addInt32Field(metadataField.getName(), toNumber(metadataField, value).intValue());
                        break;
                    case INT64:
                        zDoc.addInt64Field(metadataField.getName(), toNumber(metadataField, value).longValue());
                        break;
                    case FLOAT:
                        zDoc.addFloatField(metadataField.getName(), toNumber(metadataField, value).floatValue());
                        break;
                    case DOUBLE:
                        zDoc.addDoubleField(metadataField.getName(), toNumber(metadataField, value).doubleValue());
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported metadata field type: " + metadataField.getDataType());
                }
            }
        }

        if (doc.getEmbedding() != null) {
            if (doc.getEmbedding().length != config.dimensions) {
                throw new IllegalArgumentException("Document '" + doc.getId() + "' embedding dimensions is "
                        + doc.getEmbedding().length + ", but the collection is " + config.dimensions);
            }

            zDoc.addVectorFP32Field(config.embeddingFieldName, doc.getEmbedding());
        }

        return zDoc;
    }

    /**
     * 元数据值转为 Zvec 标量值（类型不匹配时给出明确提示，而不是掷 ClassCastException）
     */
    private static Number toNumber(MetadataField field, Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }

        throw new IllegalArgumentException("Metadata field '" + field.getName() + "' is declared as "
                + field.getDataType() + " but the value is " + value.getClass().getName() + ": " + value);
    }

    private static boolean toBoolean(MetadataField field, Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        //不做字符串宽松转换：否则写入时是 true、内存二次过滤时又是 "true"，两套语义会对不上
        throw new IllegalArgumentException("Metadata field '" + field.getName() + "' is declared as BOOL"
                + " but the value is " + value.getClass().getName() + ": " + value);
    }

    /**
     * Zvec 文档转为文档
     */
    private Document toDocument(Doc zDoc) {
        String content = zDoc.hasFieldValue(config.contentFieldName) ? zDoc.getStringField(config.contentFieldName) : null;

        Map<String, Object> metadata = null;
        if (zDoc.hasFieldValue(config.metadataFieldName)) {
            String metadataJson = zDoc.getStringField(config.metadataFieldName);
            if (Utils.isNotEmpty(metadataJson)) {
                metadata = ONode.deserialize(metadataJson, Map.class);
            }
        }

        Document doc = new Document(zDoc.getPK(), content, metadata, toSimilarity(zDoc.getScore()));

        if (config.includeVector) {
            doc.embedding(zDoc.getVectorFP32Field(config.embeddingFieldName));
        }

        return doc;
    }

    /**
     * 距离转为相似度（越大越相似）
     */
    private double toSimilarity(double distance) {
        switch (config.metricType) {
            case COSINE:
                //zvec 返回的是余弦距离（1 - cos）
                return 1.0D - distance;
            case L2:
                //zvec 返回的是平方欧氏距离
                return 1.0D / (1.0D + distance);
            case IP:
                //zvec 返回的是内积（越大越相似）
                return distance;
            default:
                return 1.0D / (1.0D + distance);
        }
    }

    public static Builder builder(EmbeddingModel embeddingModel, String basePath) {
        return new Builder(embeddingModel, basePath);
    }

    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final String basePath;

        private String collectionName = "solon_ai";

        private static final String CONTENT_FIELD_NAME = "content";
        private static final String METADATA_FIELD_NAME = "metadata";
        private static final String EMBEDDING_FIELD_NAME = "embedding";

        private final String contentFieldName = CONTENT_FIELD_NAME;
        private final String metadataFieldName = METADATA_FIELD_NAME;
        private final String embeddingFieldName = EMBEDDING_FIELD_NAME;

        private List<MetadataField> metadataFields = new ArrayList<>();
        private MetricType metricType = MetricType.COSINE;
        private int hnswM = 16;
        private int hnswEfConstruction = 200;
        private boolean includeVector = false;
        private boolean optimizeOnSave = false;
        private boolean mmap = true;
        private boolean readOnly = false;

        private int dimensions;
        private Collection collection;

        public Builder(EmbeddingModel embeddingModel, String basePath) {
            this.embeddingModel = embeddingModel;
            this.basePath = basePath;
        }

        /**
         * 集合名（一个集合对应 basePath 下的一个子目录）
         */
        public Builder collectionName(String collectionName) {
            if (Utils.isEmpty(collectionName)) {
                throw new IllegalArgumentException("The collectionName cannot be empty");
            }
            if (collectionName.indexOf('/') >= 0 || collectionName.indexOf('\\') >= 0
                    || collectionName.indexOf("..") >= 0) {
                //集合名会拼成子目录，不允许跳目录
                throw new IllegalArgumentException("The collectionName cannot contain path separators or '..': " + collectionName);
            }

            this.collectionName = collectionName;
            return this;
        }

        /**
         * 参与过滤的元数据字段（Zvec 是强类型引擎，未声明的元数据只能整体取回，不能用于过滤）
         */
        public Builder metadataFields(List<MetadataField> metadataFields) {
            if (metadataFields != null) {
                this.metadataFields = metadataFields;
            }
            return this;
        }

        public Builder metadataFields(MetadataField... metadataFields) {
            if (metadataFields != null) {
                this.metadataFields = Arrays.asList(metadataFields);
            }
            return this;
        }

        /**
         * 检查元数据字段定义（重名、与内置列冲突）
         */
        private void checkMetadataFields() {
            Set<String> names = new HashSet<>();

            for (MetadataField metadataField : metadataFields) {
                if (metadataField == null) {
                    throw new IllegalArgumentException("The metadataFields cannot contain null");
                }

                String name = metadataField.getName();
                if (Utils.isEmpty(name)) {
                    throw new IllegalArgumentException("The metadata field name cannot be empty");
                }

                if (CONTENT_FIELD_NAME.equals(name) || METADATA_FIELD_NAME.equals(name)
                        || EMBEDDING_FIELD_NAME.equals(name)) {
                    throw new IllegalArgumentException("The metadata field name is reserved: " + name);
                }

                if (names.add(name) == false) {
                    throw new IllegalArgumentException("The metadata field is duplicated: " + name);
                }
            }
        }

        /**
         * 距离度量（默认 COSINE）
         */
        public Builder metricType(MetricType metricType) {
            this.metricType = metricType;
            return this;
        }

        /**
         * HNSW 参数：每个节点的最大连接数
         */
        public Builder hnswM(int hnswM) {
            this.hnswM = hnswM;
            return this;
        }

        /**
         * HNSW 参数：构图时的候选队列大小
         */
        public Builder hnswEfConstruction(int hnswEfConstruction) {
            this.hnswEfConstruction = hnswEfConstruction;
            return this;
        }

        /**
         * 检索结果是否带回向量（默认 false）
         */
        public Builder includeVector(boolean includeVector) {
            this.includeVector = includeVector;
            return this;
        }

        /**
         * 保存后是否立即优化索引（默认 false）
         */
        public Builder optimizeOnSave(boolean optimizeOnSave) {
            this.optimizeOnSave = optimizeOnSave;
            return this;
        }

        /**
         * 是否启用 mmap（默认 true）
         */
        public Builder mmap(boolean mmap) {
            this.mmap = mmap;
            return this;
        }

        /**
         * 是否只读打开（默认 false）
         */
        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        /// ///////////////////

        private String collectionPath() {
            return new File(new File(basePath), collectionName).getPath();
        }

        private CollectionOptions collectionOptions() {
            CollectionOptions options = new CollectionOptions();
            options.setEnableMmap(mmap);
            options.setReadOnly(readOnly);
            return options;
        }

        /**
         * 可下推的过滤列（表达式里出现其它列时，交由内存二次过滤）
         *
         * <p>注意：不能包含 content 与 metadata。solon-ai 的过滤表达式是在文档 metadata 上求值的，
         * 而 content 是正文列，下推出去会把“按正文过滤”当成“按 metadata.content 过滤”，语义不一致。</p>
         */
        private Set<String> filterColumns() {
            Set<String> columns = new HashSet<>();

            for (MetadataField metadataField : metadataFields) {
                columns.add(metadataField.getName());
            }

            return columns;
        }


        public ZvecRepository build() {
            if (embeddingModel == null) {
                throw new IllegalArgumentException("The embeddingModel cannot be null");
            }
            if (Utils.isEmpty(basePath)) {
                throw new IllegalArgumentException("The basePath cannot be empty");
            }
            if (metricType == null) {
                throw new IllegalArgumentException("The metricType cannot be null");
            }

            checkMetadataFields();

            try {
                this.dimensions = embeddingModel.dimensions();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to get embedding dimensions", e);
            }

            if (this.dimensions <= 0) {
                throw new IllegalStateException("Invalid embedding dimensions: " + this.dimensions);
            }

            return new ZvecRepository(this);
        }
    }
}
