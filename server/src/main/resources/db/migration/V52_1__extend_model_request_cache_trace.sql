alter table matrixcode_model_requests
    add column cache_source varchar(32) not null default 'ESTIMATED' comment '模型请求缓存用量来源：PROVIDER 表示供应商真实 prompt cache 字段，ESTIMATED 表示 MatrixCode 本地稳定前缀估算。';

alter table matrixcode_model_requests
    add column cache_scope_id varchar(240) not null default '' comment 'MatrixCode 内部缓存作用域，由项目、角色、供应商和模型规范化拼接，用于隔离供应商缓存和本地估算。';

alter table matrixcode_model_requests
    add column stable_prefix_hash varchar(128) not null default '' comment '提示词契约稳定前缀哈希，用于观察同一角色基础上下文是否保持字节级稳定。';

alter table matrixcode_model_requests
    add column provider_usage_available boolean not null default false comment '是否使用供应商返回的真实 prompt cache usage 字段计算本次模型用量。';

create index idx_mc_model_requests_cache_trace on matrixcode_model_requests (project_id, cache_source, created_at);
