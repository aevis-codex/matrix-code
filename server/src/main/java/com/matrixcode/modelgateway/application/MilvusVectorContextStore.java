package com.matrixcode.modelgateway.application;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.matrixcode.modelgateway.domain.ModelRole;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "matrixcode.model-gateway.vector-context", name = "store", havingValue = "milvus")
public class MilvusVectorContextStore implements VectorContextStore {

    private static final String ID_FIELD = "id";
    private static final String VECTOR_FIELD = "embedding";
    private static final String PROJECT_FIELD = "project_id";
    private static final String ROLE_FIELD = "role_key";
    private static final String TYPE_FIELD = "type";
    private static final String SUMMARY_FIELD = "summary";

    private final ModelGatewayProperties properties;
    private MilvusClientV2 client;
    private boolean collectionReady;

    public MilvusVectorContextStore(ModelGatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void upsert(VectorContextEntry entry) {
        ensureCollection();
        var row = new JsonObject();
        row.addProperty(ID_FIELD, entry.id());
        row.addProperty(PROJECT_FIELD, entry.projectId());
        row.addProperty(ROLE_FIELD, entry.role().name());
        row.addProperty(TYPE_FIELD, entry.type());
        row.addProperty(SUMMARY_FIELD, entry.summary());
        row.add(VECTOR_FIELD, vector(entry.embedding()));

        client().upsert(UpsertReq.builder()
                .collectionName(collection())
                .data(List.of(row))
                .build());
        client().flush(FlushReq.builder()
                .databaseName(database())
                .collectionNames(List.of(collection()))
                .waitFlushedTimeoutMs(30_000L)
                .build());
    }

    @Override
    public List<VectorContextHit> search(String projectId, ModelRole role, List<Float> embedding, int topK) {
        if (embedding == null || embedding.isEmpty() || topK <= 0) {
            return List.of();
        }
        ensureCollection();
        var response = client().search(SearchReq.builder()
                .collectionName(collection())
                .annsField(VECTOR_FIELD)
                .metricType(IndexParam.MetricType.COSINE)
                .topK(topK)
                .filter("%s == \"%s\" && %s == \"%s\"".formatted(
                        PROJECT_FIELD,
                        escape(projectId),
                        ROLE_FIELD,
                        escape(role.name())
                ))
                .outputFields(List.of(TYPE_FIELD, SUMMARY_FIELD))
                .data(List.of(new FloatVec(embedding)))
                .build());
        return hitsFrom(response);
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
            collectionReady = false;
        }
    }

    private synchronized void ensureCollection() {
        if (collectionReady) {
            return;
        }
        var hasCollection = client().hasCollection(HasCollectionReq.builder()
                .collectionName(collection())
                .build());
        if (!hasCollection) {
            var indexParam = IndexParam.builder()
                    .fieldName(VECTOR_FIELD)
                    .indexType(IndexParam.IndexType.AUTOINDEX)
                    .metricType(IndexParam.MetricType.COSINE)
                    .build();
            client().createCollection(CreateCollectionReq.builder()
                    .collectionName(collection())
                    .description("MatrixCode role-agent vector context chunks")
                    .dimension(properties.getVectorContext().getDimension())
                    .primaryFieldName(ID_FIELD)
                    .idType(DataType.VarChar)
                    .maxLength(128)
                    .vectorFieldName(VECTOR_FIELD)
                    .metricType(IndexParam.MetricType.COSINE.name())
                    .autoID(false)
                    .enableDynamicField(true)
                    .indexParam(indexParam)
                    .build());
        }
        client().loadCollection(LoadCollectionReq.builder()
                .databaseName(database())
                .collectionName(collection())
                .sync(true)
                .timeout(properties.getMilvus().getLoadTimeoutMs())
                .build());
        collectionReady = true;
    }

    private synchronized MilvusClientV2 client() {
        if (client != null) {
            return client;
        }
        var milvus = properties.getMilvus();
        if (!milvus.getDatabase().isBlank()) {
            ensureDatabase(milvus);
        }
        client = new MilvusClientV2(connectConfig(milvus, true));
        return client;
    }

    private void ensureDatabase(ModelGatewayProperties.Milvus milvus) {
        MilvusClientV2 bootstrapClient = null;
        try {
            bootstrapClient = new MilvusClientV2(connectConfig(milvus, false));
            var databases = bootstrapClient.listDatabases().getDatabaseNames();
            if (!databases.contains(milvus.getDatabase())) {
                bootstrapClient.createDatabase(CreateDatabaseReq.builder()
                        .databaseName(milvus.getDatabase())
                        .build());
            }
        } finally {
            if (bootstrapClient != null) {
                bootstrapClient.close();
            }
        }
    }

    private ConnectConfig connectConfig(ModelGatewayProperties.Milvus milvus, boolean includeDatabase) {
        var builder = ConnectConfig.builder()
                .uri("%s://%s:%d".formatted(milvus.isSecure() ? "https" : "http", milvus.getHost(), milvus.getPort()))
                .connectTimeoutMs(10_000)
                .rpcDeadlineMs(60_000);
        if (includeDatabase && !milvus.getDatabase().isBlank()) {
            builder.dbName(milvus.getDatabase());
        }
        if (!milvus.getToken().isBlank()) {
            builder.token(milvus.getToken());
        }
        if (!milvus.getUsername().isBlank()) {
            builder.username(milvus.getUsername());
            builder.password(milvus.getPassword());
        }
        return builder.build();
    }

    private String collection() {
        return properties.getMilvus().getCollection();
    }

    private String database() {
        return properties.getMilvus().getDatabase();
    }

    private JsonArray vector(List<Float> embedding) {
        var array = new JsonArray();
        embedding.forEach(array::add);
        return array;
    }

    private List<VectorContextHit> hitsFrom(SearchResp response) {
        var hits = new ArrayList<VectorContextHit>();
        for (var batch : response.getSearchResults()) {
            for (var result : batch) {
                var entity = result.getEntity();
                hits.add(new VectorContextHit(
                        String.valueOf(entity.getOrDefault(TYPE_FIELD, "VECTOR_CONTEXT")),
                        String.valueOf(entity.getOrDefault(SUMMARY_FIELD, "")),
                        result.getScore() == null ? 0 : result.getScore()
                ));
            }
        }
        return List.copyOf(hits);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
