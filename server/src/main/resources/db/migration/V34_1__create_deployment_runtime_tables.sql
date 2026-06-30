create table matrixcode_deployment_operations (
    id varchar(64) not null,
    project_id varchar(64) not null,
    target_id varchar(64) not null,
    actor_id varchar(64) not null,
    operation_type varchar(32) not null,
    status varchar(32) not null,
    note text,
    created_at timestamp not null,
    primary key (id),
    constraint fk_mc_deploy_ops_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_deploy_ops_project_created on matrixcode_deployment_operations (project_id, created_at);
create index idx_mc_deploy_ops_target_type on matrixcode_deployment_operations (target_id, operation_type);

create table matrixcode_deployment_health_checks (
    id varchar(64) not null,
    project_id varchar(64) not null,
    target_id varchar(64) not null,
    actor_id varchar(64) not null,
    status varchar(32) not null,
    http_status int,
    duration_millis bigint not null,
    summary text,
    checked_at timestamp not null,
    primary key (id),
    constraint fk_mc_deploy_health_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_deploy_health_project_checked on matrixcode_deployment_health_checks (project_id, checked_at);
create index idx_mc_deploy_health_target on matrixcode_deployment_health_checks (target_id, checked_at);

create table matrixcode_compose_environments (
    id varchar(64) not null,
    project_id varchar(64) not null,
    target_id varchar(64) not null,
    workspace_id varchar(64) not null,
    compose_file_path varchar(500) not null,
    project_name varchar(120) not null,
    service_name varchar(120) not null,
    status varchar(32) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_compose_env_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_compose_env_project on matrixcode_compose_environments (project_id, created_at);
create index idx_mc_compose_env_target on matrixcode_compose_environments (target_id);

create table matrixcode_compose_operations (
    id varchar(64) not null,
    project_id varchar(64) not null,
    environment_id varchar(64) not null,
    actor_id varchar(64) not null,
    operation_type varchar(32) not null,
    status varchar(32) not null,
    summary text,
    log_excerpt text,
    created_at timestamp not null,
    primary key (id),
    constraint fk_mc_compose_ops_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_compose_ops_project_created on matrixcode_compose_operations (project_id, created_at);
create index idx_mc_compose_ops_environment on matrixcode_compose_operations (environment_id, created_at);
