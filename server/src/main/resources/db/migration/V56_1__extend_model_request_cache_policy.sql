alter table matrixcode_model_requests
    add column cache_policy_id varchar(80) not null default '' comment '模型请求采用的缓存策略版本，例如 stable-platform-prefix-v1；只保存策略标识，不保存完整 prompt、角色提示词、用户输入、向量召回正文或工具输出。';

alter table matrixcode_model_requests
    add column volatile_suffix_strategy varchar(120) not null default '' comment '模型请求动态后缀处理策略，例如 role-prompt-and-dynamic-context，用于说明角色提示词、用户指令、向量召回和工具输出不参与稳定前缀 fingerprint。';

create index idx_mc_model_requests_cache_policy on matrixcode_model_requests (project_id, cache_policy_id, created_at);
