create table matrixcode_model_requests (
    id varchar(64) not null,
    project_id varchar(64) not null,
    role_key varchar(64) not null,
    provider_id varchar(80) not null,
    model_name varchar(160) not null,
    answer_summary text not null,
    usage_role_session_id varchar(160) not null,
    usage_model varchar(160) not null,
    cache_hit_tokens bigint not null,
    cache_miss_input_tokens bigint not null,
    output_tokens bigint not null,
    cache_hit_rate double not null,
    estimated_cost double not null,
    currency varchar(16) not null,
    context_types text,
    created_at timestamp not null,
    primary key (id),
    constraint fk_mc_model_requests_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_model_requests_project_created on matrixcode_model_requests (project_id, created_at);
create index idx_mc_model_requests_project_role on matrixcode_model_requests (project_id, role_key, created_at);
create index idx_mc_model_requests_provider_model on matrixcode_model_requests (provider_id, model_name);
