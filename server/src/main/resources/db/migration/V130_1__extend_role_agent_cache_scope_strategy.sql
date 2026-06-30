alter table matrixcode_role_agent_configs
    add column cache_scope_strategy varchar(64) not null default 'provider-model' comment '角色智能体缓存作用域策略：provider-model 表示按项目、角色、供应商和模型隔离；provider-role 表示同项目同角色同供应商跨模型复用；project-role 表示同项目同角色最大复用。';

create index idx_mc_agent_cfg_cache_scope on matrixcode_role_agent_configs (project_id, cache_scope_strategy);
