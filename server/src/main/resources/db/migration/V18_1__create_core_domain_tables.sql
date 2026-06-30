create table matrixcode_users (
    id varchar(64) not null,
    username varchar(120) not null,
    display_name varchar(120) not null,
    email varchar(255),
    status varchar(32) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id)
);

create unique index uk_mc_users_username on matrixcode_users (username);
create index idx_mc_users_status on matrixcode_users (status);

create table matrixcode_projects (
    id varchar(64) not null,
    name varchar(160) not null,
    description text,
    owner_user_id varchar(64),
    status varchar(32) not null,
    current_stage varchar(64),
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_projects_owner foreign key (owner_user_id) references matrixcode_users (id)
);

create index idx_mc_projects_status on matrixcode_projects (status);
create index idx_mc_projects_owner on matrixcode_projects (owner_user_id);

create table matrixcode_project_members (
    id varchar(64) not null,
    project_id varchar(64) not null,
    user_id varchar(64) not null,
    role_key varchar(64) not null,
    status varchar(32) not null,
    joined_at timestamp not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_members_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_members_user foreign key (user_id) references matrixcode_users (id)
);

create unique index uk_mc_members_project_user_role on matrixcode_project_members (project_id, user_id, role_key);
create index idx_mc_members_project on matrixcode_project_members (project_id);
create index idx_mc_members_user on matrixcode_project_members (user_id);

create table matrixcode_role_agent_configs (
    id varchar(64) not null,
    project_id varchar(64) not null,
    role_key varchar(64) not null,
    display_name varchar(120) not null,
    agent_kind varchar(64) not null,
    model_provider varchar(80),
    model_name varchar(120),
    tool_contract_version varchar(64),
    temperature decimal(6, 4),
    system_prompt text,
    user_prompt_template text,
    theme_color varchar(32),
    font_family varchar(120),
    font_size int,
    sort_order int not null,
    enabled boolean not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_agent_configs_project foreign key (project_id) references matrixcode_projects (id)
);

create unique index uk_mc_agent_cfg_project_role on matrixcode_role_agent_configs (project_id, role_key);
create index idx_mc_agent_cfg_project_kind on matrixcode_role_agent_configs (project_id, agent_kind);

create table matrixcode_collaboration_sessions (
    id varchar(64) not null,
    project_id varchar(64) not null,
    title varchar(200) not null,
    status varchar(32) not null,
    started_by_user_id varchar(64),
    started_at timestamp not null,
    ended_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_collab_sessions_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_collab_sessions_user foreign key (started_by_user_id) references matrixcode_users (id)
);

create index idx_mc_collab_sessions_project on matrixcode_collaboration_sessions (project_id);
create index idx_mc_collab_sessions_status on matrixcode_collaboration_sessions (status);

create table matrixcode_collaboration_participants (
    id varchar(64) not null,
    session_id varchar(64) not null,
    user_id varchar(64),
    role_key varchar(64) not null,
    agent_config_id varchar(64),
    presence_status varchar(32) not null,
    cursor_label varchar(120),
    joined_at timestamp not null,
    last_seen_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_collab_participants_session foreign key (session_id) references matrixcode_collaboration_sessions (id),
    constraint fk_mc_collab_participants_user foreign key (user_id) references matrixcode_users (id),
    constraint fk_mc_collab_participants_agent foreign key (agent_config_id) references matrixcode_role_agent_configs (id)
);

create index idx_mc_collab_participants_session on matrixcode_collaboration_participants (session_id);
create index idx_mc_collab_participants_user on matrixcode_collaboration_participants (user_id);

create table matrixcode_documents (
    id varchar(64) not null,
    project_id varchar(64) not null,
    document_type varchar(64) not null,
    title varchar(200) not null,
    status varchar(32) not null,
    version int not null,
    frozen boolean not null,
    content text,
    created_by_role varchar(64),
    updated_by_role varchar(64),
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_documents_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_documents_project_type on matrixcode_documents (project_id, document_type);
create index idx_mc_documents_status on matrixcode_documents (status);

create table matrixcode_bugs (
    id varchar(64) not null,
    project_id varchar(64) not null,
    title varchar(200) not null,
    description text,
    severity varchar(32) not null,
    status varchar(32) not null,
    created_by_role varchar(64) not null,
    current_owner_role varchar(64) not null,
    related_document_id varchar(64),
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_bugs_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_bugs_document foreign key (related_document_id) references matrixcode_documents (id)
);

create index idx_mc_bugs_project_status on matrixcode_bugs (project_id, status);
create index idx_mc_bugs_owner on matrixcode_bugs (current_owner_role);

create table matrixcode_deployment_targets (
    id varchar(64) not null,
    project_id varchar(64) not null,
    name varchar(120) not null,
    environment_key varchar(64) not null,
    provider varchar(80),
    status varchar(32) not null,
    endpoint_url varchar(500),
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_deploy_targets_project foreign key (project_id) references matrixcode_projects (id)
);

create unique index uk_mc_deploy_targets_env on matrixcode_deployment_targets (project_id, environment_key);
create index idx_mc_deploy_targets_status on matrixcode_deployment_targets (status);

create table matrixcode_runtime_notifications (
    id varchar(64) not null,
    project_id varchar(64) not null,
    user_id varchar(64),
    level_key varchar(32) not null,
    source_type varchar(64) not null,
    source_id varchar(120),
    title varchar(200) not null,
    message text,
    read_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_notifications_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_notifications_user foreign key (user_id) references matrixcode_users (id)
);

create index idx_mc_notifications_project on matrixcode_runtime_notifications (project_id, created_at);
create index idx_mc_notifications_user_read on matrixcode_runtime_notifications (user_id, read_at);

create table matrixcode_local_execution_tasks (
    id varchar(64) not null,
    project_id varchar(64) not null,
    workspace_id varchar(64),
    requested_by_role varchar(64) not null,
    approval_record_id varchar(64),
    command_text text not null,
    status varchar(32) not null,
    exit_code int,
    started_at timestamp,
    finished_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_execution_tasks_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_execution_tasks_project on matrixcode_local_execution_tasks (project_id, status);
create index idx_mc_execution_tasks_created on matrixcode_local_execution_tasks (created_at);

create table matrixcode_audit_records (
    id varchar(64) not null,
    project_id varchar(64) not null,
    actor_user_id varchar(64),
    actor_role varchar(64),
    action_key varchar(120) not null,
    target_type varchar(80) not null,
    target_id varchar(120),
    decision varchar(32),
    summary text,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_audit_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_audit_user foreign key (actor_user_id) references matrixcode_users (id)
);

create index idx_mc_audit_project_action on matrixcode_audit_records (project_id, action_key);
create index idx_mc_audit_target on matrixcode_audit_records (target_type, target_id);

create table matrixcode_project_events (
    id varchar(64) not null,
    project_id varchar(64) not null,
    event_type varchar(120) not null,
    source_role varchar(64),
    source_id varchar(120),
    title varchar(200) not null,
    payload text,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_events_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_events_project_created on matrixcode_project_events (project_id, created_at);
create index idx_mc_events_type on matrixcode_project_events (event_type);
