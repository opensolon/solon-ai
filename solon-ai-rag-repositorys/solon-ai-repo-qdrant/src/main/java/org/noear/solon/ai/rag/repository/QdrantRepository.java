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
package org.noear.solon.ai.rag.repository;

import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.*;

import org.noear.snack.ONode;
import org.noear.solon.Utils;
import org.noear.solon.ai.embedding.EmbeddingModel;
import org.noear.solon.ai.rag.Document;
import org.noear.solon.ai.rag.RepositoryLifecycle;
import org.noear.solon.ai.rag.RepositoryStorable;
import org.noear.solon.ai.rag.repository.qdrant.FilterTransformer;
import org.noear.solon.ai.rag.repository.qdrant.QdrantValueUtil;
import org.noear.solon.ai.rag.util.SimilarityUtil;
import org.noear.solon.ai.rag.util.ListUtil;
import org.noear.solon.ai.rag.util.QueryCondition;

import org.noear.solon.lang.Preview;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.WithPayloadSelectorFactory.include;
import static io.qdrant.client.WithVectorsSelectorFactory.enable;

/**
 * Qdrant 矢量存储知识库
 *
 * @author Anush008
 * @since 3.1
 */
@Preview("3.1")
public class QdrantRepository implements RepositoryStorable, RepositoryLifecycle {
    private final Builder config;

    private QdrantRepository(Builder config) {
        this.config = config;

        initRepository();
    }

    @Override
    public void initRepository() {
        try {
            boolean exists = config.client.collectionExistsAsync(config.collectionName).get();

            if (!exists) {
                int dimensions = config.embeddingModel.dimensions();

                config.client.createCollectionAsync(config.collectionName,
                        VectorParams.newBuilder().setSize(dimensions)
                                .setDistance(Distance.Cosine)
                                .build()).get();

            }
        } catch (InterruptedException | ExecutionException | IOException e) {
            throw new RuntimeException("Failed to initialize Qdrant repository", e);
        }
    }

    @Override
    public void dropRepository() {
        try {
            config.client.deleteCollectionAsync(config.collectionName).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to drop Qdrant repository", e);
        }
    }

    @Override
    public void insert(List<Document> documents, BiConsumer<Integer, Integer> progressCallback) throws IOException {
        if (Utils.isEmpty(documents)) {
            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(0, 0);
            }
            return;
        }

        // 分块处理
        List<List<Document>> batchList = ListUtil.partition(documents, config.embeddingModel.batchSize());
        int batchIndex = 0;
        for (List<Document> batch : batchList) {
            config.embeddingModel.embed(batch);
            batchInsertDo(batch);

            //回调进度
            if (progressCallback != null) {
                progressCallback.accept(batchIndex++, batchList.size());
            }
        }
    }

    private void batchInsertDo(List<Document> batch) throws IOException{
        List<PointStruct> points = batch.stream()
                .map(this::toPointStruct)
                .collect(Collectors.toList());

        try {
            config.client.upsertAsync(UpsertPoints.newBuilder()
                    .setCollectionName(config.collectionName)
                    .addAllPoints(points).build()).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to insert documents from Qdrant", e);
        }
    }

    @Override
    public void delete(String... ids) throws IOException {
        try {
            List<PointId> pointIds = Arrays.stream(ids).map(id -> PointId.newBuilder().setUuid(id).build())
                    .collect(Collectors.toList());

            config.client.deleteAsync(config.collectionName, pointIds).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to delete documents from Qdrant", e);
        }
    }

    @Override
    public boolean exists(String id) throws IOException {
        try {
            List<RetrievedPoint> points = config.client.retrieveAsync(
                    GetPoints.newBuilder().setCollectionName(config.collectionName)
                    .addIds(PointIdFactory.id(UUID.fromString(id))).build(), null).get();

            return points.size() > 0;
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to check document existence in Qdrant", e);
        }
    }

    @Override
    public List<Document> search(QueryCondition condition) throws IOException {
        try {
            float[] queryVector = config.embeddingModel.embed(condition.getQuery());

            QueryPoints.Builder queryBuilder = QueryPoints.newBuilder().setCollectionName(config.collectionName)
                    .setQuery(nearest(queryVector))
                    .setLimit(condition.getLimit())
                    .setScoreThreshold((float) condition.getSimilarityThreshold())
                    .setWithPayload(include(Arrays.asList(config.contentFieldName, config.metadataFieldName)))
                    .setWithVectors(enable(true));

            Filter filter = FilterTransformer.getInstance().transform(condition.getFilterExpression());

            if (filter != null) {
                queryBuilder.setFilter(filter);
            }

            List<ScoredPoint> points = config.client.queryAsync(queryBuilder.build()).get();

            Stream<Document> docs = points.stream().map(this::toDocument);

            return SimilarityUtil.refilter(docs, condition);
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to search documents in Qdrant", e);
        }
    }

    private PointStruct toPointStruct(Document doc) {
        if (doc.getId() == null) {
            doc.id(Utils.uuid());
        }

        Map<String, JsonWithInt.Value> payload = QdrantValueUtil.fromMap(doc.getMetadata());

        payload.put(config.contentFieldName, value(doc.getContent()));

        if (doc.getMetadata() != null) {
            String metadataJson = ONode.stringify(doc.getMetadata());
            payload.put("metadata", value(metadataJson));
        }

        return PointStruct.newBuilder().setId(PointIdFactory.id(UUID.fromString(doc.getId())))
                .setVectors(vectors(doc.getEmbedding())).putAllPayload(payload).build();
    }

    @SuppressWarnings("unchecked")
    private Document toDocument(ScoredPoint scoredPoint) {
        String id = scoredPoint.getId().getUuid();
        float score = scoredPoint.getScore();

        Map<String, JsonWithInt.Value> payload = scoredPoint.getPayloadMap();

        String content = payload.get(config.contentFieldName).getStringValue();

        Map<String, Object> metadata = null;
        if (payload.containsKey(config.metadataFieldName)) {
            String metadataJson = payload.get(config.metadataFieldName).getStringValue();
            metadata = ONode.deserialize(metadataJson, Map.class);
        }

        float[] embedding = listToFloatArray(scoredPoint.getVectors().getVector().getDataList());

        Document doc = new Document(id, content, metadata, score);
        return doc.embedding(embedding);
    }

    private float[] listToFloatArray(List<Float> list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    public static Builder builder(EmbeddingModel embeddingModel, QdrantClient client) {
        return new Builder(embeddingModel, client);
    }

    public static class Builder {
        private final EmbeddingModel embeddingModel;
        private final QdrantClient client;
        private String collectionName = "solon_ai";

        private String contentFieldName = "content";
        private String metadataFieldName = "metadata";

        public Builder(EmbeddingModel embeddingModel, QdrantClient client) {
            this.embeddingModel = embeddingModel;
            this.client = client;
        }

        public Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        public QdrantRepository build() {
            return new QdrantRepository(this);
        }
    }
}