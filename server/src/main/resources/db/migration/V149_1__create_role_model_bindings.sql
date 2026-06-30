create table matrixcode_role_model_bindings (
    id varchar(128) not null comment '角色模型绑定 ID，使用项目 ID 与角色键组成的稳定唯一标识。',
    project_id varchar(64) not null comment '所属项目 ID，关联 matrixcode_projects.id，用于项目级隔离角色模型绑定。',
    role_key varchar(64) not null comment '角色键，例如 PRODUCT、DEVELOPER、TESTER、OPERATIONS。',
    provider_id varchar(80) not null comment '模型供应商 ID，例如 qwen、deepseek、kimi、doubao。',
    model_name varchar(160) not null comment '模型名称，例如 qwen-max、deepseek-chat 或供应商具体模型版本。',
    currency varchar(16) not null comment '费用币种，用于模型成本估算展示。',
    cache_hit_per_million decimal(18, 6) not null comment '缓存命中输入 token 每百万价格，用于 prompt cache 成本估算。',
    cache_miss_input_per_million decimal(18, 6) not null comment '缓存未命中输入 token 每百万价格，用于模型成本估算。',
    output_per_million decimal(18, 6) not null comment '输出 token 每百万价格，用于模型成本估算。',
    context_budget_tokens int not null comment '该角色模型绑定的上下文 token 预算。',
    tool_contract_version varchar(64) not null comment '工具契约版本，用于约束该角色模型可使用的工具协议。',
    created_at timestamp not null comment '角色模型绑定创建时间。',
    updated_at timestamp not null comment '角色模型绑定最后更新时间。',
    primary key (id),
    constraint fk_mc_role_model_bindings_project foreign key (project_id) references matrixcode_projects (id)
) comment='保存每个项目角色当前选择的模型供应商、模型名称、费用参数和工具契约版本。';

create unique index uk_mc_role_model_bindings_project_role
    on matrixcode_role_model_bindings (project_id, role_key);

create index idx_mc_role_model_bindings_provider_model
    on matrixcode_role_model_bindings (project_id, provider_id, model_name);
