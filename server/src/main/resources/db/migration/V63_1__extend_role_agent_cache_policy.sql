alter table matrixcode_role_agent_configs
    add column cache_policy_id varchar(80) not null default 'stable-platform-prefix-v1' comment '角色智能体采用的模型缓存策略版本，例如 stable-platform-prefix-v1 或 deepseek-prefix-v2；只保存策略标识，不保存完整 prompt、用户输入、向量召回正文、工具输出或供应商密钥。';

alter table matrixcode_role_agent_configs
    add column volatile_suffix_strategy varchar(120) not null default 'role-prompt-and-dynamic-context' comment '角色智能体动态后缀处理策略，用于说明角色提示词、用户指令、向量召回和工具输出如何放在稳定平台前缀之后以提高供应商 prompt cache 命中率。';

create index idx_mc_agent_cfg_cache_policy on matrixcode_role_agent_configs (project_id, cache_policy_id);
